package com.badol.studio;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 기보 뷰어 Activity
 *
 * ── 핵심 설계 원칙 ──────────────────────────────────────────────────────────
 *
 * [기보 재생 모드]
 *   sec.moves = List<int[4]>  각 항목: [moveNum(1-based), color, x, y]
 *   goToStep(n): 기본도에서 n수까지 goRules.rebuildState()로 완전 재계산
 *   moveNum 배열은 항상 재계산으로만 갱신한다.
 *
 * [놓아보기 모드]
 *   tryHistory = List<int[3]>  각 항목: [color, x, y]
 *   착수 번호 = 인덱스 + 1
 *   undo = tryHistory 마지막 항목 제거 후 rebuildTryState() 재계산
 *   따내기/undo/재생 모두 rebuildTryState() 한 곳에서 처리한다.
 */
public class ViewerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_URI  = "file_uri";

    // ── UI ───────────────────────────────────────────
    private BoardView   boardView;
    private TextView    tvInfo;
    private TextView    tvMoveInfo;
    private TextView    tvComment;
    private SeekBar     seekBar;
    private ImageButton btnFirst, btnPrev, btnNext, btnLast, btnPlay;
    private Button      btnToggleColor;
    private Button      btnStartTryMove;
    private ListView    lvSectionList;

    // ── 데이터 ───────────────────────────────────────
    private List<BdkSection> sections = new ArrayList<>();
    private int currentSectionIdx = 0;

    // ── 보드 상태 ────────────────────────────────────
    private int[][] board;
    private int[][] moveNum;
    private int currentStep;
    private int boardSize = 19;

    // ── 자동 재생 ────────────────────────────────────
    private boolean isPlaying = false;
    private static final int PLAY_SPEED_MS = 1000;
    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private Runnable playRunnable;

    // ── 착수음 ───────────────────────────────────────
    private SoundPool soundPool;
    private int       soundId      = -1;
    private boolean   soundReady   = false;
    private boolean   soundEnabled = true;

    private static final String PREFS_NAME  = "viewer_prefs";
    private static final String PREF_SOUND  = "sound_enabled";
    private static final String PREF_COORDS = "show_coords";

    // ── 파일 정보 ────────────────────────────────────
    private String currentFilePath = null;
    private String currentFileUri  = null;
    private String currentFileName = null;

    // ── 돌 설명 표시 ─────────────────────────────────
    private boolean showStoneComment = false;

    // ── 놓아보기 모드 ────────────────────────────────
    // tryHistory: 놓아보기 착수 이력 [color, x, y]
    // tryRedo:    undo 후 redo 가능한 이력 (앞으로 가기용)
    private boolean           tryMoveMode  = false;
    private final List<int[]> tryHistory   = new ArrayList<>();
    private final List<int[]> tryRedo      = new ArrayList<>();
    private int               nextTryColor = BdkSection.BLACK;
    private int               tryKoX       = -1;
    private int               tryKoY       = -1;

    // ── 바둑 규칙 헬퍼 ────────────────────────────────
    private GoRules goRules;

    // ── 상태 복원 키 ─────────────────────────────────
    private static final String KEY_SECTION_IDX = "section_idx";
    private static final String KEY_STEP        = "step";
    private static final String KEY_TRY_MODE    = "try_mode";
    private static final String KEY_TRY_MOVES   = "try_moves";
    private static final String KEY_NEXT_COLOR  = "next_color";

    // ══════════════════════════════════════════════════
    //  생명주기
    // ══════════════════════════════════════════════════

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_SECTION_IDX, currentSectionIdx);
        out.putInt(KEY_STEP, currentStep);
        out.putBoolean(KEY_TRY_MODE, tryMoveMode);
        out.putInt(KEY_NEXT_COLOR, nextTryColor);
        if (!tryHistory.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int[] m : tryHistory) {
                if (sb.length() > 0) sb.append('|');
                sb.append(m[0]).append(',').append(m[1]).append(',').append(m[2]);
            }
            out.putString(KEY_TRY_MOVES, sb.toString());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        playRunnable = () -> {
            BdkSection sec = currentSection();
            if (isPlaying && sec != null && currentStep < sec.moves.size()) {
                currentStep++;
                refreshDisplay();
                handler.postDelayed(playRunnable, PLAY_SPEED_MS);
            } else {
                stopPlay();
            }
        };
        setContentView(R.layout.activity_viewer);
        initWindow();
        bindViews();
        setupListeners();
        initSound();

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String uri  = getIntent().getStringExtra(EXTRA_FILE_URI);
        final int     rSecIdx = savedInstanceState != null ? savedInstanceState.getInt(KEY_SECTION_IDX, 0) : 0;
        final int     rStep   = savedInstanceState != null ? savedInstanceState.getInt(KEY_STEP, 0) : 0;
        final boolean rTry    = savedInstanceState != null && savedInstanceState.getBoolean(KEY_TRY_MODE, false);
        final int     rColor  = savedInstanceState != null ? savedInstanceState.getInt(KEY_NEXT_COLOR, BdkSection.BLACK) : BdkSection.BLACK;
        final String  rMoves  = savedInstanceState != null ? savedInstanceState.getString(KEY_TRY_MOVES, null) : null;

        if (path != null) {
            currentFilePath = path;
            loadFromPath(path, rSecIdx, rStep, rTry, rColor, rMoves);
        } else if (uri != null) {
            currentFileUri = uri;
            loadFromUri(Uri.parse(uri), rSecIdx, rStep, rTry, rColor, rMoves);
        } else {
            Toast.makeText(this, "파일 경로가 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlay();
        if (soundPool != null) { soundPool.release(); soundPool = null; }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(R.layout.activity_viewer);
        initWindow();
        bindViews();
        setupListeners();
        if (!sections.isEmpty()) {
            setupSectionListView();
            boardView.setBoardSize(boardSize);
            boardView.setBoard(copyBoard());
            boardView.setMoveNumbers(moveNum);
            boardView.invalidate();
            refreshDisplay();
            restoreTryModeUI();
        }
    }

    // ══════════════════════════════════════════════════
    //  초기화 헬퍼
    // ══════════════════════════════════════════════════

    private void initWindow() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            if (currentFileName != null) getSupportActionBar().setTitle(currentFileName);
        }

        AppBarLayout appBar = findViewById(R.id.appBarLayout);
        View rootView = findViewById(android.R.id.content);
        applyWindowInsets(appBar, rootView);
        ViewCompat.requestApplyInsets(rootView);
    }

    private void bindViews() {
        boardView       = findViewById(R.id.boardView);
        tvInfo          = findViewById(R.id.tvInfo);
        tvMoveInfo      = findViewById(R.id.tvMoveInfo);
        tvComment       = findViewById(R.id.tvComment);
        seekBar         = findViewById(R.id.seekBar);
        btnFirst        = findViewById(R.id.btnFirst);
        btnPrev         = findViewById(R.id.btnPrev);
        btnNext         = findViewById(R.id.btnNext);
        btnLast         = findViewById(R.id.btnLast);
        btnPlay         = findViewById(R.id.btnPlay);
        btnToggleColor  = findViewById(R.id.btnToggleColor);
        btnStartTryMove = findViewById(R.id.btnStartTryMove);
        lvSectionList   = findViewById(R.id.lvSectionList);
    }

    private void setupListeners() {
        if (btnStartTryMove != null)
            btnStartTryMove.setOnClickListener(v -> toggleTryMoveMode());

        btnFirst.setOnClickListener(v -> { stopPlay(); onNavFirst(); });
        btnPrev .setOnClickListener(v -> { stopPlay(); onNavPrev(); });
        btnNext .setOnClickListener(v -> { stopPlay(); onNavNext(); });
        btnLast .setOnClickListener(v -> { stopPlay(); onNavLast(); });
        btnPlay.setOnClickListener(v -> { if (isPlaying) stopPlay(); else startPlay(); });

        btnToggleColor.setOnClickListener(v -> {
            nextTryColor = opponent(nextTryColor);
            updateToggleColorButton();
            // 수동 색 전환 시 드래그 미리보기 색 동기화
            boardView.setHoverColor(nextTryColor);
            updateTryInfo();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) { stopPlay(); seekToStep(progress); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        boardView.setTouchListener((bx, by) -> {
            if (tryMoveMode) {
                handleTryMoveTouch(bx, by);
            } else if (showStoneComment) {
                showStoneCommentAt(bx, by);
            }
        });
        boardView.setDoubleTapListener(() -> { if (tryMoveMode) undoTryMove(); });
    }

    private void initSound() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        soundEnabled = prefs.getBoolean(PREF_SOUND, true);
        boardView.setShowCoords(prefs.getBoolean(PREF_COORDS, false));
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        soundPool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(aa).build();
        soundPool.setOnLoadCompleteListener((sp, id, status) -> { if (status == 0) soundReady = true; });
        soundId = soundPool.load(this, R.raw.stone_click, 1);
    }

    private void restoreTryModeUI() {
        if (btnStartTryMove != null) {
            btnStartTryMove.setVisibility(
                    currentSectionIdx == 0 ? View.VISIBLE : View.INVISIBLE);
        }
        if (tryMoveMode) {
            setTryModeUI(true);
            // 놓아보기 모드 상태 복원 시 드래그 미리보기 활성화
            boardView.setDragPlaceMode(true);
            boardView.setHoverColor(nextTryColor);
            updateTryInfo();
        } else {
            setTryModeUI(false);
            boardView.setDragPlaceMode(false);
        }
    }

    // ══════════════════════════════════════════════════
    //  파일 로드
    // ══════════════════════════════════════════════════

    private void loadFromPath(String path, int secIdx, int step, boolean tryMode, int nextColor, String movesStr) {
        new Thread(() -> {
            try {
                File f = new File(path);
                List<BdkSection> result = parseFileByExt(f, f.getName());
                runOnUiThread(() -> onSectionsLoaded(result, f.getName(),
                        secIdx, step, tryMode, nextColor, movesStr));
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show(); finish(); });
            }
        }).start();
    }

    private void loadFromUri(Uri uri, int secIdx, int step, boolean tryMode, int nextColor, String movesStr) {
        new Thread(() -> {
            try {
                String seg = uri.getLastPathSegment();
                String name = seg != null ? new java.io.File(seg).getName() : "기보";
                boolean isBdk = name.toLowerCase().endsWith(".bdk");
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception("파일을 열 수 없습니다.");
                File tmp = new File(getCacheDir(), isBdk ? "tmp.bdk" : "tmp.sgf");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                is.close();
                List<BdkSection> result = parseFileByExt(tmp, name);
                runOnUiThread(() -> onSectionsLoaded(result, name,
                        secIdx, step, tryMode, nextColor, movesStr));
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show(); finish(); });
            }
        }).start();
    }

    /**
     * 파일 확장자에 따라 SGF 또는 BDK 파서로 파싱
     */
    private List<BdkSection> parseFileByExt(File file, String fileName) throws Exception {
        if (fileName.toLowerCase().endsWith(".bdk")) {
            return BdkParser.parse(file);
        } else {
            return SgfParser.parse(file);
        }
    }

    private void onSectionsLoaded(List<BdkSection> result, String fileName,
                                   int rSecIdx, int rStep, boolean rTry, int rColor, String rMovesStr) {
        if (result == null || result.isEmpty()) {
            Toast.makeText(this, "기보 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        sections = result;
        currentFileName = fileName;
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(fileName);
        setupSectionListView();

        int target = (rSecIdx < sections.size()) ? rSecIdx : 0;
        switchSection(target);
        if (rStep > 0) goToStep(rStep);

        if (rTry) {
            tryMoveMode  = true;
            nextTryColor = rColor;
            tryHistory.clear();
            tryRedo.clear();
            if (rMovesStr != null && !rMovesStr.isEmpty()) {
                for (String token : rMovesStr.split("\\|")) {
                    String[] p = token.split(",");
                    if (p.length == 3) {
                        int c = Integer.parseInt(p[0]);
                        int x = Integer.parseInt(p[1]);
                        int y = Integer.parseInt(p[2]);
                        tryHistory.add(new int[]{c, x, y});
                    }
                }
            }
            rebuildTryState();
            setTryModeUI(true);
            // 놓아보기 모드 복원 시 드래그 미리보기 활성화
            boardView.setDragPlaceMode(true);
            boardView.setHoverColor(nextTryColor);
            boardView.setBoard(copyBoard());
            boardView.setMoveNumbers(copyMoveNum());
            // 놓아보기 이력이 있으면 마지막수 표시, 없으면 삼각형 표시
            if (!tryHistory.isEmpty()) {
                int[] last = tryHistory.get(tryHistory.size() - 1);
                boardView.setLastMove(last[1], last[2]);
            } else {
                BdkSection sec = currentSection();
                if (sec != null && !sec.initialStones.isEmpty()) {
                    int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
                    boardView.setTriangleMark(ls[1], ls[2]);
                }
            }
            boardView.invalidate();
            updateTryInfo();
        }
    }

    // ══════════════════════════════════════════════════
    //  기보 목록
    // ══════════════════════════════════════════════════

    private void setupSectionListView() {
        if (lvSectionList == null) return;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++)
            names.add((i + 1) + ". " + sections.get(i).name);
        SectionListAdapter adapter = new SectionListAdapter(names);
        lvSectionList.setAdapter(adapter);
        lvSectionList.setOnItemClickListener((parent, view, which, id) -> {
            if (which == currentSectionIdx && tryMoveMode) toggleTryMoveMode();
            else { switchSection(which); adapter.setSelected(which); }
        });
    }

    private class SectionListAdapter extends android.widget.BaseAdapter {
        private final List<String> items;
        private int selectedPos = 0;

        SectionListAdapter(List<String> items) { this.items = items; }
        void setSelected(int pos) { selectedPos = pos; notifyDataSetChanged(); }

        @Override public int    getCount()         { return items.size(); }
        @Override public Object getItem(int pos)   { return items.get(pos); }
        @Override public long   getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, android.view.ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(ViewerActivity.this);
                tv.setPadding(20, 18, 20, 18);
                tv.setTextSize(14f);
            }
            tv.setText(items.get(pos));
            if (pos == selectedPos) {
                tv.setBackgroundColor(0xFFBBDEFB);
                tv.setTextColor(0xFF0D47A1);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                tv.setBackgroundColor(0xFFFFFFFF);
                tv.setTextColor(0xFF222222);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
            return tv;
        }
    }

    // ══════════════════════════════════════════════════
    //  섹션 전환
    // ══════════════════════════════════════════════════

    private void switchSection(int idx) {
        if (idx < 0 || idx >= sections.size()) return;
        currentSectionIdx = idx;
        BdkSection sec = sections.get(idx);

        if (lvSectionList != null && lvSectionList.getAdapter() instanceof SectionListAdapter) {
            ((SectionListAdapter) lvSectionList.getAdapter()).setSelected(idx);
            lvSectionList.setSelection(idx);
        }

        boardSize = sec.boardSize;
        board     = new int[boardSize + 2][boardSize + 2];
        moveNum   = new int[boardSize + 2][boardSize + 2];
        boardView.setBoardSize(boardSize);
        currentStep  = 0;
        tryMoveMode  = false;
        tryHistory.clear();
        tryRedo.clear();
        goRules  = new GoRules(boardSize);
        tryKoX   = -1;
        tryKoY   = -1;

        if (sec.hasZoom()) {
            boardView.setZoomRegion(sec.zoomX1, sec.zoomY1, sec.zoomX2, sec.zoomY2);
        } else {
            boardView.resetZoom();
        }
        nextTryColor = inferNextColor(sec);

        if (btnToggleColor  != null) btnToggleColor.setVisibility(View.INVISIBLE);
        goToStep(0);
        if (btnStartTryMove != null)
            btnStartTryMove.setVisibility(
                    idx == 0 ? View.VISIBLE : View.INVISIBLE);
    }

    // ══════════════════════════════════════════════════
    //  네비게이션
    // ══════════════════════════════════════════════════

    private void onNavFirst() {
        if (tryMoveMode) {
            seekToStep(0);
        } else {
            goToStep(0);
        }
    }

    private void onNavPrev() {
        if (tryMoveMode) {
            seekToStep(tryHistory.size() - 1);
        } else {
            goToStep(currentStep - 1);
        }
    }

    private void onNavNext() {
        if (tryMoveMode) {
            seekToStep(tryHistory.size() + 1);
        } else {
            goToStep(currentStep + 1);
        }
    }

    private void onNavLast() {
        if (tryMoveMode) {
            int total = tryHistory.size() + tryRedo.size();
            seekToStep(total);
        } else {
            BdkSection s = currentSection();
            if (s != null) goToStep(s.moves.size());
        }
    }

    /**
     * seekBar 또는 네비게이션 버튼에서 특정 수순으로 이동.
     * 놓아보기 모드: tryHistory/tryRedo 재배치 후 rebuildTryState()
     * 뷰어 모드: goToStep()
     */
    private void seekToStep(int target) {
        if (tryMoveMode) {
            int total = tryHistory.size() + tryRedo.size();
            target = Math.max(0, Math.min(target, total));
            // tryHistory + tryRedo를 합쳐 전체 이력 구성
            List<int[]> all = new ArrayList<>(tryHistory);
            all.addAll(tryRedo);
            // 역순으로 저장된 tryRedo를 올바른 순서로 합치기
            // tryRedo는 undo 순서로 쌓이므로 역순이 원래 순서
            java.util.Collections.reverse(all.subList(tryHistory.size(), all.size()));
            tryHistory.clear();
            tryRedo.clear();
            for (int i = 0; i < target && i < all.size(); i++)
                tryHistory.add(all.get(i));
            for (int i = target; i < all.size(); i++)
                tryRedo.add(all.get(i));
            // tryRedo는 다시 역순으로 (undo 순서)
            java.util.Collections.reverse(tryRedo);

            rebuildTryState();
            updateTryBoardView();
            updateTryInfo();
            updateSeekBar();
        } else {
            goToStep(target);
        }
    }

    // ══════════════════════════════════════════════════
    //  놓아보기 모드
    // ══════════════════════════════════════════════════

    private void toggleTryMoveMode() {
        if (!tryMoveMode) {
            tryMoveMode = true;
            tryHistory.clear();
            tryRedo.clear();
            tryKoX = -1; tryKoY = -1;
            nextTryColor = inferNextColor(currentSection());
            setTryModeUI(true);
            // 놓아보기 모드 진입: 드래그 반투명 미리보기 활성화
            boardView.setDragPlaceMode(true);
            boardView.setHoverColor(nextTryColor);
            BdkSection sec = currentSection();
            if (sec != null && !sec.initialStones.isEmpty()) {
                int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
                boardView.setTriangleMark(ls[1], ls[2]);
            }
            updateTryInfo();
        } else {
            tryMoveMode = false;
            tryHistory.clear();
            tryRedo.clear();
            nextTryColor = inferNextColor(currentSection());
            setTryModeUI(false);
            // 놓아보기 모드 종료: 드래그 미리보기 비활성화
            boardView.setDragPlaceMode(false);
            if (tvInfo != null) {
                tvInfo.setBackgroundColor(0xFFF5F5F5);
                tvInfo.setTextColor(0xFF444444);
            }
            boardView.resetZoom();
            goToStep(0);
        }
    }

    private void setTryModeUI(boolean active) {
        if (btnStartTryMove != null) {
            btnStartTryMove.setText(active ? "기본도" : "놓아보기");
            btnStartTryMove.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(active ? 0xFF388E3C : 0xFF1565C0));
            if (active) btnStartTryMove.setVisibility(View.VISIBLE);
        }
        if (btnToggleColor != null) {
            btnToggleColor.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            if (active) updateToggleColorButton();
        }
    }

    private void handleTryMoveTouch(int bx, int by) {
        if (goRules == null) goRules = new GoRules(boardSize);
        GoRules.PlaceResult result = goRules.tryPlace(board, nextTryColor, bx, by, tryKoX, tryKoY);
        if (!result.ok) {
            String msg;
            if (GoRules.REASON_OCCUPIED.equals(result.reason))     msg = "이미 돌이 있는 자리입니다.";
            else if (GoRules.REASON_SUICIDE.equals(result.reason))  msg = "자살수는 둘 수 없습니다.";
            else if (GoRules.REASON_KO.equals(result.reason))       msg = "패로 인해 착수할 수 없습니다.";
            else                                                     msg = "착수할 수 없는 자리입니다.";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return;
        }

        // 이력에 추가
        tryHistory.add(new int[]{nextTryColor, bx, by});
        tryRedo.clear(); // 새 착수 시 redo 이력 삭제

        // 보드 재계산
        rebuildTryState();

        if (tryHistory.size() == 1) boardView.clearTriangleMark();
        playStoneSound();
        updateTryBoardView();
        boardView.setLastMove(bx, by);
        // 다음 착수색 동기화 (드래그 미리보기 색)
        boardView.setHoverColor(nextTryColor);
        updateTryInfo();
        updateSeekBar();
    }

    private void undoTryMove() {
        if (tryHistory.isEmpty()) {
            Toast.makeText(this, "되돌릴 수가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] last = tryHistory.remove(tryHistory.size() - 1);
        tryRedo.add(last); // undo 스택에 저장

        rebuildTryState();
        updateTryBoardView();

        if (!tryHistory.isEmpty()) {
            int[] prev = tryHistory.get(tryHistory.size() - 1);
            boardView.setLastMove(prev[1], prev[2]);
        } else {
            boardView.clearLastMove();
            BdkSection sec = currentSection();
            if (sec != null && !sec.initialStones.isEmpty()) {
                int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
                boardView.setTriangleMark(ls[1], ls[2]);
            }
        }
        // undo 후 다음 착수색 동기화 (드래그 미리보기 색)
        boardView.setHoverColor(nextTryColor);
        updateTryInfo();
        updateSeekBar();
        Toast.makeText(this, "한 수 되돌렸습니다.", Toast.LENGTH_SHORT).show();
    }

    /**
     * tryHistory를 기반으로 보드 상태를 완전 재계산한다.
     * 착수 번호 = 인덱스 + 1 (따내기/undo와 무관하게 항상 연속)
     * 패 좌표도 재계산한다.
     */
    private void rebuildTryState() {
        BdkSection sec = currentSection();
        if (sec == null) return;
        if (goRules == null) goRules = new GoRules(boardSize);

        // 기본도 보드 구성
        int[][] baseBoard = new int[boardSize + 2][boardSize + 2];
        for (int[] s : sec.initialStones) {
            if (s[1] >= 1 && s[1] <= boardSize && s[2] >= 1 && s[2] <= boardSize)
                baseBoard[s[2]][s[1]] = s[0];
        }

        // tryHistory 기반 재계산
        int[] ko = goRules.rebuildState(baseBoard, tryHistory, tryHistory.size(), board, moveNum);
        tryKoX = ko[0];
        tryKoY = ko[1];

        // 다음 색: 마지막 착수 색의 반대
        if (tryHistory.isEmpty()) {
            nextTryColor = inferNextColor(sec);
        } else {
            int[] last = tryHistory.get(tryHistory.size() - 1);
            nextTryColor = opponent(last[0]);
        }
        updateToggleColorButton();
    }

    private void updateTryBoardView() {
        boardView.setBoard(copyBoard());
        boardView.setMoveNumbers(copyMoveNum());
        boardView.invalidate();
    }

    // ══════════════════════════════════════════════════
    //  수 이동 (뷰어 모드)
    // ══════════════════════════════════════════════════

    private void goToStep(int step) {
        BdkSection sec = currentSection();
        if (sec == null) return;
        if (goRules == null) goRules = new GoRules(boardSize);

        step = Math.max(0, Math.min(step, sec.moves.size()));

        // 기본도 구성
        int[][] baseBoard = new int[boardSize + 2][boardSize + 2];
        for (int[] s : sec.initialStones) {
            if (s[1] >= 1 && s[1] <= boardSize && s[2] >= 1 && s[2] <= boardSize)
                baseBoard[s[2]][s[1]] = s[0];
        }

        // sec.moves를 [color, x, y] 형식의 이력으로 변환하여 rebuildState 사용
        List<int[]> history = new ArrayList<>();
        for (int i = 0; i < step && i < sec.moves.size(); i++) {
            int[] m = sec.moves.get(i);
            // m = [moveNum(1-based), color, x, y]
            history.add(new int[]{m[1], m[2], m[3]});
        }

        board   = new int[boardSize + 2][boardSize + 2];
        moveNum = new int[boardSize + 2][boardSize + 2];
        goRules.rebuildState(baseBoard, history, step, board, moveNum);

        boolean advanced = (step > currentStep && step > 0);
        currentStep = step;
        boardView.setBoard(copyBoard());
        boardView.setMoveNumbers(copyMoveNum());

        if (step > 0 && step <= sec.moves.size()) {
            int[] last = sec.moves.get(step - 1);
            boardView.setLastMove(last[2], last[3]);
        } else {
            boardView.clearLastMove();
        }

        if (step == 0 && !sec.initialStones.isEmpty()) {
            int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
            boardView.setTriangleMark(ls[1], ls[2]);
        } else {
            boardView.clearTriangleMark();
        }
        boardView.invalidate();

        if (advanced) playStoneSound();

        int total = sec.moves.size();
        if (tvMoveInfo != null) {
            if (step == 0) tvMoveInfo.setText("기본도  (전체 " + total + "수)");
            else {
                int[] last = sec.moves.get(step - 1);
                tvMoveInfo.setText(step + "수: " + colorName(last[1]) + "  /  " + total + "수");
            }
        }
        seekBar.setMax(total == 0 ? 1 : total);
        seekBar.setProgress(step);

        if (tvComment != null) {
            String cmt = sec.comment;
            if (cmt != null && !cmt.isEmpty()) {
                tvComment.setText(cmt);
                tvComment.setVisibility(View.VISIBLE);
                final String fullComment = cmt;
                tvComment.setOnClickListener(v -> showCommentDialog(fullComment));
            } else {
                tvComment.setVisibility(View.GONE);
                tvComment.setOnClickListener(null);
            }
        }
    }

    private void updateSeekBar() {
        if (seekBar == null) return;
        int total = tryHistory.size() + tryRedo.size();
        seekBar.setMax(total == 0 ? 1 : total);
        seekBar.setProgress(tryHistory.size());
    }

    // ══════════════════════════════════════════════════
    //  주석 팝업
    // ══════════════════════════════════════════════════

    private void showStoneCommentAt(int bx, int by) {
        if (currentSectionIdx < 0 || currentSectionIdx >= sections.size()) return;
        if (tvComment == null) return;
        BdkSection sec = sections.get(currentSectionIdx);
        String key = bx + "," + by;
        String stoneCmt = (sec.stoneComments != null) ? sec.stoneComments.get(key) : null;
        if (stoneCmt != null && !stoneCmt.isEmpty()) {
            tvComment.setText(stoneCmt);
            tvComment.setVisibility(View.VISIBLE);
            tvComment.setOnClickListener(v -> showCommentDialog(stoneCmt));
        } else {
            tvComment.setVisibility(View.GONE);
            tvComment.setOnClickListener(null);
        }
    }

    private void showCommentDialog(String comment) {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(comment);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setLineSpacing(0, 1.4f);
        tv.setPadding(48, 32, 48, 32);
        tv.setTextColor(0xFF222222);
        scrollView.addView(tv);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("설명")
            .setView(scrollView)
            .setPositiveButton("닫기", null)
            .show();
    }

    // ══════════════════════════════════════════════════
    //  바둑 규칙 헬퍼
    // ══════════════════════════════════════════════════

    private int opponent(int color) { return (color == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK; }
    private String colorName(int color) { return (color == BdkSection.BLACK) ? "흑" : "백"; }

    /**
     * 다음 착수색 결정: initialStones의 마지막 seq 기반.
     * seq가 짝수(0-based)이면 흑이 마지막 → 다음=백
     * seq가 홀수이면 백이 마지막 → 다음=흑
     * 돌이 없으면 흑 시작.
     */
    private int inferNextColor(BdkSection sec) {
        if (sec == null || sec.initialStones.isEmpty()) return BdkSection.BLACK;
        int maxSeq = -1;
        for (int[] stone : sec.initialStones)
            if (stone[3] > maxSeq) maxSeq = stone[3];
        // seq는 0-based: 0=흑1번, 1=백2번, 2=흑3번, ...
        // seq가 짝수면 흑이 마지막 → 다음=백
        // seq가 홀수면 백이 마지막 → 다음=흑
        return (maxSeq % 2 == 0) ? BdkSection.WHITE : BdkSection.BLACK;
    }

    // ══════════════════════════════════════════════════
    //  보드 복사
    // ══════════════════════════════════════════════════

    private int[][] copyBoard() {
        int[][] copy = new int[boardSize + 2][boardSize + 2];
        for (int y = 0; y <= boardSize + 1; y++) System.arraycopy(board[y], 0, copy[y], 0, boardSize + 2);
        return copy;
    }

    private int[][] copyMoveNum() {
        int[][] copy = new int[boardSize + 2][boardSize + 2];
        for (int y = 0; y <= boardSize + 1; y++) System.arraycopy(moveNum[y], 0, copy[y], 0, boardSize + 2);
        return copy;
    }

    // ══════════════════════════════════════════════════
    //  UI 갱신
    // ══════════════════════════════════════════════════

    private void updateToggleColorButton() {
        if (btnToggleColor == null) return;
        boolean isBlack = (nextTryColor == BdkSection.BLACK);
        btnToggleColor.setText(isBlack ? "흑 ●" : "백 ○");
        btnToggleColor.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(isBlack ? 0xFF222222 : 0xFFEEEEEE));
        btnToggleColor.setTextColor(isBlack ? 0xFFFFFFFF : 0xFF333333);
    }

    private void updateTryInfo() {
        if (tvInfo == null) return;
        boolean isBlack = (nextTryColor == BdkSection.BLACK);
        int moveNo = tryHistory.size();
        tvInfo.setBackgroundColor(isBlack ? 0xFF424242 : 0xFFEEEEEE);
        tvInfo.setTextColor(isBlack ? 0xFFFFFFFF : 0xFF222222);
        String next = colorName(nextTryColor);
        String dot  = isBlack ? " ●" : " ○";
        if (moveNo == 0) tvInfo.setText("놓아보기  다음: " + next + dot + "  (더블탭=되돌리기)");
        else             tvInfo.setText(moveNo + "수: " + colorName(tryHistory.get(moveNo - 1)[0]) + "  다음: " + next + dot);
    }

    private BdkSection currentSection() {
        if (sections.isEmpty() || currentSectionIdx >= sections.size()) return null;
        return sections.get(currentSectionIdx);
    }

    private void refreshDisplay() { goToStep(currentStep); }

    // ══════════════════════════════════════════════════
    //  자동 재생
    // ══════════════════════════════════════════════════

    private void startPlay() {
        isPlaying = true;
        if (btnPlay != null) btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        handler.postDelayed(playRunnable, PLAY_SPEED_MS);
    }

    private void stopPlay() {
        isPlaying = false;
        if (btnPlay != null) btnPlay.setImageResource(android.R.drawable.ic_media_play);
        handler.removeCallbacks(playRunnable);
    }

    private void playStoneSound() {
        if (soundEnabled && soundReady && soundPool != null && soundId != -1)
            soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
    }

    // ══════════════════════════════════════════════════
    //  메뉴
    // ══════════════════════════════════════════════════

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_viewer, menu);
        MenuItem soundItem = menu.findItem(R.id.action_sound);
        if (soundItem != null) soundItem.setChecked(soundEnabled);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.action_select_section) {
            showSectionDialog();
        } else if (id == R.id.action_show_numbers) {
            boolean v = !item.isChecked(); item.setChecked(v);
            boardView.setShowNumbers(v); boardView.invalidate();
        } else if (id == R.id.action_show_coords) {
            boolean v = !item.isChecked(); item.setChecked(v);
            boardView.setShowCoords(v); boardView.invalidate();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_COORDS, v).apply();
        } else if (id == R.id.action_sound) {
            soundEnabled = !item.isChecked();
            item.setChecked(soundEnabled);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SOUND, soundEnabled).apply();
            Toast.makeText(this, "착수음 " + (soundEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_show_stone_comment) {
            showStoneComment = !item.isChecked();
            item.setChecked(showStoneComment);
            Toast.makeText(this, "돌 설명 표시 " + (showStoneComment ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_game_info) {
            showGameInfoDialog();
        } else if (id == R.id.action_open_editor) {
            openInEditor();
        }
        return true;
    }

    // 게임 정보 다이얼로그 (읽기 전용)

    private void showGameInfoDialog() {
        if (sections.isEmpty()) {
            Toast.makeText(this, "기보 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        BdkSection base = sections.get(0);

        StringBuilder sb = new StringBuilder();
        appendInfo(sb, "흐 기사", base.playerBlack, base.rankBlack);
        appendInfo(sb, "백 기사", base.playerWhite, base.rankWhite);
        appendInfoLine(sb, "덤", base.komi);
        appendInfoLine(sb, "결과", base.result);
        appendInfoLine(sb, "날짜", base.date);
        appendInfoLine(sb, "대회", base.event);
        appendInfoLine(sb, "장소", base.place);
        appendInfoLine(sb, "라운드", base.round);

        String msg = sb.toString().trim();
        if (msg.isEmpty()) msg = "게임 정보가 없습니다.";

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("게임 정보")
            .setMessage(msg)
            .setPositiveButton("확인", null)
            .show();
    }

    private void appendInfo(StringBuilder sb, String label, String name, String rank) {
        if ((name == null || name.trim().isEmpty()) && (rank == null || rank.trim().isEmpty())) return;
        sb.append(label).append(": ");
        if (name != null && !name.trim().isEmpty()) sb.append(name.trim());
        if (rank != null && !rank.trim().isEmpty()) sb.append(" (").append(rank.trim()).append(")");
        sb.append("\n");
    }

    private void appendInfoLine(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append(label).append(": ").append(value.trim()).append("\n");
    }

    private void openInEditor() {
        Intent intent = new Intent(this, EditorActivity.class);
        if      (currentFilePath != null) intent.putExtra(EditorActivity.EXTRA_FILE_PATH, currentFilePath);
        else if (currentFileUri  != null) intent.putExtra(EditorActivity.EXTRA_FILE_URI,  currentFileUri);
        else { Toast.makeText(this, "편집할 파일 정보가 없습니다.", Toast.LENGTH_SHORT).show(); return; }
        startActivity(intent);
    }

    private void showSectionDialog() {
        if (sections.isEmpty()) { Toast.makeText(this, "섹션이 없습니다.", Toast.LENGTH_SHORT).show(); return; }
        BottomSheetDialog bs = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_sections, null);
        ListView lv = sheetView.findViewById(R.id.lvSections);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++)
            names.add((i + 1) + ". " + sections.get(i).name);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((p, v, which, id) -> { switchSection(which); bs.dismiss(); });
        bs.setContentView(sheetView);
        bs.setOnShowListener(d -> {
            View parent = bs.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (parent != null) {
                BottomSheetBehavior<View> beh = BottomSheetBehavior.from(parent);
                beh.setState(BottomSheetBehavior.STATE_EXPANDED);
                beh.setSkipCollapsed(true);
            }
        });
        bs.show();
    }

    // ══════════════════════════════════════════════════
    //  Edge-to-Edge WindowInsets
    // ══════════════════════════════════════════════════

    private void applyWindowInsets(AppBarLayout appBar, View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (appBar != null) appBar.setPadding(0, sys.top, 0, 0);
            v.setPadding(sys.left, 0, sys.right, 0);
            View contentRow   = v.getRootView().findViewById(R.id.contentRow);
            View navBarSpacer = v.getRootView().findViewById(R.id.navBarSpacer);
            if (contentRow != null) {
                contentRow.setPadding(0, 0, 0, sys.bottom);
            } else if (navBarSpacer != null) {
                android.view.ViewGroup.LayoutParams lp = navBarSpacer.getLayoutParams();
                lp.height = sys.bottom;
                navBarSpacer.setLayoutParams(lp);
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
