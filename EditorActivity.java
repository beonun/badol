package com.bdk.studio;

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
 * 기능:
 *  - 신규 BDK 파일 생성 (기본도 → 정해도/변화도 순서로 입력)
 *  - 기존 BDK 파일 불러와 수정
 *  - 기보(기본도/정해도/변화도) 추가·삭제
 *  - 저장: 기존 파일 덮어쓰기 / 다른 이름으로 저장
 *  - 기본도 착수 순서 변경: 선택한 돌부터 1번으로 재배열
 *
 * 진입 방법:
 *  - MainActivity: "새 기보" 버튼 → EXTRA_FILE_PATH 없이 시작
 *  - ViewerActivity: 메뉴 "편집기로 열기" → EXTRA_FILE_PATH 전달
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "editor_file_path";
    public static final String EXTRA_FILE_URI  = "editor_file_uri";

    // ── 상태 ──────────────────────────────────────────
    private List<BdkSection> sections = new ArrayList<>();
    private int currentSectionIdx = -1;  // 현재 편집 중인 섹션 인덱스 (-1=초기화 전)
    private boolean isBaseMode = true;   // true=기본도 입력 모드, false=착수 입력 모드
    private int nextColor = BdkSection.BLACK; // 다음 놓을 돌 색
    private String loadedFilePath = null; // 불러온 파일 경로 (null=신규 또는 URI 기반)
    private Uri    loadedUri      = null;  // URI 기반으로 불러온 경우 원본 URI (null=신규 또는 path 기반)

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

    // 현재 섹션의 바둑판 상태
    private int[][] boardState;   // [y][x] 0=빈, 1=흑, 2=백
    private int[][] moveNumbers;  // [y][x] 표시 번호 (기본도: seq+1, 정해도/변화도: 착수 순서)
    private int     moveCount = 0; // 현재 섹션의 착수 수

    // 실행 취소/복원 스택
    // undoEntry: [x, y, prevColor, prevMoveNum, capturedCount, (cx0,cy0,cc0), (cx1,cy1,cc1), ...]
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>(); // redo 용 (기본도 전용)

    // 바둑 규칙 헬퍼
    private GoRules goRules;
    // 패 금지 좌표 (-1이면 없음)
    private int koX = -1, koY = -1;
    // 착수 모드 수순 이동을 위한 전체 수순 스냅샷
    // moveHistory.get(i) = [color, x, y, capturedCount, cx0, cy0, cc0, ...]
    private final List<int[]> moveHistory = new ArrayList<>();
    private int currentMoveStep = 0; // 현재 표시 중인 수순 위치 (0=기본도 상태)

    // ── 설정 (ViewerActivity와 공유) ─────────────────────
    private static final String PREFS_NAME  = "viewer_prefs"; // ViewerActivity와 동일한 prefs 사용
    private static final String PREF_SOUND  = "sound_enabled";
    private static final String PREF_COORDS = "show_coords";
    private boolean soundEnabled = true;
    private SoundPool soundPool;
    private int       soundId    = -1;
    private boolean   soundReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 스마트폰(sw < 600dp)에서는 세로 방향 고정
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        setContentView(R.layout.activity_editor);
        initWindow("기보 편집기");

        bindViews();
        setupBoardView();
        setupButtons();
        setupSectionList();
        initSoundAndPrefs(); // 착수음 및 설정 초기화

        // 뮤로 버튼 / 시스템 백 제스처 → 종료 확인 다이얼로그
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { confirmExit(); }
        });

        // 파일 불러오기 또는 신규 시작
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

    // ── 화면 회전 처리 ──────────────────────────────────

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(R.layout.activity_editor);
        String rotTitle = (loadedFilePath != null)
                ? new java.io.File(loadedFilePath).getName()
                : (sections.isEmpty() ? "새 기보" : "기보 편집기");
        initWindow(rotTitle);

        bindViews();
        rebindBoardView();  // boardView 리스너만 재연결 (데이터 초기화 없음)
        setupButtons();
        setupSectionList();

        // 화면 회전 후 좌표/착수음 설정 복원
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boardView.setShowCoords(prefs.getBoolean(PREF_COORDS, false));

        // 현재 상태 복원
        if (!sections.isEmpty() && currentSectionIdx >= 0) {
            refreshSectionList();
            updateBoardView();
            updateModeUI();
            updateColorUI();
            updateNavButtons();
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
    }

    // ── BoardView 설정 ────────────────────────────────

    private void setupBoardView() {
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        goRules     = new GoRules(19);
        boardView.setTouchListener((x, y) -> onBoardTouch(x, y));
        boardView.setDoubleTapListener(() -> undo()); // 더블탭으로 실행 취소
        boardView.setShowNumbers(true);
        boardView.setShowCoords(false);
        boardView.resetZoom();
    }

    /** 화면 회전 시 boardView 리스너만 재연결 (데이터 초기화 없음) */
    private void rebindBoardView() {
        boardView.setTouchListener((x, y) -> onBoardTouch(x, y));
        boardView.setDoubleTapListener(() -> undo());
        boardView.setShowNumbers(true);
        boardView.setShowCoords(false);
    }

    // ── 버튼 설정 ─────────────────────────────────────

    private void setupButtons() {
        // 모드 전환: 기본도 입력 ↔ 착수 입력
        btnToggleMode.setOnClickListener(v -> {
            if (currentSectionIdx == 0) {
                // 기본도 섹션: 입력 모드만 사용
                Toast.makeText(this, "기본도는 입력 모드만 사용합니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            isBaseMode = !isBaseMode;
            if (isBaseMode) {
                // 착수 입력 → 입력 모드: 착수 전부 취소하고 기본도 입력 상태로 복원
                resetToBaseLayout();
            }
            updateModeUI();
        });

        // 흑/백 전환
        btnToggleColor.setOnClickListener(v -> {
            nextColor = (nextColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            updateColorUI();
        });

        // 실행 취소
        btnUndo.setOnClickListener(v -> undo());

        // 기보 추가
        btnAddSection.setOnClickListener(v -> addSection());

        // 기보 삭제
        btnDelSection.setOnClickListener(v -> deleteCurrentSection());

        // 네비게이션 버튼
        if (btnFirst != null) btnFirst.setOnClickListener(v -> onNavFirst());
        if (btnPrev  != null) btnPrev.setOnClickListener(v -> onNavPrev());
        if (btnNext  != null) btnNext.setOnClickListener(v -> onNavNext());
        if (btnLast  != null) btnLast.setOnClickListener(v -> onNavLast());

        // 시크바
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    if (currentSectionIdx == 0) {
                        // 기본도: undo/redo로 탐색
                        int target = progress;
                        int current = undoStack.size();
                        if (target < current) { for (int i = current; i > target; i--) undo(); }
                        else if (target > current) { for (int i = current; i < target; i++) redo(); }
                    } else if (!isBaseMode) {
                        // 착수 모드: 수순 이동
                        goToMoveStep(progress);
                    } else {
                        // 배치 모드: 섹션 간 이동
                        navigateTo(progress);
                    }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
        }
    }

    // ── 기보 목록 설정 ────────────────────────────────

    private void setupSectionList() {
        sectionAdapter = new SectionAdapter(new ArrayList<BdkSection>());
        lvSections.setAdapter(sectionAdapter);
        lvSections.setChoiceMode(ListView.CHOICE_MODE_NONE);

        lvSections.setOnItemClickListener((parent, view, position, id) -> {
            switchToSection(position);
        });
    }

    // ── 신규 시작 ─────────────────────────────────────

    private void startNew() {
        sections.clear();
        BdkSection base = new BdkSection();
        base.name = "기본도";
        sections.add(base);
        loadedFilePath = null;
        loadedUri = null;
        switchToSection(0);
        refreshSectionList();
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("새 기보");
    }

    // ── 파일 불러오기 ─────────────────────────────────

    private void loadFromPath(String path) {
        try {
            File f = new File(path);
            List<BdkSection> parsed = BdkParser.parse(f);
            if (parsed.isEmpty()) { startNew(); return; }
            sections = parsed;
            loadedFilePath = path;
            switchToSection(0);
            refreshSectionList();
            String name = f.getName();
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(name);
        } catch (Exception e) {
            Toast.makeText(this, "파일 불러오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            startNew();
        }
    }

    private void loadFromUri(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            // URI → 임시 파일로 복사 후 파싱
            File tmp = new File(getCacheDir(), "editor_tmp.bdk");
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (is == null) throw new IOException("입력 스트림을 열 수 없습니다");
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            }
            List<BdkSection> parsed = BdkParser.parse(tmp);
            if (parsed.isEmpty()) { startNew(); return; }
            sections = parsed;
            loadedFilePath = null;
            loadedUri = uri; // URI 기반: 저장 시 덮어쓰기에 사용
            switchToSection(0);
            refreshSectionList();
            // URI 마지막 세그먼트에서 파일명만 추출 (경로 제외)
            String seg = uri.getLastPathSegment();
            String uriFileName = seg != null ? new java.io.File(seg).getName() : "편집 중";
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(uriFileName);
        } catch (Exception e) {
            Toast.makeText(this, "파일 불러오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            startNew();
        }
    }

    // ── 기보 전환 ─────────────────────────────────────

    // 기본도(idx=0)에서는 undo/redo, 기보가 2개 이상일 때는 기보 간 이동
    private void onNavFirst() {
        if (currentSectionIdx == 0) {
            while (!undoStack.isEmpty()) undo();
        } else if (!isBaseMode) {
            // 착수 모드: 첫 수순(0수)로
            goToMoveStep(0);
        } else {
            navigateTo(0);
        }
    }

    private void onNavPrev() {
        if (currentSectionIdx == 0) {
            undo();
        } else if (!isBaseMode) {
            // 착수 모드: 한 수 이전
            goToMoveStep(currentMoveStep - 1);
        } else {
            navigateTo(currentSectionIdx - 1);
        }
    }

    private void onNavNext() {
        if (currentSectionIdx == 0) {
            redo();
        } else if (!isBaseMode) {
            // 착수 모드: 한 수 다음
            goToMoveStep(currentMoveStep + 1);
        } else {
            navigateTo(currentSectionIdx + 1);
        }
    }

    private void onNavLast() {
        if (currentSectionIdx == 0) {
            while (!redoStack.isEmpty()) redo();
        } else if (!isBaseMode) {
            // 착수 모드: 마지막 수순
            goToMoveStep(moveHistory.size());
        } else {
            navigateTo(sections.size() - 1);
        }
    }

    /**
     * 착수 모드에서 특정 수순으로 이동.
     * moveHistory를 기반으로 기본도에서 재계산.
     */
    private void goToMoveStep(int targetStep) {
        if (isBaseMode || currentSectionIdx == 0) return;
        int total = moveHistory.size();
        targetStep = Math.max(0, Math.min(targetStep, total));
        if (targetStep == currentMoveStep) return;

        // 기본도에서 다시 시작하여 targetStep까지 재생
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        koX = -1; koY = -1;

        BdkSection base = sections.get(0);
        for (int[] stone : base.initialStones)
            boardState[stone[2]][stone[1]] = stone[0];

        for (int i = 0; i < targetStep; i++) {
            int[] h = moveHistory.get(i);
            int color = h[0], mx = h[1], my = h[2];
            int moveNo = i + 1;
            goRules.applyMove(boardState, moveNumbers, color, mx, my, moveNo);
            // 패 갱신
            int capturedCount = h[3];
            if (capturedCount == 1 && i + 1 < moveHistory.size()) {
                // 다음 수순에서 패 판별을 위해 따내진 자리 저장
                // (단순화: 마지막 수순의 패만 추적)
            }
        }

        // 현재 수순의 패 처리
        // moveHistory 항목: [color, x, y, capturedCount, cx0, cy0, cc0, ...]
        // 1점 따내고 자기도 1점 그룹이면 패
        if (targetStep > 0) {
            int[] lastH = moveHistory.get(targetStep - 1);
            int capturedCount = lastH[3];
            if (capturedCount == 1 && lastH.length >= 7) {
                // 따내진 좌표: lastH[4]=cx, lastH[5]=cy
                koX = lastH[4];
                koY = lastH[5];
            } else {
                koX = -1; koY = -1;
            }
        }

        currentMoveStep = targetStep;
        moveCount = targetStep;

        // 다음 색 계산
        if (targetStep == 0) {
            BdkSection sec = sections.get(currentSectionIdx);
            nextColor = guessNextColorFromMoves(sec.moves, base.initialStones);
            // 첫 수순 이전이면 첫 번째 수순의 색
            if (!moveHistory.isEmpty()) nextColor = moveHistory.get(0)[0];
        } else {
            int[] lastH = moveHistory.get(targetStep - 1);
            nextColor = (lastH[0] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
        }

        // 마지막 수순 표시
        if (targetStep > 0) {
            int[] lastH = moveHistory.get(targetStep - 1);
            boardView.setLastMove(lastH[1], lastH[2]);
        } else {
            boardView.clearLastMove();
        }

        updateColorUI();
        updateBoardView();
        updateNavButtons();
    }

    // 네비게이션: 범위 체크 후 switchToSection 호출
    private void navigateTo(int idx) {
        if (sections.isEmpty()) return;
        int target = Math.max(0, Math.min(idx, sections.size() - 1));
        if (target == currentSectionIdx) return;
        switchToSection(target);
    }

    private void switchToSection(int idx) {
        if (idx < 0 || idx >= sections.size()) return;

        // 현재 섹션 저장 (초기화 전이면 건너뜀)
        saveCurrentSectionState();

        currentSectionIdx = idx;
        BdkSection sec = sections.get(idx);

        // 기본도 섹션(idx=0)은 항상 입력 모드
        if (idx == 0) isBaseMode = true;

        // 바둑판 상태 복원
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        moveCount   = 0;
        undoStack.clear();

        moveHistory.clear();
        currentMoveStep = 0;
        koX = -1; koY = -1;

        if (idx == 0) {
            // 기본도: initialStones 배치
            for (int[] stone : sec.initialStones) {
                int color = stone[0], x = stone[1], y = stone[2];
                int seq   = stone[3]; // BdkParser: 기본도=0~999
                boardState[y][x]  = color;
                moveNumbers[y][x] = seq + 1; // 표시 번호: 1, 2, 3...
                moveCount++;
            }
            // 다음 색: 돌 수 기반 자동 계산 (바둑 규칙 준수)
            nextColor = computeCorrectNextColor();
        } else {
            // 정해도/변화도: 기본도 배치 + 착수 (따내기 적용)
            BdkSection base = sections.get(0);
            for (int[] stone : base.initialStones) {
                boardState[stone[2]][stone[1]] = stone[0];
            }
            if (isBaseMode) {
                isBaseMode = false;
            }
            // 저장된 수순을 순서대로 재생하며 따내기 적용
            for (int[] move : sec.moves) {
                int num = move[0], color = move[1], mx = move[2], my = move[3];
                List<int[]> caps = goRules.applyMove(boardState, moveNumbers, color, mx, my, num);
                moveCount = Math.max(moveCount, num);
                // moveHistory 재구성
                int[] histEntry = new int[4 + caps.size() * 3];
                histEntry[0] = color; histEntry[1] = mx; histEntry[2] = my; histEntry[3] = caps.size();
                for (int i = 0; i < caps.size(); i++) {
                    histEntry[4 + i * 3]     = caps.get(i)[0];
                    histEntry[4 + i * 3 + 1] = caps.get(i)[1];
                    histEntry[4 + i * 3 + 2] = caps.get(i)[2];
                }
                moveHistory.add(histEntry);
            }
            currentMoveStep = moveHistory.size();
            nextColor = guessNextColorFromMoves(sec.moves, base.initialStones);
        }

        if (sectionAdapter != null) {
            sectionAdapter.setSelectedIndex(idx);
            sectionAdapter.notifyDataSetChanged();
        }
        lvSections.setSelection(idx);
        updateBoardView();
        updateModeUI();
        updateColorUI();
        tvSectionName.setText(sec.name);
        updateNavButtons();
        // 파일에 저장된 zoom 영역 적용 (없으면 전체 보기)
        if (sec.hasZoom()) {
            boardView.setZoomRegion(sec.zoomX1, sec.zoomY1, sec.zoomX2, sec.zoomY2);
        } else {
            boardView.resetZoom();
        }
    }

    /**
     * 현재 boardState의 흑/백 돌 수를 세어 올바른 다음 착수색을 반환.
     * 흑 > 백: 마지막=흑 → 다음=백
     * 흑 == 백: 마지막=백 → 다음=흑
     * 돌이 없으면: 흑이 첫 번째이므로 흑 반환
     */
    private int computeCorrectNextColor() {
        int blackCount = 0, whiteCount = 0;
        for (int y = 1; y <= 19; y++)
            for (int x = 1; x <= 19; x++) {
                if (boardState[y][x] == BdkSection.BLACK) blackCount++;
                else if (boardState[y][x] == BdkSection.WHITE) whiteCount++;
            }
        if (blackCount == 0 && whiteCount == 0) return BdkSection.BLACK;
        return (blackCount > whiteCount) ? BdkSection.WHITE : BdkSection.BLACK;
    }

    /** 착수 목록 기준으로 다음 색 추정 */
    private int guessNextColorFromMoves(List<int[]> moves, List<int[]> baseStones) {
        if (!moves.isEmpty()) {
            int[] last = moves.get(moves.size() - 1);
            return (last[1] == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
        }
        // 착수가 없으면 기본도 돌 수로 자동 계산
        return computeCorrectNextColor();
    }

    // ── 현재 기보 상태 저장 ───────────────────────────

    private void saveCurrentSectionState() {
        if (currentSectionIdx < 0 || currentSectionIdx >= sections.size()) return;
        BdkSection sec = sections.get(currentSectionIdx);
        // 현재 zoom 상태를 섹션에 저장
        if (boardView != null) {
            int[] z = boardView.getZoomCoords();
            sec.zoomX1 = z[0]; sec.zoomY1 = z[1];
            sec.zoomX2 = z[2]; sec.zoomY2 = z[3];
        }

        if (currentSectionIdx == 0) {
            // 기본도: boardState → initialStones 재구성 (기보 저장)
            sec.initialStones.clear();
            List<int[]> ordered = new ArrayList<>();
            for (int y = 1; y <= 19; y++) {
                for (int x = 1; x <= 19; x++) {
                    if (boardState[y][x] != BdkSection.EMPTY) {
                        int num = moveNumbers[y][x]; // 표시 번호 1, 2, 3...
                        ordered.add(new int[]{boardState[y][x], x, y, num - 1}); // seq=0~999
                    }
                }
            }
            ordered.sort((a, b) -> Integer.compare(a[3], b[3]));
            sec.initialStones = ordered;

            // BDK 파일 구조 변경 없음 - 돌 수로 다음 착수색 자동 결정
        } else {
            // 정해도/변화도: moves 재구성
            sec.moves.clear();
            List<int[]> ordered = new ArrayList<>();
            for (int y = 1; y <= 19; y++) {
                for (int x = 1; x <= 19; x++) {
                    if (moveNumbers[y][x] > 0) {
                        ordered.add(new int[]{moveNumbers[y][x], boardState[y][x], x, y});
                    }
                }
            }
            ordered.sort((a, b) -> Integer.compare(a[0], b[0]));
            sec.moves = ordered;
        }
    }

    // ── 바둑판 터치 처리 ──────────────────────────────

    private void onBoardTouch(int x, int y) {
        if (x < 1 || x > 19 || y < 1 || y > 19) return;

        // 기본도 read-only: 정해도/변화도가 존재하면 기본도 수정 불가
        if (currentSectionIdx == 0 && sections.size() > 1) {
            Toast.makeText(this, "정해도/변화도가 있으면 기본도를 수정할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentSectionIdx == 0) {
            // 기본도 입력 모드: 따내기/패 없이 단순 배치
            int prevColor = boardState[y][x];
            if (prevColor != BdkSection.EMPTY) {
                // 마지막 놓은 돌이면 탭으로 제거
                if (!undoStack.isEmpty()) {
                    int[] last = undoStack.get(undoStack.size() - 1);
                    if (last[0] == x && last[1] == y && last[2] == BdkSection.EMPTY) undo();
                }
                return;
            }
            redoStack.clear();
            undoStack.add(new int[]{x, y, BdkSection.EMPTY, 0, 0}); // capturedCount=0
            boardState[y][x] = nextColor;
            moveCount++;
            moveNumbers[y][x] = moveCount;
            nextColor = computeCorrectNextColor();
            updateColorUI();
            if (soundEnabled && soundReady && soundPool != null && soundId != -1)
                soundPool.play(soundId, 1f, 1f, 0, 0, 1f);
            boardView.setLastMove(x, y);
            updateBoardView();
            updateNavButtons();
            return;
        }

        if (isBaseMode) {
            // 배치 모드 (정해도/변화도 기본도 수정): 따내기 없이 단순 배치
            int prevColor = boardState[y][x];
            if (prevColor != BdkSection.EMPTY) {
                if (!undoStack.isEmpty()) {
                    int[] last = undoStack.get(undoStack.size() - 1);
                    if (last[0] == x && last[1] == y && last[2] == BdkSection.EMPTY) undo();
                }
                return;
            }
            redoStack.clear();
            undoStack.add(new int[]{x, y, BdkSection.EMPTY, 0, 0});
            boardState[y][x] = nextColor;
            nextColor = (nextColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            updateColorUI();
            if (soundEnabled && soundReady && soundPool != null && soundId != -1)
                soundPool.play(soundId, 1f, 1f, 0, 0, 1f);
            boardView.setLastMove(x, y);
            updateBoardView();
            updateNavButtons();
            return;
        }

        // 착수 모드: GoRules 적용 (따내기, 자살수, 패 판별)
        // 수순 이동 중(현재스텝 < 전체 수순)이면 새 착수 불가
        if (currentMoveStep < moveHistory.size()) {
            Toast.makeText(this, "마지막 수순으로 이동 후 착수하세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int moveNo = moveHistory.size() + 1;
        GoRules.PlaceResult result = goRules.tryPlace(
                boardState, moveNumbers, nextColor, x, y, koX, koY, moveNo);

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

        // 착수 성공: 보드 상태 갱신
        List<int[]> captured = result.capturedStones;

        // undoEntry 구성: [x, y, EMPTY, 0, capturedCount, cx0,cy0,cc0, cx1,cy1,cc1, ...]
        int[] undoEntry = new int[5 + captured.size() * 3];
        undoEntry[0] = x;
        undoEntry[1] = y;
        undoEntry[2] = BdkSection.EMPTY;
        undoEntry[3] = 0;
        undoEntry[4] = captured.size();
        for (int i = 0; i < captured.size(); i++) {
            undoEntry[5 + i * 3]     = captured.get(i)[0]; // cx
            undoEntry[5 + i * 3 + 1] = captured.get(i)[1]; // cy
            undoEntry[5 + i * 3 + 2] = captured.get(i)[2]; // color
        }
        undoStack.add(undoEntry);

        // moveHistory 기록: [color, x, y, capturedCount, cx0,cy0,cc0, ...]
        int[] histEntry = new int[4 + captured.size() * 3];
        histEntry[0] = nextColor;
        histEntry[1] = x;
        histEntry[2] = y;
        histEntry[3] = captured.size();
        for (int i = 0; i < captured.size(); i++) {
            histEntry[4 + i * 3]     = captured.get(i)[0];
            histEntry[4 + i * 3 + 1] = captured.get(i)[1];
            histEntry[4 + i * 3 + 2] = captured.get(i)[2];
        }
        moveHistory.add(histEntry);
        currentMoveStep = moveHistory.size();

        // 보드 상태 적용
        for (int iy = 0; iy < result.newBoard.length; iy++)
            System.arraycopy(result.newBoard[iy], 0, boardState[iy], 0, result.newBoard[iy].length);
        for (int iy = 0; iy < result.newMoveNum.length; iy++)
            System.arraycopy(result.newMoveNum[iy], 0, moveNumbers[iy], 0, result.newMoveNum[iy].length);

        moveCount = moveHistory.size();
        koX = result.newKoX;
        koY = result.newKoY;
        nextColor = (nextColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
        updateColorUI();
        if (soundEnabled && soundReady && soundPool != null && soundId != -1)
            soundPool.play(soundId, 1f, 1f, 0, 0, 1f);
        boardView.setLastMove(x, y);
        updateBoardView();
        updateNavButtons();
    }

    // ── 실행 취소 ─────────────────────────────────────

    private void undo() {
        // 기본도 read-only: 정해도/변화도가 있으면 기본도 수정 불가
        if (currentSectionIdx == 0 && sections.size() > 1) {
            Toast.makeText(this, "정해도/변화도가 있으면 기본도를 수정할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "취소할 작업이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] last = undoStack.remove(undoStack.size() - 1);
        int x = last[0], y = last[1], prevColor = last[2], prevMoveNum = last[3];

        if (prevColor == BdkSection.EMPTY) {
            // 돌을 놓은 것을 취소 → 돌 제거
            int curColor = boardState[y][x];
            int curNum   = moveNumbers[y][x];
            redoStack.add(new int[]{x, y, curColor, curNum});
            if (moveNumbers[y][x] > 0) moveCount--;
            boardState[y][x]  = BdkSection.EMPTY;
            moveNumbers[y][x] = 0;

            // 착수 모드: 따내진 돌 복원 + moveHistory 제거
            if (!isBaseMode && currentSectionIdx != 0) {
                // undoEntry에 따내진 돌 정보가 있으면 복원
                int capturedCount = (last.length > 4) ? last[4] : 0;
                for (int i = 0; i < capturedCount; i++) {
                    int cx = last[5 + i * 3];
                    int cy = last[5 + i * 3 + 1];
                    int cc = last[5 + i * 3 + 2];
                    boardState[cy][cx]  = cc;
                    moveNumbers[cy][cx] = 0; // 따내진 돌은 번호 없음
                }
                // moveHistory 마지막 항목 제거
                if (!moveHistory.isEmpty()) {
                    moveHistory.remove(moveHistory.size() - 1);
                    currentMoveStep = moveHistory.size();
                }
                moveCount = moveHistory.size();
                // 패 초기화 (단순화: undo 시 패 해제)
                koX = -1; koY = -1;
            }

            // 색 되돌리기
            if (currentSectionIdx == 0) {
                nextColor = computeCorrectNextColor();
            } else {
                nextColor = (nextColor == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
            }
            updateColorUI();
        } else {
            // 돌을 제거한 것을 취소 → 복원 (기본도 전용)
            redoStack.add(new int[]{x, y, BdkSection.EMPTY, 0});
            boardState[y][x]  = prevColor;
            moveNumbers[y][x] = prevMoveNum;
            if (prevMoveNum > 0) moveCount++;
        }
        boardView.clearLastMove();
        updateBoardView();
        updateNavButtons();
    }

    /** 실행 취소 복원 (redo) - 기본도 전용 */
    private void redo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "복원할 작업이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] entry = redoStack.remove(redoStack.size() - 1);
        int x = entry[0], y = entry[1], savedColor = entry[2], savedNum = entry[3];
        if (savedColor == BdkSection.EMPTY) {
            // 원래 제거 동작을 다시 실행 → 돌 제거
            undoStack.add(new int[]{x, y, boardState[y][x], moveNumbers[y][x]});
            if (moveNumbers[y][x] > 0) moveCount--;
            boardState[y][x]  = BdkSection.EMPTY;
            moveNumbers[y][x] = 0;
            if (currentSectionIdx == 0) nextColor = computeCorrectNextColor();
        } else {
            // 원래 놓기 동작을 다시 실행 → 돌 복원
            undoStack.add(new int[]{x, y, BdkSection.EMPTY, 0});
            boardState[y][x]  = savedColor;
            moveNumbers[y][x] = savedNum;
            if (savedNum > 0) moveCount++;
            if (currentSectionIdx == 0) nextColor = computeCorrectNextColor();
        }
        updateColorUI();
        boardView.clearLastMove();
        updateBoardView();
        updateNavButtons();
    }

    // ── 기보 추가 ─────────────────────────────────────

    private void addSection() {
        if (sections.isEmpty()) {
            Toast.makeText(this, "기본도를 먼저 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        // 기본도가 비어 있으면 경고
        if (sections.get(0).initialStones.isEmpty()) {
            boolean hasStone = false;
            for (int y = 1; y <= 19; y++)
                for (int x = 1; x <= 19; x++)
                    if (boardState[y][x] != BdkSection.EMPTY) { hasStone = true; break; }
            if (!hasStone) {
                Toast.makeText(this, "기본도에 돌을 배치한 후 기보를 추가하세요.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        // 현재 기보 저장 후 새 기보 추가
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

    // ── 기본도 입력 상태로 초기화 (착수 취소) ────────────

    private void resetToBaseLayout() {
        if (currentSectionIdx == 0) return;
        boardState  = new int[21][21];
        moveNumbers = new int[21][21];
        moveCount   = 0;
        undoStack.clear();
        moveHistory.clear();
        currentMoveStep = 0;
        koX = -1; koY = -1;

        BdkSection base = sections.get(0);
        for (int[] stone : base.initialStones) {
            boardState[stone[2]][stone[1]] = stone[0];
        }
        sections.get(currentSectionIdx).moves.clear();
        updateBoardView();
    }

    // ── UI 갱신 ───────────────────────────────────────

    private void updateBoardView() {
        boardView.setBoard(boardState);
        boardView.setMoveNumbers(moveNumbers);
        boardView.invalidate();
    }

    private void updateModeUI() {
        if (currentSectionIdx == 0) {
            tvModeInfo.setText("기본도 입력 모드");
            btnToggleMode.setText("입력 모드");
            btnToggleMode.setEnabled(false);
            btnToggleMode.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFAAAAAA));
        } else {
            if (isBaseMode) {
                tvModeInfo.setText("입력 모드 (기본도 수정)");
                btnToggleMode.setText("착수 입력으로 전환");
            } else {
                tvModeInfo.setText("착수 입력 모드");
                btnToggleMode.setText("입력 모드로 전환");
            }
            btnToggleMode.setEnabled(true);
            btnToggleMode.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.btn_toggle_mode, null)));
        }
    }

    private void updateColorUI() {
        boolean isBlack = (nextColor == BdkSection.BLACK);
        String label = isBlack ? "흑 ●" : "백 ○";
        String prefix = (currentSectionIdx == 0) ? "시작: " : "다음: ";
        tvNextColor.setText(prefix + label);
        tvNextColor.setTextColor(isBlack ? 0xFF000000 : 0xFF888888);
        // 버튼 텍스트: 현재 색 표시 (누르면 반대로 전환)
        btnToggleColor.setText(isBlack ? "흑 ●" : "백 ○");
        // 버튼 배경색: 흑=검정, 백=흰색(회색 텍스트)
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

    /** 네비게이션 버튼 활성/비활성 상태 업데이트 */
    private void updateNavButtons() {
        if (btnFirst == null) return;
        boolean canPrev, canNext;
        if (currentSectionIdx == 0) {
            // 기본도 모드: undo/redo 스택 상태로 활성화
            canPrev = !undoStack.isEmpty();
            canNext = !redoStack.isEmpty();
        } else if (!isBaseMode) {
            // 착수 모드: 수순 위치 기준
            canPrev = (currentMoveStep > 0);
            canNext = (currentMoveStep < moveHistory.size());
        } else {
            // 배치 모드: 섹션 간 이동
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

        // 시크바 업데이트
        if (seekBar != null) {
            if (currentSectionIdx == 0) {
                // 기본도: undo 스택 기준
                int total = undoStack.size() + redoStack.size();
                seekBar.setMax(total);
                seekBar.setProgress(undoStack.size());
            } else if (!isBaseMode) {
                // 착수 모드: 수순 기준
                int total = moveHistory.size();
                seekBar.setMax(total == 0 ? 1 : total);
                seekBar.setProgress(currentMoveStep);
            } else {
                // 배치 모드: 섹션 간 이동
                seekBar.setMax(sections.size() - 1);
                seekBar.setProgress(currentSectionIdx);
            }
        }
    }

    // ── 저장 ──────────────────────────────────────────

    /**
     * 저장 처리:
     *  - loadedFilePath가 있으면 → 덮어쓰기 확인 다이얼로그
     *  - loadedFilePath가 없으면 (신규) → 파일 이름 입력 다이얼로그
     */
    private void save() {
        saveCurrentSectionState();
        if (loadedFilePath != null) {
            // path 기반 파일: 덮어쓰기 확인
            File f = new File(loadedFilePath);
            new AlertDialog.Builder(this)
                .setTitle("저장")
                .setMessage("'" + f.getName() + "' 파일에 덮어쓰시겠습니까?")
                .setPositiveButton("덮어쓰기", (d, w) -> {
                    try {
                        BdkWriter.write(sections, f);
                        Toast.makeText(this, "저장 완료: " + f.getName(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
        } else if (loadedUri != null) {
            // URI 기반 파일: URI로 덮어쓰기 확인
            String seg = loadedUri.getLastPathSegment();
            String uriFileName = seg != null ? new java.io.File(seg).getName() : "파일";
            new AlertDialog.Builder(this)
                .setTitle("저장")
                .setMessage("'" + uriFileName + "' 파일에 덮어쓰시겠습니까?")
                .setPositiveButton("덮어쓰기", (d, w) -> {
                    try {
                        byte[] data = BdkWriter.serialize(sections);
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
            // 신규: 이름 입력
            showNewFileDialog();
        }
    }

    /**
     * 신규 파일 저장: 파일 이름 입력 다이얼로그 (loadedFilePath == null 일 때)
     */
    private void showNewFileDialog() {
        EditText etName = new EditText(this);
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        etName.setHint("파일 이름 (예: 문제001)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 0);
        layout.addView(etName);

        new AlertDialog.Builder(this)
            .setTitle("저장")
            .setView(layout)
            .setPositiveButton("저장", (d, w) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "파일 이름을 입력하세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!name.toLowerCase().endsWith(".bdk")) name += ".bdk";
                saveToNewFile(name);
            })
            .setNegativeButton("취소", null)
            .show();
    }

    /**
     * 다른 이름으로 저장: 파일 이름 입력 다이얼로그
     */
    private void showSaveAsDialog() {
        saveCurrentSectionState();

        EditText etName = new EditText(this);
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        etName.setHint("파일 이름 (예: 문제001)");

        // 다른 이름으로 저장: 기존 파일명을 입력창에 미리 표시하여 수정 가능하게
        String existingName = null;
        if (loadedFilePath != null) {
            existingName = new File(loadedFilePath).getName();
        } else if (loadedUri != null) {
            String seg = loadedUri.getLastPathSegment();
            existingName = seg != null ? new java.io.File(seg).getName() : null;
        }
        if (existingName != null) {
            if (existingName.toLowerCase().endsWith(".bdk"))
                existingName = existingName.substring(0, existingName.length() - 4);
            etName.setText(existingName);
            etName.setSelection(existingName.length()); // 커서를 끝으로
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 0);
        layout.addView(etName);

        new AlertDialog.Builder(this)
            .setTitle("다른 이름으로 저장")
            .setView(layout)
            .setPositiveButton("저장", (d, w) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "파일 이름을 입력하세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!name.toLowerCase().endsWith(".bdk")) name += ".bdk";
                saveToNewFile(name);
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void saveToNewFile(String fileName) {
        try {
            // 내부 저장소 최상위 Bdk 폴더에 저장
            File dir = new File(Environment.getExternalStorageDirectory(), "Bdk");
            if (!dir.exists() && !dir.mkdirs()) {
                // 폴더 생성 실패 시 앱 전용 외부 저장소로 폴백
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
                            BdkWriter.write(sections, finalOut);
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
                BdkWriter.write(sections, outFile);
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
        // 메뉴 체크 상태를 현재 설정값으로 동기화
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
                moveCount   = 0;
                undoStack.clear();
                if (currentSectionIdx != 0) {
                    BdkSection base = sections.get(0);
                    for (int[] stone : base.initialStones)
                        boardState[stone[2]][stone[1]] = stone[0];
                    sections.get(currentSectionIdx).moves.clear();
                }
                boardView.clearLastMove();
                updateBoardView();
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

    private void confirmExit() {
        new AlertDialog.Builder(this)
            .setTitle("편집기 종료")
            .setMessage("저장하지 않은 내용이 있을 수 있습니다. 종료하시겠습니까?")
            .setPositiveButton("종료", (d, w) -> finish())
            .setNegativeButton("취소", null)
            .show();
    }

    // ── 기보 선택 BottomSheet ────────────────────────────────────────────────

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

    // ── 기보 목록 커스텀 어댑터 (하이라이트 방식 - ViewerActivity와 동일) ──────────────────────
    /**
     * Edge-to-Edge 설정, ActionBar 초기화, WindowInsets 등록을 한 번에 처리.
     */
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

    /**
     * Edge-to-Edge WindowInsets 적용 헬퍼.
     * - 상단(sys.top) → AppBarLayout 패딩
     * - 좌우(sys.left/right) → rootView 패딩
     * - 하단(sys.bottom) → landscape: contentRow 패딩, portrait: navBarSpacer 높이 조정
     */
    private void applyWindowInsets(AppBarLayout appBar, View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (appBar != null) appBar.setPadding(0, sys.top, 0, 0);
            v.setPadding(sys.left, 0, sys.right, 0);
            android.view.View contentRow   = v.getRootView().findViewById(R.id.contentRow);
            android.view.View navBarSpacer = v.getRootView().findViewById(R.id.navBarSpacer);
            if (contentRow != null) {
                // landscape 모드: 전체 콘텐츠 행에 bottom 패딩 (바둑판 + 컨트롤 모두 네비게이션 바 위로)
                contentRow.setPadding(0, 0, 0, sys.bottom);
            } else if (navBarSpacer != null) {
                // portrait 모드: board_color 배경의 스페이서 높이를 sys.bottom으로 설정
                android.view.ViewGroup.LayoutParams lp = navBarSpacer.getLayoutParams();
                lp.height = sys.bottom;
                navBarSpacer.setLayoutParams(lp);
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }

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
