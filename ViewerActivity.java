package com.bdk.studio;

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
 * board[y][x]: 0=빈, 1=흑, 2=백 (1-based 좌표)
 *
 * 놓아보기 모드 (기본도에서만 활성화):
 *   단일 탭 → 착수, 더블 탭 → 되돌리기
 *   btnToggleColor → 다음 착수 색상 수동 전환
 */
public class ViewerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_URI  = "file_uri";

    // ── UI ───────────────────────────────────────────
    private BoardView   boardView;
    private TextView    tvInfo;
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
    private Runnable playRunnable; // self-reference 방지: onCreate에서 초기화

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

    // ── 돌 설명 표시 ──────────────────────────────────────
    private boolean showStoneComment = false;

    // ── 놓아보기 모드 ────────────────────────────────
    private boolean           tryMoveMode     = false;
    private final List<int[]> tryMoves        = new ArrayList<>();
    private final List<int[]> tryMovesHistory = new ArrayList<>();
    private int nextTryColor = BdkSection.BLACK;

    // ── 바둑 규칙 헬퍼 ────────────────────
    private GoRules goRules;
    // 패 금지 좌표 (-1이면 없음)
    private int tryKoX = -1, tryKoY = -1;

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
        if (!tryMoves.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int[] m : tryMoves) {
                if (sb.length() > 0) sb.append('|');
                sb.append(m[0]).append(',').append(m[1]).append(',').append(m[2]);
            }
            out.putString(KEY_TRY_MOVES, sb.toString());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 스마트폰(sw < 600dp)에서는 세로 방향 고정
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        // playRunnable: 필드 초기화에서 self-reference 방지를 위해 여기서 생성
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
        tvInfo          = findViewById(R.id.tvMoveInfo);
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

        btnFirst.setOnClickListener(v -> { stopPlay(); goToStep(0); });
        btnPrev .setOnClickListener(v -> { stopPlay(); goToStep(currentStep - 1); });
        btnNext .setOnClickListener(v -> { stopPlay(); goToStep(currentStep + 1); });
        btnLast .setOnClickListener(v -> {
            stopPlay();
            if (tryMoveMode) goToStep(tryMoves.size() + tryMovesHistory.size());
            else { BdkSection s = currentSection(); if (s != null) goToStep(s.moves.size()); }
        });
        btnPlay.setOnClickListener(v -> { if (isPlaying) stopPlay(); else startPlay(); });

        btnToggleColor.setOnClickListener(v -> {
            nextTryColor = (nextTryColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            updateToggleColorButton();
            updateTryInfo();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) { stopPlay(); goToStep(progress); }
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
        if (tryMoveMode) { setTryModeUI(true); updateTryInfo(); }
        else             { setTryModeUI(false); }
    }

    // ══════════════════════════════════════════════════
    //  파일 로드
    // ══════════════════════════════════════════════════

    private void loadFromPath(String path, int secIdx, int step, boolean tryMode, int nextColor, String movesStr) {
        new Thread(() -> {
            try {
                List<BdkSection> result = BdkParser.parse(new File(path));
                runOnUiThread(() -> onSectionsLoaded(result, new File(path).getName(),
                        secIdx, step, tryMode, nextColor, movesStr));
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show(); finish(); });
            }
        }).start();
    }

    private void loadFromUri(Uri uri, int secIdx, int step, boolean tryMode, int nextColor, String movesStr) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception("파일을 열 수 없습니다.");
                File tmp = new File(getCacheDir(), "tmp.bdk");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                is.close();
                List<BdkSection> result = BdkParser.parse(tmp);
                // URI 마지막 세그먼트에서 파일명만 추출 (경로 제외)
                String seg = uri.getLastPathSegment();
                String name = seg != null ? new java.io.File(seg).getName() : "기보";
                runOnUiThread(() -> onSectionsLoaded(result, name,
                        secIdx, step, tryMode, nextColor, movesStr));
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show(); finish(); });
            }
        }).start();
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
            tryMoves.clear();
            if (rMovesStr != null && !rMovesStr.isEmpty()) {
                for (String token : rMovesStr.split("\\|")) {
                    String[] p = token.split(",");
                    if (p.length == 3) {
                        int c = Integer.parseInt(p[0]), x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                        tryMoves.add(new int[]{c, x, y});
                        board[y][x] = c;
                    }
                }
            }
            setTryModeUI(true);
            boardView.setBoard(copyBoard());
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
        board     = new int[boardSize + 1][boardSize + 1];
        moveNum   = new int[boardSize + 1][boardSize + 1];
        boardView.setBoardSize(boardSize);
        currentStep  = 0;
        tryMoveMode  = false;
        tryMoves.clear();
        tryMovesHistory.clear();
        goRules  = new GoRules(boardSize);
        tryKoX   = -1;
        tryKoY   = -1;
        // 파일에 저장된 zoom 영역 적용 (없으면 전체 보기)
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

    // ══════════════════════════════════
    //  놓아보기 모드
    // ══════════════════════════════════════════════════

    private void toggleTryMoveMode() {
        if (!tryMoveMode) {
            tryMoveMode = true;
            setTryModeUI(true);
            BdkSection sec = currentSection();
            if (sec != null && !sec.initialStones.isEmpty()) {
                int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
                boardView.setTriangleMark(ls[1], ls[2]);
            }
            updateTryInfo();
        } else {
            tryMoveMode = false;
            tryMoves.clear();
            tryMovesHistory.clear();
            nextTryColor = inferNextColor(currentSection());
            setTryModeUI(false);
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
        // GoRules로 자살수/패/점유 판별
        int moveNo = tryMoves.size() + 1;
        GoRules.PlaceResult result = goRules.tryPlace(
                board, moveNum, nextTryColor, bx, by, tryKoX, tryKoY, moveNo);
        if (!result.ok) {
            String msg;
            switch (result.reason) {
                case GoRules.REASON_OCCUPIED: msg = "이미 돌이 있는 자리입니다."; break;
                case GoRules.REASON_SUICIDE:  msg = "자살수는 둘 수 없습니다."; break;
                case GoRules.REASON_KO:       msg = "패로 인해 착수할 수 없습니다."; break;
                default: msg = "착수할 수 없는 자리입니다.";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return;
        }
        placeTryMove(bx, by, result);
    }

    private void placeTryMove(int bx, int by, GoRules.PlaceResult result) {
        int color = nextTryColor;
        // 보드 상태 적용
        for (int iy = 0; iy < result.newBoard.length; iy++)
            System.arraycopy(result.newBoard[iy], 0, board[iy], 0, result.newBoard[iy].length);
        for (int iy = 0; iy < result.newMoveNum.length; iy++)
            System.arraycopy(result.newMoveNum[iy], 0, moveNum[iy], 0, result.newMoveNum[iy].length);
        // tryMoves에 따내진 돌 정보 포함 저장: [color, bx, by, capturedCount, cx0, cy0, cc0, ...]
        List<int[]> caps = result.capturedStones;
        int[] entry = new int[4 + caps.size() * 3];
        entry[0] = color; entry[1] = bx; entry[2] = by; entry[3] = caps.size();
        for (int i = 0; i < caps.size(); i++) {
            entry[4 + i * 3]     = caps.get(i)[0];
            entry[4 + i * 3 + 1] = caps.get(i)[1];
            entry[4 + i * 3 + 2] = caps.get(i)[2];
        }
        tryMoves.add(entry);
        tryMovesHistory.clear();
        currentStep = tryMoves.size();
        tryKoX = result.newKoX;
        tryKoY = result.newKoY;
        nextTryColor = opponent(color);
        updateToggleColorButton();
        if (tryMoves.size() == 1) boardView.clearTriangleMark();
        playStoneSound();
        boardView.setBoard(copyBoard());
        boardView.setMoveNumbers(copyMoveNum());
        boardView.setLastMove(bx, by);
        boardView.invalidate();
        updateTryInfo();
    }

    private void undoTryMove() {
        if (tryMoves.isEmpty()) {
            Toast.makeText(this, "되돌릴 수가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] last = tryMoves.remove(tryMoves.size() - 1);
        tryMovesHistory.add(last);
        currentStep  = tryMoves.size();
        nextTryColor = last[0];
        rebuildBoardWithTryMoves();
        updateToggleColorButton();
        if (!tryMoves.isEmpty()) {
            int[] prev = tryMoves.get(tryMoves.size() - 1);
            boardView.setLastMove(prev[1], prev[2]);
        } else {
            boardView.clearLastMove();
            BdkSection sec = currentSection();
            if (sec != null && !sec.initialStones.isEmpty()) {
                int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
                boardView.setTriangleMark(ls[1], ls[2]);
            }
        }
        boardView.setBoard(copyBoard());
        boardView.setMoveNumbers(copyMoveNum());
        boardView.invalidate();
        updateTryInfo();
        Toast.makeText(this, "한 수 되돌렸습니다.", Toast.LENGTH_SHORT).show();
    }

    private void rebuildBoardWithTryMoves() {
        BdkSection sec = currentSection();
        if (sec == null) return;
        for (int y = 0; y <= boardSize; y++) {
            java.util.Arrays.fill(board[y],   BdkSection.EMPTY);
            java.util.Arrays.fill(moveNum[y], 0);
        }
        for (int[] s : sec.initialStones) {
            if (s[1] >= 1 && s[1] <= boardSize && s[2] >= 1 && s[2] <= boardSize)
                board[s[2]][s[1]] = s[0];
        }
        tryKoX = -1; tryKoY = -1;
        for (int i = 0; i < tryMoves.size(); i++) {
            int[] m = tryMoves.get(i);
            // GoRules.applyMove로 따내기 적용
            List<int[]> caps = goRules.applyMove(board, moveNum, m[0], m[1], m[2], i + 1);
            // 패 갱신
            if (caps.size() == 1) {
                tryKoX = caps.get(0)[0];
                tryKoY = caps.get(0)[1];
            } else {
                tryKoX = -1; tryKoY = -1;
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  수 이동
    // ══════════════════════════════════════════════════

    private void goToStep(int step) {
        BdkSection sec = currentSection();
        if (sec == null) return;

        if (tryMoveMode) {
            int total  = tryMoves.size() + tryMovesHistory.size();
            int target = Math.max(0, Math.min(step, total));
            while (tryMoves.size() < target && !tryMovesHistory.isEmpty())
                tryMoves.add(tryMovesHistory.remove(tryMovesHistory.size() - 1));
            while (tryMoves.size() > target)
                tryMovesHistory.add(tryMoves.remove(tryMoves.size() - 1));
            nextTryColor = tryMoves.isEmpty() ? inferNextColor(sec)
                    : opponent(tryMoves.get(tryMoves.size() - 1)[0]);
            rebuildBoardWithTryMoves();
            currentStep = target;
            boardView.setBoard(copyBoard());
            boardView.setMoveNumbers(copyMoveNum());
            if (!tryMoves.isEmpty()) {
                int[] last = tryMoves.get(tryMoves.size() - 1);
                boardView.setLastMove(last[1], last[2]);
                boardView.clearTriangleMark();
            } else {
                boardView.clearLastMove();
                if (!sec.initialStones.isEmpty()) {
                    int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
                    boardView.setTriangleMark(ls[1], ls[2]);
                }
            }
            boardView.invalidate();
            updateToggleColorButton();
            updateTryInfo();
            int totalMoves = tryMoves.size() + tryMovesHistory.size();
            seekBar.setMax(totalMoves == 0 ? 1 : totalMoves);
            seekBar.setProgress(target);
            return;
        }

        // 뷰어 모드
        step = Math.max(0, Math.min(step, sec.moves.size()));
        for (int y = 0; y <= boardSize; y++) java.util.Arrays.fill(board[y], BdkSection.EMPTY);
        for (int[] s : sec.initialStones) {
            if (s[1] >= 1 && s[1] <= boardSize && s[2] >= 1 && s[2] <= boardSize)
                board[s[2]][s[1]] = s[0];
        }
        for (int[] mn : moveNum) java.util.Arrays.fill(mn, 0);

        int lastX = -1, lastY = -1;
        for (int i = 0; i < step && i < sec.moves.size(); i++) {
            int[] m = sec.moves.get(i);
            int color = m[1], x = m[2], y = m[3];
            if (x >= 1 && x <= boardSize && y >= 1 && y <= boardSize) {
                goRules.applyMove(board, moveNum, color, x, y, m[0]);
                lastX = x; lastY = y;
            }
        }

        boolean advanced = (step > currentStep && step > 0);
        currentStep = step;
        boardView.setBoard(copyBoard());
        boardView.setMoveNumbers(copyMoveNum());
        if (lastX > 0) boardView.setLastMove(lastX, lastY);
        else           boardView.clearLastMove();
        if (advanced) playStoneSound();

        if (step == 0 && !sec.initialStones.isEmpty()) {
            int[] ls = sec.initialStones.get(sec.initialStones.size() - 1);
            boardView.setTriangleMark(ls[1], ls[2]);
        } else {
            boardView.clearTriangleMark();
        }
        boardView.invalidate();

        int total = sec.moves.size();
        if (tvInfo != null) {
            if (step == 0) tvInfo.setText("기본도  (전체 " + total + "수)");
            else {
                int[] last = sec.moves.get(step - 1);
                tvInfo.setText(step + "수: " + colorName(last[1]) + "  /  " + total + "수");
            }
        }
        seekBar.setMax(total);
        seekBar.setProgress(step);

        if (tvComment != null) {
            String cmt = sec.comment;
            if (cmt != null && !cmt.isEmpty()) {
                tvComment.setText(cmt);
                tvComment.setVisibility(View.VISIBLE);
                // 터치 시 전체 주석 팝업 표시
                final String fullComment = cmt;
                tvComment.setOnClickListener(v -> showCommentDialog(fullComment));
            } else {
                tvComment.setVisibility(View.GONE);
                tvComment.setOnClickListener(null);
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  주석 팝업
    // ══════════════════════════════════════════════════

    /**
     * 돌 설명 표시 ON 상태에서 특정 좌표의 돌을 탭 시 tvComment에 해당 돌 설명 표시.
     * 설명이 없는 돌 탭 시에는 tvComment를 변경하지 않음.
     */
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

    /** 주석 전체 내용을 스크롤 가능한 팝업으로 표시 */
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

    private void captureOpponents(int color, int bx, int by) {
        int opp = opponent(color);
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (int[] d : dirs) {
            int nx = bx + d[0], ny = by + d[1];
            if (inBounds(nx, ny) && board[ny][nx] == opp) {
                List<int[]> group = getGroup(nx, ny);
                if (liberties(group) == 0)
                    for (int[] s : group) { board[s[1]][s[0]] = BdkSection.EMPTY; moveNum[s[1]][s[0]] = 0; }
            }
        }
    }

    private List<int[]> getGroup(int x, int y) {
        List<int[]> group = new ArrayList<>();
        boolean[][] visited = new boolean[boardSize + 1][boardSize + 1];
        int color = board[y][x];
        if (color != BdkSection.EMPTY) fill(x, y, color, group, visited);
        return group;
    }

    private void fill(int x, int y, int color, List<int[]> g, boolean[][] v) {
        if (!inBounds(x, y) || v[y][x] || board[y][x] != color) return;
        v[y][x] = true;
        g.add(new int[]{x, y});
        fill(x+1,y,color,g,v); fill(x-1,y,color,g,v);
        fill(x,y+1,color,g,v); fill(x,y-1,color,g,v);
    }

    private int liberties(List<int[]> group) {
        boolean[][] counted = new boolean[boardSize + 1][boardSize + 1];
        int libs = 0;
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (int[] s : group)
            for (int[] d : dirs) {
                int nx = s[0]+d[0], ny = s[1]+d[1];
                if (inBounds(nx, ny) && !counted[ny][nx] && board[ny][nx] == BdkSection.EMPTY) {
                    counted[ny][nx] = true; libs++;
                }
            }
        return libs;
    }

    private boolean inBounds(int x, int y) { return x >= 1 && x <= boardSize && y >= 1 && y <= boardSize; }
    private int opponent(int color) { return (color == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK; }
    private String colorName(int color) { return (color == BdkSection.BLACK) ? "흑" : "백"; }

    /**
     * 다음 착수색 결정: 마지막 기본도 돌 번호(seq)의 홀짝으로 판단.
     * 홀수(흑) → 다음=백, 짝수(백) → 다음=흑, 돌 없으면 흑 시작.
     */
    private int inferNextColor(BdkSection sec) {
        if (sec == null || sec.initialStones.isEmpty()) return BdkSection.BLACK;
        int maxSeq = -1;
        for (int[] stone : sec.initialStones)
            if (stone[3] > maxSeq) maxSeq = stone[3];
        return (maxSeq < 0 || maxSeq % 2 == 0) ? BdkSection.BLACK : BdkSection.WHITE;
    }

    // ══════════════════════════════════════════════════
    //  보드 복사
    // ══════════════════════════════════════════════════

    private int[][] copyBoard() {
        int[][] copy = new int[boardSize + 1][boardSize + 1];
        for (int y = 0; y <= boardSize; y++) System.arraycopy(board[y], 0, copy[y], 0, boardSize + 1);
        return copy;
    }

    private int[][] copyMoveNum() {
        int[][] copy = new int[boardSize + 1][boardSize + 1];
        for (int y = 0; y <= boardSize; y++) System.arraycopy(moveNum[y], 0, copy[y], 0, boardSize + 1);
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
        int moveNo = tryMoves.size();
        tvInfo.setBackgroundColor(isBlack ? 0xFF424242 : 0xFFEEEEEE);
        tvInfo.setTextColor(isBlack ? 0xFFFFFFFF : 0xFF222222);
        String next = colorName(nextTryColor);
        String dot  = isBlack ? " ●" : " ○";
        if (moveNo == 0) tvInfo.setText("놓아보기  다음: " + next + dot + "  (더블탭=되돌리기)");
        else             tvInfo.setText(moveNo + "수: " + colorName(tryMoves.get(moveNo - 1)[0]) + "  다음: " + next + dot);
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
        } else if (id == R.id.action_open_editor) {
            openInEditor();
        }
        return true;
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

    /**
     * 시스템 바 인셋 적용:
     *   상단(sys.top)      → AppBarLayout 패딩 (상태표시줄 영역)
     *   좌우(sys.left/right) → rootView 패딩
     *   하단(sys.bottom)   → landscape: contentRow 패딩
     *                        portrait:  navBarSpacer 높이 (board_color 배경)
     */
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
