package com.badol.studio;

import androidx.appcompat.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * BDK 기보 편집기
 *
 * ── 핵심 설계 원칙 ──────────────────────────────────────────────────────────
 *
 * [착수 모드 - 정해도/변화도]
 *   moveHistory = List<int[3]>  각 항목: [color, x, y]
 *   착수 번호(moveNo) = 인덱스 + 1  (1번째=1, 2번째=2, ...)
 *   1번=흑(홀수), 2번=백(짝수) 자동 성립
 *   따내기가 발생해도 moveHistory는 절대 변경되지 않는다.
 *   undo = moveHistory 마지막 항목 제거 후 rebuildState() 재계산
 *   화면 표시(moveNumbers)는 항상 rebuildState()로 재계산한다.
 *
 * [기본도 배치 모드]
 *   basePlaceHistory = List<int[3]>  각 항목: [color, x, y]
 *   착수 번호 = 인덱스 + 1
 *   undo = basePlaceHistory 마지막 항목 제거 후 rebuildBaseState() 재계산
 *   저장 시 basePlaceHistory → initialStones(seq=인덱스)로 변환
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "editor_file_path";
    public static final String EXTRA_FILE_URI  = "editor_file_uri";

    // ── 상태 ──────────────────────────────────────────
    private List<BdkSection> sections = new ArrayList<>();
    private int currentSectionIdx = -1;
    private boolean isBaseMode = true;
    private int nextColor = BdkSection.BLACK;
    private String loadedFilePath = null;
    private Uri    loadedUri      = null;

    // ── UI ────────────────────────────────────────────
    private BoardView boardView;
    private TextView  tvSectionName;
    private TextView  tvModeInfo;
    private TextView  tvNextColor;
    private Button    btnToggleMode;
    private Button    btnToggleColor;
    private Button    btnUndo;
    private Button    btnAddSection;
    private Button    btnDelSection;
    private ListView  lvSections;
    private android.widget.ImageButton btnFirst;
    private android.widget.ImageButton btnPrev;
    private android.widget.ImageButton btnNext;
    private android.widget.ImageButton btnLast;
    private android.widget.SeekBar seekBar;
    private SectionAdapter sectionAdapter;

    // ── 마커 도구 팔레트 ──────────────────────────────
    private static final int TOOL_NONE     = -1; // 선택 없음 (착수 모드)
    private static final int TOOL_TRIANGLE = 1;
    private static final int TOOL_CIRCLE   = 2;
    private static final int TOOL_SQUARE   = 3;
    private static final int TOOL_X        = 4;
    private static final int TOOL_LABEL    = 5;
    private static final int TOOL_ERASE    = 6;
    private int currentTool = TOOL_NONE; // 기본: 마커 미선택 (착수 모드)

    private android.view.View markerToolbar;
    private Button btnToolTriangle;
    private Button btnToolCircle;
    private Button btnToolSquare;
    private Button btnToolX;
    private Button btnToolLabel;
    private Button btnToolEraseMarker;

    // ── 전체화면 모드 ──────────────────────────────────
    private android.widget.ImageButton btnFullscreen;
    private boolean isFullscreen = false;

    // ── 바둑판 상태 ───────────────────────────────────
    private int[][] boardState;   // [y][x] 0=빈, 1=흑, 2=백
    private int[][] moveNumbers;  // [y][x] 표시 번호 (rebuildState로 재계산)

    // ── 기본도 배치 이력 (배치 모드 전용) ─────────────
    // 각 항목: [color, x, y]
    // 인덱스+1 = 착수 번호, undo = 마지막 제거 후 rebuildBaseState()
    private final List<int[]> basePlaceHistory = new ArrayList<>();

    // ── 착수 이력 (착수 모드 전용) ────────────────────
    // 각 항목: [color, x, y]
    // 인덱스+1 = 착수 번호, undo = 마지막 제거 후 rebuildState()
    private final List<int[]> moveHistory = new ArrayList<>();
    private int currentMoveStep = 0;

    // ── 패 좌표 ───────────────────────────────────────
    private int koX = -1, koY = -1;

    // ── 바둑 규칙 헬퍼 ────────────────────────────────
    private GoRules goRules;

    // ── 설정 ──────────────────────────────────────────
    private static final String PREFS_NAME  = "viewer_prefs";
    private static final String PREF_SOUND  = "sound_enabled";
    private static final String PREF_COORDS = "show_coords";
    private boolean soundEnabled = true;
    private SoundPool soundPool;
    private int       soundId    = -1;
    private boolean   soundReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        setContentView(R.layout.activity_editor);
        initWindow("기보 편집기");

        bindViews();
        setupBoardView();
        setupButtons();
        setupSectionList();
        initSoundAndPrefs();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { confirmExit(); }
        });

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String uri  = getIntent().getStringExtra(EXTRA_FILE_URI);

        if (path != null) {
            loadFromPath(path);
        } else if (uri != null) {
            loadFromUri(uri);
        } else {
            startNew();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 화면 회전/크기 변경 시 올바른 레이아웃 재적용 (Android가 qualifier 자동 선택)
        setContentView(R.layout.activity_editor);
        String rotTitle = (loadedFilePath != null)
                ? new java.io.File(loadedFilePath).getName()
                : (sections.isEmpty() ? "새 기보" : "기보 편집기");
        initWindow(rotTitle);

        bindViews();
        rebindBoardView();
        setupButtons();
        setupSectionList();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boardView.setShowCoords(prefs.getBoolean(PREF_COORDS, false));

        if (!sections.isEmpty() && currentSectionIdx >= 0) {
            refreshSectionList();
            updateBoardView();
            updateModeUI(); // dragPlaceMode=true 설정 포함
            updateColorUI();
            updateNavButtons();
            // 화면 회전 후 마커 복원
            if (isBaseMode && currentSectionIdx == 0 && !basePlaceHistory.isEmpty()) {
                int[] last = basePlaceHistory.get(basePlaceHistory.size() - 1);
                boardView.setTriangleMark(last[1], last[2]);
            } else if (!isBaseMode && !moveHistory.isEmpty() && currentMoveStep > 0) {
                int[] last = moveHistory.get(currentMoveStep - 1);
                boardView.setLastMove(last[1], last[2]);
            }
        }
    }

    // ── 뷰 바인딩 ─────────────────────────────────────

    private void bindViews() {
        boardView      = findViewById(R.id.boardView);
        tvSectionName  = findViewById(R.id.tvSectionName);
        tvModeInfo     = findViewById(R.id.tvModeInfo);
        tvNextColor    = findViewById(R.id.tvNextColor);
        btnToggleMode  = findViewById(R.id.btnToggleMode);
        btnToggleColor = findViewById(R.id.btnToggleColor);
        btnUndo        = findViewById(R.id.btnUndo);
        btnAddSection  = findViewById(R.id.btnAddSection);
        btnDelSection  = findViewById(R.id.btnDelSection);
        lvSections     = findViewById(R.id.lvSections);
        btnFirst       = findViewById(R.id.btnFirst);
        btnPrev        = findViewById(R.id.btnPrev);
        btnNext        = findViewById(R.id.btnNext);
        btnLast        = findViewById(R.id.btnLast);
        seekBar        = findViewById(R.id.seekBar);

        // 마커 도구 팔레트
        markerToolbar      = findViewById(R.id.markerToolbar);
        btnToolTriangle    = findViewById(R.id.btnToolTriangle);
        btnToolCircle      = findViewById(R.id.btnToolCircle);
        btnToolSquare      = findViewById(R.id.btnToolSquare);
        btnToolX           = findViewById(R.id.btnToolX);
        btnToolLabel       = findViewById(R.id.btnToolLabel);
        btnToolEraseMarker = findViewById(R.id.btnToolEraseMarker);

        // 전체화면 버튼 (태블릿 전용, 없으면 null)
        btnFullscreen = findViewById(R.id.btnFullscreen);
    }

    private void setupBoardView() {
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        goRules     = new GoRules(19);
        boardView.setTouchListener((x, y) -> onBoardTouch(x, y));
        boardView.setDoubleTapListener(() -> onNavPrev());
        boardView.setShowNumbers(true);
        boardView.setShowCoords(false);
        boardView.resetZoom();
    }

    private void rebindBoardView() {
        boardView.setTouchListener((x, y) -> onBoardTouch(x, y));
        boardView.setDoubleTapListener(() -> onNavPrev());
        boardView.setShowNumbers(true);
        boardView.setShowCoords(false);
    }

    // ── 버튼 설정 ─────────────────────────────────────

    private void setupButtons() {
        btnToggleMode.setOnClickListener(v -> {
            boolean wasBaseMode = isBaseMode;
            isBaseMode = !isBaseMode;
            if (currentSectionIdx == 0) {
                if (wasBaseMode && !isBaseMode) {
                    // 배치 모드 → 착수 모드: basePlaceHistory를 moveHistory로 이관
                    moveHistory.clear();
                    for (int[] h : basePlaceHistory) {
                        moveHistory.add(new int[]{h[0], h[1], h[2]});
                    }
                    basePlaceHistory.clear();
                    int[][] ko = rebuildMoveState(moveHistory.size());
                    koX = ko[0][0]; koY = ko[0][1];
                    currentMoveStep = moveHistory.size();
                    if (moveHistory.isEmpty()) {
                        nextColor = BdkSection.BLACK;
                    } else {
                        int[] last = moveHistory.get(moveHistory.size() - 1);
                        nextColor = (last[0] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
                    }
                } else if (!wasBaseMode && isBaseMode) {
                    // 착수 모드 → 배치 모드: moveHistory를 basePlaceHistory로 이관
                    basePlaceHistory.clear();
                    for (int[] h : moveHistory) {
                        basePlaceHistory.add(new int[]{h[0], h[1], h[2]});
                    }
                    moveHistory.clear();
                    currentMoveStep = 0;
                    rebuildBaseState();
                    if (basePlaceHistory.isEmpty()) nextColor = BdkSection.BLACK;
                    // 배치 모드로 전환 시: 마지막 돌에 삼각형 표시
                    boardView.clearLastMove();
                    if (!basePlaceHistory.isEmpty()) {
                        int[] last = basePlaceHistory.get(basePlaceHistory.size() - 1);
                        boardView.setTriangleMark(last[1], last[2]);
                    } else {
                        boardView.clearTriangleMark();
                    }
                }
                if (wasBaseMode && !isBaseMode) {
                    // 배치 모드 → 착수 모드로 전환 시: 삼각형 제거, 마지막수 표시
                    boardView.clearTriangleMark();
                    if (!moveHistory.isEmpty()) {
                        int[] last = moveHistory.get(moveHistory.size() - 1);
                        boardView.setLastMove(last[1], last[2]);
                    }
                }
                updateBoardView();
                updateNavButtons();
            } else if (isBaseMode) {
                resetToBaseLayout();
            }
            updateModeUI();
            updateColorUI();
        });

        btnToggleColor.setOnClickListener(v -> {
            nextColor = (nextColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            updateColorUI();
        });

        btnUndo.setOnClickListener(v -> undo());
        btnAddSection.setOnClickListener(v -> addSection());
        btnDelSection.setOnClickListener(v -> deleteCurrentSection());

        if (btnFirst != null) btnFirst.setOnClickListener(v -> onNavFirst());
        if (btnPrev  != null) btnPrev.setOnClickListener(v -> onNavPrev());
        if (btnNext  != null) btnNext.setOnClickListener(v -> onNavNext());
        if (btnLast  != null) btnLast.setOnClickListener(v -> onNavLast());

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    if (!isBaseMode) {
                        // 착수 모드: 섹션에 무관하게 moveHistory 기준
                        goToMoveStep(progress);
                    } else if (currentSectionIdx == 0) {
                        // 배치 모드 기본도: basePlaceHistory 기준
                        int target  = progress;
                        int current = basePlaceHistory.size();
                        if (target < current) {
                            int remove = current - target;
                            for (int i = 0; i < remove; i++) {
                                if (!basePlaceHistory.isEmpty())
                                    basePlaceHistory.remove(basePlaceHistory.size() - 1);
                            }
                            rebuildBaseState();
                            updateBoardView();
                            updateNavButtons();
                        }
                    } else {
                        navigateTo(progress);
                    }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
        }

        // 마커 도구 팔레트 버튼 (토글 방식: 선택 중 다시 누르면 해제)
        if (btnToolTriangle != null) {
            btnToolTriangle.setOnClickListener(v -> selectTool(TOOL_TRIANGLE));
            btnToolCircle.setOnClickListener(v -> selectTool(TOOL_CIRCLE));
            btnToolSquare.setOnClickListener(v -> selectTool(TOOL_SQUARE));
            btnToolX.setOnClickListener(v -> selectTool(TOOL_X));
            btnToolLabel.setOnClickListener(v -> selectTool(TOOL_LABEL));
            btnToolEraseMarker.setOnClickListener(v -> selectTool(TOOL_ERASE));
        }

        // 전체화면 버튼
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        }
    }

    private void setupSectionList() {
        sectionAdapter = new SectionAdapter(new ArrayList<BdkSection>());
        lvSections.setAdapter(sectionAdapter);
        lvSections.setChoiceMode(ListView.CHOICE_MODE_NONE);
        lvSections.setOnItemClickListener((parent, view, position, id) -> switchToSection(position));
    }

    // ── 전체화면 모드 ────────────────────────────────

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        android.view.View bottomPanel = findViewById(R.id.bottomPanel);
        if (bottomPanel != null) {
            bottomPanel.setVisibility(isFullscreen ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (markerToolbar != null) {
            markerToolbar.setVisibility(isFullscreen ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        // 아이콘 변경: 전체화면 중에는 축소 아이콘, 일반 시는 확대 아이콘
        if (btnFullscreen != null) {
            btnFullscreen.setImageResource(
                isFullscreen
                    ? android.R.drawable.ic_menu_close_clear_cancel
                    : android.R.drawable.ic_menu_zoom);
        }
        // 액션바 숨김/표시
        if (getSupportActionBar() != null) {
            if (isFullscreen) getSupportActionBar().hide();
            else getSupportActionBar().show();
        }
    }

    // ── 마커 도구 선택 ───────────────────────────────

    private void selectTool(int tool) {
        // 이미 선택된 도구를 다시 누르면 해제 (착수 모드로 복굼)
        if (currentTool == tool) {
            currentTool = TOOL_NONE;
        } else {
            currentTool = tool;
        }
        updateMarkerToolUI();
    }

    /**
     * 마커 도구 팔레트 버튼 강조 표시 업데이트.
     * 선택된 도구는 primary 색, 나머지는 neutral 색으로 표시.
     */
    private void updateMarkerToolUI() {
        if (btnToolTriangle == null) return;
        int primary = getResources().getColor(R.color.primary, null);
        int neutral = getResources().getColor(R.color.btn_neutral, null);
        int del     = getResources().getColor(R.color.btn_del, null);

        btnToolTriangle.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(currentTool == TOOL_TRIANGLE ? primary : neutral));
        btnToolCircle.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(currentTool == TOOL_CIRCLE ? primary : neutral));
        btnToolSquare.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(currentTool == TOOL_SQUARE ? primary : neutral));
        btnToolX.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(currentTool == TOOL_X ? primary : neutral));
        btnToolLabel.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(currentTool == TOOL_LABEL ? primary : neutral));
        btnToolEraseMarker.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(currentTool == TOOL_ERASE ? del : neutral));
    }

    // ── 신규 시작 ─────────────────────────────────────

    private void startNew() {
        sections.clear();
        BdkSection base = new BdkSection();
        base.name = "기본도";
        sections.add(base);
        loadedFilePath = null;
        loadedUri = null;
        isBaseMode = false; // 새기보는 착수 입력 모드로 시작
        switchToSection(0);
        refreshSectionList();
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("새 기보");
    }

    // ── 파일 불러오기 ─────────────────────────────────

    private void loadFromPath(String path) {
        try {
            File f = new File(path);
            List<BdkSection> parsed = parseFile(f, null);
            if (parsed.isEmpty()) { startNew(); return; }
            sections = parsed;
            loadedFilePath = path;
            // SGF로 저장하므로 확장자를 .sgf로 변경
            if (path.toLowerCase().endsWith(".bdk")) {
                loadedFilePath = null; // BDK 파일은 다른 이름으로 저장
            }
            // 파일 불러올 때: 섹션이 1개(기본도만)이면 착수 모드, 여러 섹션이면 기본도 배치 모드
            isBaseMode = (parsed.size() > 1);
            switchToSection(0);
            refreshSectionList();
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(f.getName());
        } catch (Exception e) {
            Toast.makeText(this, "파일 불러오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            startNew();
        }
    }

    private void loadFromUri(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            String seg = uri.getLastPathSegment();
            String uriFileName = seg != null ? new java.io.File(seg).getName() : "편집 중";
            boolean isBdk = uriFileName.toLowerCase().endsWith(".bdk");
            File tmp = new File(getCacheDir(), isBdk ? "editor_tmp.bdk" : "editor_tmp.sgf");
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (is == null) throw new IOException("입력 스트림을 열 수 없습니다");
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            }
            List<BdkSection> parsed = parseFile(tmp, uriFileName);
            if (parsed.isEmpty()) { startNew(); return; }
            sections = parsed;
            loadedFilePath = null;
            // BDK 파일은 URI 저장 불가(읽기 전용), SGF는 URI 덮어쓰기 허용
            loadedUri = isBdk ? null : uri;
            isBaseMode = (parsed.size() > 1);
            switchToSection(0);
            refreshSectionList();
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(uriFileName);
        } catch (Exception e) {
            Toast.makeText(this, "파일 불러오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            startNew();
        }
    }

    /**
     * 파일 확장자에 따라 SGF 또는 BDK 파서로 파싱
     * @param file     파싱할 파일
     * @param fileName 파일명 (확장자 판단용, null이면 file.getName() 사용)
     */
    private List<BdkSection> parseFile(File file, String fileName) throws Exception {
        String name = (fileName != null) ? fileName : file.getName();
        if (name.toLowerCase().endsWith(".bdk")) {
            return BdkParser.parse(file);
        } else {
            // SGF 또는 기타 텍스트 포맷
            return SgfParser.parse(file);
        }
    }

    // ── 기보 전환 ─────────────────────────────────────

    private void onNavFirst() {
        if (!isBaseMode) {
            // 착수 모드: 섹션에 무관하게 첫 수순으로
            goToMoveStep(0);
        } else if (currentSectionIdx == 0) {
            basePlaceHistory.clear();
            rebuildBaseState();
            updateBoardView();
            updateNavButtons();
        } else {
            navigateTo(0);
        }
    }

    private void onNavPrev() {
        if (!isBaseMode) {
            // 착수 모드: 한 수 이전으로
            if (currentMoveStep > 0) goToMoveStep(currentMoveStep - 1);
        } else if (currentSectionIdx == 0) {
            undo();
        } else {
            navigateTo(currentSectionIdx - 1);
        }
    }

    private void onNavNext() {
        if (!isBaseMode) {
            // 착수 모드: 한 수 다음으로
            if (currentMoveStep < moveHistory.size()) goToMoveStep(currentMoveStep + 1);
        } else if (currentSectionIdx == 0) {
            // 배치 모드 기본도: redo 불가
        } else {
            navigateTo(currentSectionIdx + 1);
        }
    }

    private void onNavLast() {
        if (!isBaseMode) {
            // 착수 모드: 마지막 수순으로
            goToMoveStep(moveHistory.size());
        } else if (currentSectionIdx == 0) {
            // 배치 모드 기본도: 이미 마지막
        } else {
            navigateTo(sections.size() - 1);
        }
    }

    /**
     * 착수 모드에서 특정 수순으로 이동.
     * moveHistory를 기반으로 기본도에서 완전 재계산.
     */
    private void goToMoveStep(int targetStep) {
        if (isBaseMode) return;
        int total = moveHistory.size();
        targetStep = Math.max(0, Math.min(targetStep, total));
        if (targetStep == currentMoveStep) return;

        int[][] ko = rebuildMoveState(targetStep);
        koX = ko[0][0]; koY = ko[0][1];
        currentMoveStep = targetStep;

        // 다음 색: moveHistory 기반 (따내기와 무관하게 항상 정확)
        if (targetStep == 0) {
            nextColor = (moveHistory.isEmpty()) ? BdkSection.BLACK : moveHistory.get(0)[0];
        } else {
            int[] last = moveHistory.get(targetStep - 1);
            nextColor = (last[0] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
        }

        if (targetStep > 0) {
            int[] last = moveHistory.get(targetStep - 1);
            boardView.setLastMove(last[1], last[2]);
        } else {
            boardView.clearLastMove();
        }

        updateColorUI();
        updateBoardView();
        updateNavButtons();
    }

    /**
     * moveHistory[0..targetStep-1]을 기본도에서 재계산하여
     * boardState, moveNumbers를 갱신한다.
     * @return [[koX, koY]]
     */
    private int[][] rebuildMoveState(int targetStep) {
        BdkSection base = sections.get(0);
        int[][] baseBoard = new int[21][21];
        for (int[] stone : base.initialStones)
            baseBoard[stone[2]][stone[1]] = stone[0];

        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        int[] ko = goRules.rebuildState(baseBoard, moveHistory, targetStep, boardState, moveNumbers);
        return new int[][]{ko};
    }

    /**
     * basePlaceHistory를 기반으로 기본도 보드 상태를 재계산한다.
     * 착수 번호 = 인덱스 + 1 (따내기 후에도 항상 연속)
     */
    private void rebuildBaseState() {
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        koX = -1; koY = -1;

        for (int i = 0; i < basePlaceHistory.size(); i++) {
            int[] h = basePlaceHistory.get(i);
            int color = h[0], x = h[1], y = h[2];
            int moveNo = i + 1;

            boardState[y][x]   = color;
            moveNumbers[y][x]  = moveNo;

            // 따내기
            int opp = (color == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
            List<int[]> captured = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1];
                if (goRules.inBounds(nx, ny) && boardState[ny][nx] == opp) {
                    List<int[]> group = goRules.getGroup(boardState, nx, ny);
                    if (goRules.liberties(boardState, group) == 0) {
                        for (int[] s : group) {
                            captured.add(new int[]{s[0], s[1]});
                            boardState[s[1]][s[0]]   = BdkSection.EMPTY;
                            moveNumbers[s[1]][s[0]]  = 0;
                        }
                    }
                }
            }

            // 패 갱신
            if (captured.size() == 1 && goRules.getGroup(boardState, x, y).size() == 1) {
                koX = captured.get(0)[0];
                koY = captured.get(0)[1];
            } else {
                koX = -1; koY = -1;
            }
        }

        // 배치 모드: nextColor는 사용자가 선택한 색 고정 (자동 교체 없음)
    }

    private void navigateTo(int idx) {
        if (sections.isEmpty()) return;
        int target = Math.max(0, Math.min(idx, sections.size() - 1));
        if (target == currentSectionIdx) return;
        switchToSection(target);
    }

    private void switchToSection(int idx) {
        if (idx < 0 || idx >= sections.size()) return;

        // 항상 현재 섹션 상태를 저장 (기본도 포함)
        // currentSectionIdx < 0 이면 saveCurrentSectionState() 내부에서 return 처리됨
        saveCurrentSectionState();

        currentSectionIdx = idx;
        BdkSection sec = sections.get(idx);

        // 섹션0으로 전환 시 모드는 유지 (사용자가 선택한 모드 그대로)
        // 단, 처음 로드 시에는 startNew/loadFromPath에서 설정

        // 상태 초기화
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        koX = -1; koY = -1;
        basePlaceHistory.clear();
        moveHistory.clear();
        currentMoveStep = 0;

        if (idx == 0) {
            // 기본도: initialStones → basePlaceHistory 복원
            // seq 순서대로 정렬하여 basePlaceHistory 재구성
            List<int[]> sorted = new ArrayList<>(sec.initialStones);
            sorted.sort((a, b) -> Integer.compare(a[3], b[3]));
            for (int[] stone : sorted) {
                basePlaceHistory.add(new int[]{stone[0], stone[1], stone[2]});
            }
            if (isBaseMode) {
                // 배치 모드: basePlaceHistory 기반으로 보드 재계산
                rebuildBaseState();
                if (basePlaceHistory.isEmpty()) nextColor = BdkSection.BLACK;
            } else {
                // 착수 모드: basePlaceHistory를 moveHistory로 복원하여 재계산
                for (int[] h : basePlaceHistory) {
                    moveHistory.add(new int[]{h[0], h[1], h[2]});
                }
                basePlaceHistory.clear();
                int[][] ko = rebuildMoveState(moveHistory.size());
                koX = ko[0][0]; koY = ko[0][1];
                currentMoveStep = moveHistory.size();
                if (moveHistory.isEmpty()) {
                    nextColor = BdkSection.BLACK;
                } else {
                    int[] last = moveHistory.get(moveHistory.size() - 1);
                    nextColor = (last[0] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
                }
            }
        } else {
            // 정해도/변화도: 기본도 배치 + 착수 이력 복원
            isBaseMode = false;
            BdkSection base = sections.get(0);
            // moveHistory 복원: sec.moves → [color, x, y]
            for (int[] move : sec.moves) {
                moveHistory.add(new int[]{move[1], move[2], move[3]});
            }
            // 전체 수순 재계산
            int[][] ko = rebuildMoveState(moveHistory.size());
            koX = ko[0][0]; koY = ko[0][1];
            currentMoveStep = moveHistory.size();
            // 다음 색
            if (moveHistory.isEmpty()) {
                // 기본도 돌 수 기반
                nextColor = guessNextColorFromBase(base.initialStones);
            } else {
                int[] last = moveHistory.get(moveHistory.size() - 1);
                nextColor = (last[0] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            }
        }

        if (sectionAdapter != null) {
            sectionAdapter.setSelectedIndex(idx);
            sectionAdapter.notifyDataSetChanged();
        }
        lvSections.setSelection(idx);
        // 섹션 전환 시 삼각형/마지막수 표시 초기화
        boardView.clearLastMove();
        boardView.clearTriangleMark();
        // 배치 모드에서 섹션 복원 시 마지막 돌 삼각형 표시
        if (isBaseMode && idx == 0 && !basePlaceHistory.isEmpty()) {
            int[] last = basePlaceHistory.get(basePlaceHistory.size() - 1);
            boardView.setTriangleMark(last[1], last[2]);
        }
        updateBoardView();
        updateModeUI();
        updateColorUI();
        tvSectionName.setText(sec.name);
        updateNavButtons();
        if (sec.hasZoom()) {
            boardView.setZoomRegion(sec.zoomX1, sec.zoomY1, sec.zoomX2, sec.zoomY2);
        } else {
            boardView.resetZoom();
        }
    }

    /**
     * 기본도 initialStones의 최대 seq 기반으로 다음 착수색 추정.
     * seq는 0-based: 0=흑1번, 1=백2번, ...
     * 마지막 seq가 짝수(흑)이면 다음=백, 홀수(백)이면 다음=흑.
     * 돌이 없으면 흑 시작.
     */
    private int guessNextColorFromBase(List<int[]> initialStones) {
        if (initialStones == null || initialStones.isEmpty()) return BdkSection.BLACK;
        int maxSeq = -1;
        for (int[] s : initialStones)
            if (s[3] > maxSeq) maxSeq = s[3];
        // seq 짝수=흑이 마지막 → 다음=백, 홀수=백이 마지막 → 다음=흑
        return (maxSeq % 2 == 0) ? BdkSection.WHITE : BdkSection.BLACK;
    }

    // ── 현재 기보 상태 저장 ───────────────────────────

    private void saveCurrentSectionState() {
        if (currentSectionIdx < 0 || currentSectionIdx >= sections.size()) return;
        BdkSection sec = sections.get(currentSectionIdx);

        if (boardView != null) {
            int[] z = boardView.getZoomCoords();
            sec.zoomX1 = z[0]; sec.zoomY1 = z[1];
            sec.zoomX2 = z[2]; sec.zoomY2 = z[3];
        }

        if (currentSectionIdx == 0) {
            // 기본도: 착수 이력 → initialStones
            // 배치 모드이면 basePlaceHistory, 착수 모드이면 moveHistory를 사용
            List<int[]> history = isBaseMode ? basePlaceHistory : moveHistory;

            sec.initialStones.clear();
            // 이력을 순서대로 재생하여 최종 살아있는 돌 목록 구성
            int[][] tmpBoard = new int[21][21];
            int[][] tmpNum   = new int[21][21];
            for (int i = 0; i < history.size(); i++) {
                int[] h = history.get(i);
                int color = h[0], x = h[1], y = h[2];
                tmpBoard[y][x]  = color;
                tmpNum[y][x]    = i + 1;
                // 따내기
                int opp = (color == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
                int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (goRules.inBounds(nx, ny) && tmpBoard[ny][nx] == opp) {
                        List<int[]> group = goRules.getGroup(tmpBoard, nx, ny);
                        if (goRules.liberties(tmpBoard, group) == 0) {
                            for (int[] s : group) {
                                tmpBoard[s[1]][s[0]] = BdkSection.EMPTY;
                                tmpNum[s[1]][s[0]]   = 0;
                            }
                        }
                    }
                }
            }
            // 살아있는 돌을 착수 번호 순서로 정렬하여 seq 부여
            List<int[]> alive = new ArrayList<>();
            for (int y = 1; y <= 19; y++)
                for (int x = 1; x <= 19; x++)
                    if (tmpBoard[y][x] != BdkSection.EMPTY)
                        alive.add(new int[]{tmpBoard[y][x], x, y, tmpNum[y][x]});
            alive.sort((a, b) -> Integer.compare(a[3], b[3]));
            for (int i = 0; i < alive.size(); i++) {
                int[] s = alive.get(i);
                sec.initialStones.add(new int[]{s[0], s[1], s[2], i}); // seq = 0,1,2,...
            }
        } else {
            // 정해도/변화도: moveHistory → moves
            // moves: [moveNum(1-based), color, x, y]
            sec.moves.clear();
            for (int i = 0; i < moveHistory.size(); i++) {
                int[] h = moveHistory.get(i);
                sec.moves.add(new int[]{i + 1, h[0], h[1], h[2]});
            }
        }
    }

    // ── 바둑판 터치 처리 ──────────────────────────────

    private void onBoardTouch(int x, int y) {
        if (x < 1 || x > 19 || y < 1 || y > 19) return;

        // 마커 도구 모드: 마커가 선택된 경우 우선 처리
        if (currentTool != TOOL_NONE) {
            handleMarkerTouch(x, y);
            return;
        }

        if (isBaseMode) {
            // 배치 모드: 이미 돌이 있으면 마지막 돌이면 undo, 아니면 무시
            if (boardState[y][x] != BdkSection.EMPTY) {
                if (!basePlaceHistory.isEmpty()) {
                    int[] last = basePlaceHistory.get(basePlaceHistory.size() - 1);
                    if (last[1] == x && last[2] == y) undo();
                }
                return;
            }
            // 착수 시도
            GoRules.PlaceResult result = goRules.tryPlace(boardState, nextColor, x, y, koX, koY);
            if (!result.ok) return; // 배치 모드에서는 오류 무시

            // 이력에 추가
            basePlaceHistory.add(new int[]{nextColor, x, y});

            // 보드 상태 갱신 (rebuildBaseState로 재계산)
            rebuildBaseState();

            if (soundEnabled && soundReady && soundPool != null && soundId != -1)
                soundPool.play(soundId, 1f, 1f, 0, 0, 1f);
            // 배치 모드: 마지막 돌에 삼각형 표시
            boardView.clearLastMove();
            boardView.setTriangleMark(x, y);
            updateColorUI();
            updateBoardView();
            updateNavButtons();
            return;
        }

        // 착수 모드
        if (currentMoveStep < moveHistory.size()) {
            // 중간 위치에서 착수: currentMoveStep 이후 수순 삭제 후 재착수
            int removeCount = moveHistory.size() - currentMoveStep;
            for (int i = 0; i < removeCount; i++)
                moveHistory.remove(moveHistory.size() - 1);
            // currentMoveStep은 이미 올바른 위치 (moveHistory.size())
        }

        GoRules.PlaceResult result = goRules.tryPlace(boardState, nextColor, x, y, koX, koY);
        if (!result.ok) {
            String msg;
            if (GoRules.REASON_OCCUPIED.equals(result.reason))     msg = "이미 돌이 있는 자리입니다.";
            else if (GoRules.REASON_SUICIDE.equals(result.reason)) msg = "자살수는 둘 수 없습니다.";
            else if (GoRules.REASON_KO.equals(result.reason))      msg = "패로 인해 착수할 수 없습니다.";
            else                                                    msg = "착수할 수 없는 자리입니다.";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return;
        }

        // 이력에 추가
        moveHistory.add(new int[]{nextColor, x, y});
        currentMoveStep = moveHistory.size();

        // 보드 상태 갱신 (rebuildMoveState로 재계산)
        int[][] ko = rebuildMoveState(currentMoveStep);
        koX = ko[0][0]; koY = ko[0][1];

        // 다음 색: 단순 교체
        nextColor = (nextColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;

        if (soundEnabled && soundReady && soundPool != null && soundId != -1)
            soundPool.play(soundId, 1f, 1f, 0, 0, 1f);
        boardView.setLastMove(x, y);
        updateColorUI();
        updateBoardView();
        updateNavButtons();
    }


    // ── 마커 터치 처리 ──────────────────────────────────────────────

    /**
     * 착수 입력 모드에서 마커 도구가 선택된 상태에서 바둑판 터치 처리.
     * 마커 토글 방식: 이미 있으면 제거, 없으면 추가.
     * 레이블 도구는 입력 다이얼로그 표시.
     */
    private void handleMarkerTouch(int x, int y) {
        if (currentSectionIdx < 0 || currentSectionIdx >= sections.size()) return;
        BdkSection sec = sections.get(currentSectionIdx);
        String key = x + "," + y;

        switch (currentTool) {
            case TOOL_TRIANGLE: {
                boolean removed = sec.markersTriangle.removeIf(m -> m[0] == x && m[1] == y);
                if (!removed) sec.markersTriangle.add(new int[]{x, y});
                break;
            }
            case TOOL_CIRCLE: {
                boolean removed = sec.markersCircle.removeIf(m -> m[0] == x && m[1] == y);
                if (!removed) sec.markersCircle.add(new int[]{x, y});
                break;
            }
            case TOOL_SQUARE: {
                boolean removed = sec.markersSquare.removeIf(m -> m[0] == x && m[1] == y);
                if (!removed) sec.markersSquare.add(new int[]{x, y});
                break;
            }
            case TOOL_X: {
                boolean removed = sec.markersX.removeIf(m -> m[0] == x && m[1] == y);
                if (!removed) sec.markersX.add(new int[]{x, y});
                break;
            }
            case TOOL_LABEL: {
                if (sec.markersLabel.containsKey(key)) {
                    sec.markersLabel.remove(key);
                    boardView.setMarkers(sec);
                } else {
                    EditText et = new EditText(this);
                    et.setHint("레이블 (A, B, 1, 2, ...)");
                    et.setInputType(InputType.TYPE_CLASS_TEXT);
                    et.setMaxLines(1);
                    LinearLayout layout = new LinearLayout(this);
                    layout.setPadding(48, 16, 48, 0);
                    layout.addView(et);
                    new AlertDialog.Builder(this)
                        .setTitle("레이블 입력")
                        .setView(layout)
                        .setPositiveButton("확인", (d, w) -> {
                            String label = et.getText().toString().trim();
                            if (!label.isEmpty()) {
                                sec.markersLabel.put(key, label);
                                boardView.setMarkers(sec);
                            }
                        })
                        .setNegativeButton("취소", null)
                        .show();
                }
                return;
            }
            case TOOL_ERASE: {
                sec.markersTriangle.removeIf(m -> m[0] == x && m[1] == y);
                sec.markersCircle.removeIf(m -> m[0] == x && m[1] == y);
                sec.markersSquare.removeIf(m -> m[0] == x && m[1] == y);
                sec.markersX.removeIf(m -> m[0] == x && m[1] == y);
                sec.markersLabel.remove(key);
                break;
            }
            default:
                return;
        }

        boardView.setMarkers(sec);
    }

    // ── 실행 취소 ─────────────────────────────────────

    private void undo() {
        if (isBaseMode) {
            // 배치 모드 undo
            if (basePlaceHistory.isEmpty()) {
                Toast.makeText(this, "취소할 작업이 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            basePlaceHistory.remove(basePlaceHistory.size() - 1);
            rebuildBaseState();
            boardView.clearLastMove();
            // 배치 모드: undo 후 새 마지막 돌에 삼각형 표시
            if (!basePlaceHistory.isEmpty()) {
                int[] last = basePlaceHistory.get(basePlaceHistory.size() - 1);
                boardView.setTriangleMark(last[1], last[2]);
            } else {
                boardView.clearTriangleMark();
            }
            updateColorUI();
            updateBoardView();
            updateNavButtons();
        } else {
            // 착수 모드 undo
            if (moveHistory.isEmpty()) {
                Toast.makeText(this, "취소할 수순이 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            moveHistory.remove(moveHistory.size() - 1);
            int targetStep = moveHistory.size();

            int[][] ko = rebuildMoveState(targetStep);
            koX = ko[0][0]; koY = ko[0][1];
            currentMoveStep = targetStep;

            // 다음 색
            if (targetStep == 0) {
                nextColor = BdkSection.BLACK;
            } else {
                int[] last = moveHistory.get(targetStep - 1);
                nextColor = (last[0] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            }

            boardView.clearLastMove();
            if (targetStep > 0) {
                int[] last = moveHistory.get(targetStep - 1);
                boardView.setLastMove(last[1], last[2]);
            }
            updateColorUI();
            updateBoardView();
            updateNavButtons();
        }
    }

    // ── 기보 추가 ─────────────────────────────────────

    private void addSection() {
        if (sections.isEmpty()) {
            Toast.makeText(this, "기본도를 먼저 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (basePlaceHistory.isEmpty() && currentSectionIdx == 0) {
            // 기본도에 돌이 없는지 확인
            boolean hasStone = false;
            outer:
            for (int y = 1; y <= 19; y++)
                for (int x = 1; x <= 19; x++)
                    if (boardState[y][x] != BdkSection.EMPTY) { hasStone = true; break outer; }
            if (!hasStone) {
                Toast.makeText(this, "기본도에 돌을 배치한 후 기보를 추가하세요.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        saveCurrentSectionState();
        int newIdx = sections.size();
        String[] names = {"정해도", "변화도1", "변화도2", "변화도3", "변화도4",
                          "변화도5", "변화도6", "변화도7", "변화도8"};
        String name = (newIdx < names.length) ? names[newIdx - 1] : ("변화도" + (newIdx - 1));
        BdkSection newSec = new BdkSection();
        newSec.name = name;
        sections.add(newSec);
        refreshSectionList();
        switchToSection(newIdx);
    }

    // ── 기보 삭제 ─────────────────────────────────────

    private void deleteCurrentSection() {
        if (currentSectionIdx == 0) {
            Toast.makeText(this, "기본도는 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("기보 삭제")
            .setMessage("'" + sections.get(currentSectionIdx).name + "'을(를) 삭제하시겠습니까?")
            .setPositiveButton("삭제", (d, w) -> {
                sections.remove(currentSectionIdx);
                refreshSectionList();
                switchToSection(Math.min(currentSectionIdx, sections.size() - 1));
            })
            .setNegativeButton("취소", null)
            .show();
    }

    // ── 기본도 입력 상태로 초기화 ────────────────────

    private void resetToBaseLayout() {
        if (currentSectionIdx == 0) return;
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        moveHistory.clear();
        currentMoveStep = 0;
        koX = -1; koY = -1;

        BdkSection base = sections.get(0);
        for (int[] stone : base.initialStones)
            boardState[stone[2]][stone[1]] = stone[0];
        sections.get(currentSectionIdx).moves.clear();
        updateBoardView();
    }

    // ── UI 갱신 ───────────────────────────────────────

    private void updateBoardView() {
        boardView.setBoard(boardState);
        boardView.setMoveNumbers(moveNumbers);
        boardView.setHoverColor(nextColor); // 드래그 미리보기 돌 색 동기화
        // 현재 섹션의 마커 데이터 전달
        if (currentSectionIdx >= 0 && currentSectionIdx < sections.size()) {
            boardView.setMarkers(sections.get(currentSectionIdx));
        } else {
            boardView.clearMarkers();
        }
        boardView.invalidate();
    }

    private void updateModeUI() {
        if (isBaseMode) {
            tvModeInfo.setText(currentSectionIdx == 0 ? "기본도 입력 모드" : "입력 모드 (기본도 수정)");
            btnToggleMode.setText("착수 입력으로 전환");
        } else {
            tvModeInfo.setText("착수 입력 모드");
            btnToggleMode.setText("입력 모드로 전환");
        }
        // 마커 팔레트는 입력/착수 모드 모두 표시
        if (markerToolbar != null) markerToolbar.setVisibility(View.VISIBLE);
        updateMarkerToolUI();
        btnToggleMode.setEnabled(true);
        btnToggleMode.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.btn_toggle_mode, null)));
        // 드래그 착수 모드: 코드 입력 시리즈 (입력/착수 모두 활성화)
        boardView.setDragPlaceMode(true);
    }

    private void updateColorUI() {
        boolean isBlack = (nextColor == BdkSection.BLACK);
        String label = isBlack ? "흑 ●" : "백 ○";
        String prefix = (currentSectionIdx == 0) ? "시작: " : "다음: ";
        tvNextColor.setText(prefix + label);
        tvNextColor.setTextColor(isBlack ? 0xFF000000 : 0xFF888888);
        btnToggleColor.setText(isBlack ? "흑 ●" : "백 ○");
        btnToggleColor.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(isBlack ? 0xFF222222 : 0xFFEEEEEE));
        btnToggleColor.setTextColor(isBlack ? 0xFFFFFFFF : 0xFF333333);
    }

    private void refreshSectionList() {
        sectionAdapter.setItems(sections);
        sectionAdapter.setSelectedIndex(currentSectionIdx);
        sectionAdapter.notifyDataSetChanged();
        lvSections.setSelection(currentSectionIdx);
        updateNavButtons();
    }

    private void updateNavButtons() {
        if (btnFirst == null) return;
        boolean canPrev, canNext;
        if (!isBaseMode) {
            // 착수 모드: 섹션에 무관하게 moveHistory 기준
            canPrev = (currentMoveStep > 0);
            canNext = (currentMoveStep < moveHistory.size());
        } else if (currentSectionIdx == 0) {
            // 배치 모드 기본도
            canPrev = !basePlaceHistory.isEmpty();
            canNext = false;
        } else {
            // 배치 모드 정해도/변화도
            canPrev = (currentSectionIdx > 0);
            canNext = (currentSectionIdx < sections.size() - 1);
        }
        btnFirst.setEnabled(canPrev);
        btnPrev.setEnabled(canPrev);
        btnNext.setEnabled(canNext);
        btnLast.setEnabled(canNext);
        btnFirst.setAlpha(canPrev ? 1.0f : 0.3f);
        btnPrev.setAlpha(canPrev ? 1.0f : 0.3f);
        btnNext.setAlpha(canNext ? 1.0f : 0.3f);
        btnLast.setAlpha(canNext ? 1.0f : 0.3f);

        if (seekBar != null) {
            if (!isBaseMode) {
                // 착수 모드: moveHistory 기준
                int total = moveHistory.size();
                seekBar.setMax(total == 0 ? 1 : total);
                seekBar.setProgress(currentMoveStep);
            } else if (currentSectionIdx == 0) {
                // 배치 모드 기본도
                seekBar.setMax(Math.max(1, basePlaceHistory.size()));
                seekBar.setProgress(basePlaceHistory.size());
            } else {
                // 배치 모드 정해도/변화도
                seekBar.setMax(sections.size() - 1);
                seekBar.setProgress(currentSectionIdx);
            }
        }
    }

    // ── 저장 ──────────────────────────────────────────

    private void save() {
        saveCurrentSectionState();
        if (loadedFilePath != null) {
            File f = new File(loadedFilePath);
            new AlertDialog.Builder(this)
                .setTitle("저장")
                .setMessage("'" + f.getName() + "' 파일에 덮어쓰시겠습니까?")
                .setPositiveButton("덮어쓰기", (d, w) -> {
                    try {
                        SgfWriter.write(sections, f);
                        Toast.makeText(this, "저장 완료: " + f.getName(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
        } else if (loadedUri != null) {
            String seg = loadedUri.getLastPathSegment();
            String uriFileName = seg != null ? new java.io.File(seg).getName() : "파일";
            new AlertDialog.Builder(this)
                .setTitle("저장")
                .setMessage("'" + uriFileName + "' 파일에 덮어쓰시겠습니까?")
                .setPositiveButton("덮어쓰기", (d, w) -> {
                    try {
                        byte[] data = SgfWriter.serialize(sections);
                        try (java.io.OutputStream os = getContentResolver().openOutputStream(loadedUri, "wt")) {
                            if (os == null) throw new IOException("출력 스트림을 열 수 없습니다");
                            os.write(data);
                        }
                        Toast.makeText(this, "저장 완료: " + uriFileName, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
        } else {
            showSaveAsDialog();
        }
    }

    private void showSaveAsDialog() {
        EditText et = new EditText(this);
        et.setHint("파일 이름 (예: 문제1.sgf)");
        et.setInputType(InputType.TYPE_CLASS_TEXT);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(48, 16, 48, 0);
        layout.addView(et);

        new AlertDialog.Builder(this)
            .setTitle("다른 이름으로 저장")
            .setView(layout)
            .setPositiveButton("저장", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "파일 이름을 입력하세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!name.endsWith(".sgf")) name += ".sgf";
                saveToNewFile(name);
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void saveToNewFile(String fileName) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "바돌");
            if (!dir.exists() && !dir.mkdirs()) {
                dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                    dir = getFilesDir();
                }
            }

            File outFile = new File(dir, fileName);

            if (outFile.exists()) {
                File finalOut = outFile;
                new AlertDialog.Builder(this)
                    .setTitle("파일 덮어쓰기")
                    .setMessage("'" + fileName + "' 파일이 이미 존재합니다. 덮어쓰시겠습니까?")
                    .setPositiveButton("덮어쓰기", (d2, w2) -> {
                        try {
                            SgfWriter.write(sections, finalOut);
                            loadedFilePath = finalOut.getAbsolutePath();
                            if (getSupportActionBar() != null)
                                getSupportActionBar().setTitle(finalOut.getName());
                            Toast.makeText(this, "저장 완료: " + finalOut.getName(), Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("취소", null)
                    .show();
            } else {
                SgfWriter.write(sections, outFile);
                loadedFilePath = outFile.getAbsolutePath();
                if (getSupportActionBar() != null)
                    getSupportActionBar().setTitle(outFile.getName());
                Toast.makeText(this, "저장 완료: " + outFile.getName(), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── 메뉴 ──────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        MenuItem coordItem = menu.findItem(R.id.action_show_coords);
        if (coordItem != null) coordItem.setChecked(prefs.getBoolean(PREF_COORDS, false));
        MenuItem soundItem = menu.findItem(R.id.action_sound);
        if (soundItem != null) soundItem.setChecked(prefs.getBoolean(PREF_SOUND, true));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            confirmExit();
            return true;
        } else if (id == R.id.action_game_info) {
            showGameInfoDialog();
            return true;
        } else if (id == R.id.action_select_section) {
            showSectionDialog();
            return true;
        } else if (id == R.id.action_save) {
            save();
            return true;
        } else if (id == R.id.action_save_as) {
            showSaveAsDialog();
            return true;
        } else if (id == R.id.action_rename_section) {
            showRenameSectionDialog();
            return true;
        } else if (id == R.id.action_clear_section) {
            confirmClearSection();
            return true;
        } else if (id == R.id.action_show_coords) {
            boolean v = !item.isChecked();
            item.setChecked(v);
            boardView.setShowCoords(v);
            boardView.invalidate();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_COORDS, v).apply();
            return true;
        } else if (id == R.id.action_sound) {
            soundEnabled = !item.isChecked();
            item.setChecked(soundEnabled);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_SOUND, soundEnabled).apply();
            Toast.makeText(this, "착수음 " + (soundEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── 착수음 및 설정 초기화 ────────────────────

    private void initSoundAndPrefs() {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (soundPool != null) { soundPool.release(); soundPool = null; }
    }

    // ── 기보 초기화 ───────────────────────────────────

    private void confirmClearSection() {
        new AlertDialog.Builder(this)
            .setTitle("기보 초기화")
            .setMessage("현재 기보의 모든 착수를 지우시겠습니까?")
            .setPositiveButton("초기화", (d, w) -> {
                boardState  = new int[21][21];
                moveNumbers = new int[21][21];
                koX = -1; koY = -1;
                if (currentSectionIdx == 0) {
                    basePlaceHistory.clear();
                    boardView.clearLastMove();
                    boardView.clearTriangleMark();
                } else {
                    moveHistory.clear();
                    currentMoveStep = 0;
                    BdkSection base = sections.get(0);
                    for (int[] stone : base.initialStones)
                        boardState[stone[2]][stone[1]] = stone[0];
                    sections.get(currentSectionIdx).moves.clear();
                    boardView.clearLastMove();
                    boardView.clearTriangleMark();
                }
                updateBoardView();
                updateNavButtons();
            })
            .setNegativeButton("취소", null)
            .show();
    }

    // ── 기보 이름 변경 ────────────────────────────────

    private void showRenameSectionDialog() {
        EditText et = new EditText(this);
        et.setText(sections.get(currentSectionIdx).name);
        et.setSelectAllOnFocus(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(48, 16, 48, 0);
        layout.addView(et);

        new AlertDialog.Builder(this)
            .setTitle("기보 이름 변경")
            .setView(layout)
            .setPositiveButton("확인", (d, w) -> {
                String name = et.getText().toString().trim();
                if (!name.isEmpty()) {
                    sections.get(currentSectionIdx).name = name;
                    tvSectionName.setText(name);
                    refreshSectionList();
                }
            })
            .setNegativeButton("취소", null)
            .show();
    }

    // ── 뒤로가기 확인 ─────────────────────────────────

    // 게임 정보 다이얼로그

    private void showGameInfoDialog() {
        if (sections.isEmpty()) {
            Toast.makeText(this, "서션이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        BdkSection base = sections.get(0);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        EditText etPB   = addLabeledField(layout, "흐 기사명", base.playerBlack);
        EditText etBR   = addLabeledField(layout, "흐 단급", base.rankBlack);
        EditText etPW   = addLabeledField(layout, "백 기사명", base.playerWhite);
        EditText etWR   = addLabeledField(layout, "백 단급", base.rankWhite);
        EditText etKomi = addLabeledField(layout, "덤 (KM)", base.komi);
        EditText etRE   = addLabeledField(layout, "결과 (RE)", base.result);
        EditText etDT   = addLabeledField(layout, "날짜 (DT)", base.date);
        EditText etEV   = addLabeledField(layout, "대회명 (EV)", base.event);
        EditText etPC   = addLabeledField(layout, "장소 (PC)", base.place);
        EditText etRO   = addLabeledField(layout, "라운드 (RO)", base.round);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle("게임 정보")
            .setView(sv)
            .setPositiveButton("확인", (d, w) -> {
                base.playerBlack = etPB.getText().toString().trim();
                base.rankBlack   = etBR.getText().toString().trim();
                base.playerWhite = etPW.getText().toString().trim();
                base.rankWhite   = etWR.getText().toString().trim();
                base.komi        = etKomi.getText().toString().trim();
                base.result      = etRE.getText().toString().trim();
                base.date        = etDT.getText().toString().trim();
                base.event       = etEV.getText().toString().trim();
                base.place       = etPC.getText().toString().trim();
                base.round       = etRO.getText().toString().trim();
                Toast.makeText(this, "게임 정보가 저장되었습니다.", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("취소", null)
            .show();
    }

    /** 레이블 + 입력필드 생성 헬퍼 */
    private EditText addLabeledField(LinearLayout parent, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setPadding(0, (int)(8 * getResources().getDisplayMetrics().density), 0, 2);
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(value != null ? value : "");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(lp);
        parent.addView(et);
        return et;
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
            .setTitle("편집기 종료")
            .setMessage("저장하지 않은 내용이 있을 수 있습니다. 종료하시겠습니까?")
            .setPositiveButton("종료", (d, w) -> finish())
            .setNegativeButton("취소", null)
            .show();
    }

    // ── 기보 선택 BottomSheet ─────────────────────────

    private void showSectionDialog() {
        if (sections.isEmpty()) {
            Toast.makeText(this, "섹션이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        BottomSheetDialog bs = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_sections, null);
        ListView lv = sheetView.findViewById(R.id.lvSections);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++)
            names.add((i + 1) + ". " + sections.get(i).name);
        lv.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((parent, view, which, id) -> {
            switchToSection(which);
            bs.dismiss();
        });
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

    // ── Window/Insets 설정 ────────────────────────────

    private void initWindow(String title) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(false);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        AppBarLayout appBar = findViewById(R.id.appBarLayout);
        View rootView = findViewById(android.R.id.content);
        applyWindowInsets(appBar, rootView);
        ViewCompat.requestApplyInsets(rootView);
    }

    private void applyWindowInsets(AppBarLayout appBar, View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (appBar != null) appBar.setPadding(0, sys.top, 0, 0);
            v.setPadding(sys.left, 0, sys.right, 0);
            android.view.View contentRow   = v.getRootView().findViewById(R.id.contentRow);
            android.view.View navBarSpacer = v.getRootView().findViewById(R.id.navBarSpacer);
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

    // ── 기보 목록 어댑터 ──────────────────────────────

    private class SectionAdapter extends BaseAdapter {
        private final List<String> items = new ArrayList<>();
        private int selectedIndex = -1;

        SectionAdapter(List<BdkSection> sections) {
            for (BdkSection s : sections) items.add(s.name);
        }

        void setItems(List<BdkSection> secs) {
            items.clear();
            for (BdkSection s : secs) items.add(s.name);
        }

        void setSelectedIndex(int idx) { this.selectedIndex = idx; }

        @Override public int    getCount()         { return items.size(); }
        @Override public Object getItem(int pos)   { return items.get(pos); }
        @Override public long   getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(EditorActivity.this);
                tv.setPadding(20, 18, 20, 18);
                tv.setTextSize(14f);
            }
            tv.setText(items.get(pos));
            if (pos == selectedIndex) {
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
}
