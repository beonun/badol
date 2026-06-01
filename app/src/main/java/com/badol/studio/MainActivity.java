package com.badol.studio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 메인 화면 - BDK 파일 목록 표시 및 파일 선택
 *
 * 스캔 결과는 앱 내부 저장소(scan_cache.json)에 캐시됩니다.
 * - 앱 실행 시 캐시가 있으면 즉시 표시 (스캔 생략)
 * - 캐시가 없으면 자동 스캔 후 저장
 * - '파일 스캔' 버튼은 항상 재스캔 후 캐시 갱신
 */
public class MainActivity extends AppCompatActivity {

    private static final int    REQ_PERMISSION = 100;
    private static final String CACHE_FILE     = "scan_cache.json";

    private ListView      listView;
    private TextView      tvEmpty;
    private TextView      tvCount;
    private TextView      tvLastScan;
    private LinearLayout  layoutSearch;
    private LinearLayout  layoutScanHeader;
    private EditText      etFilter;
    private ImageButton   btnClearFilter;

    /** 스캔으로 찾은 전체 파일 목록 (정렬 완료) */
    private List<File> allFiles = new ArrayList<>();
    /** 현재 ListView에 표시 중인 파일 목록 (필터 적용) */
    private List<File> fileList = new ArrayList<>();

    private ArrayAdapter<String> adapter;

    // 파일 선택기 런처 (EXTRA_INITIAL_URI로 /sdcard/Bdk/ 기본 경로 설정)
    private final ActivityResultLauncher<Intent> filePicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) openViewer(uri.toString(), null);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 스마트폰(sw < 600dp)에서는 세로 방향 고정
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        setContentView(R.layout.activity_main);

                // Android 15+ Edge-to-Edge: 시스템 바 영역 처리
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(false); // 흰색 아이콘 유지
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            // 커스텀 TextView로 가운데 정렬 타이틀 사용 → 기본 타이틀 숨김
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        // 상단(상태표시줄) → AppBarLayout 패딩, 하단(네비게이션 바) → bottomPadding View 높이 조정
        com.google.android.material.appbar.AppBarLayout appBar = findViewById(R.id.appBarLayout);
        android.view.View rootView = findViewById(android.R.id.content);
        android.view.View bottomPadding = findViewById(R.id.bottomPadding);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (appBar != null) appBar.setPadding(0, sys.top, 0, 0);
            v.setPadding(sys.left, 0, sys.right, 0);
            if (bottomPadding != null) {
                android.view.ViewGroup.LayoutParams lp = bottomPadding.getLayoutParams();
                lp.height = sys.bottom;
                bottomPadding.setLayoutParams(lp);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        listView       = findViewById(R.id.listView);
        tvEmpty        = findViewById(R.id.tvEmpty);
        tvCount        = findViewById(R.id.tvCount);
        tvLastScan     = findViewById(R.id.tvLastScan);
        layoutSearch   = findViewById(R.id.layoutSearch);
        etFilter       = findViewById(R.id.etFilter);
        btnClearFilter    = findViewById(R.id.btnClearFilter);
        layoutScanHeader  = findViewById(R.id.layoutScanHeader);

        Button btnOpen = findViewById(R.id.btnOpenFile);
        Button btnScan = findViewById(R.id.btnScan);
        Button btnNewEditor = findViewById(R.id.btnNewEditor);

        // 파일 열기 버튼 - /sdcard/Bdk/ 를 기본 경로로 표시
        btnOpen.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            // /sdcard/Bdk/ 기본 경로 설정 (제조사에 따라 지원 여부 다름)
            try {
                File bdkDir = new File(Environment.getExternalStorageDirectory(), "바돌");
                if (!bdkDir.exists()) bdkDir.mkdirs();
                Uri initialUri = Uri.fromFile(bdkDir);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            } catch (Exception ignored) {}
            filePicker.launch(intent);
        });

        // 새 기보 편집 버튼
        btnNewEditor.setOnClickListener(v -> openEditor(null, null));

        // 파일 스캔 버튼 - 항상 재스캔
        btnScan.setOnClickListener(v -> {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("SGF 파일 검색 중...");
            layoutSearch.setVisibility(View.GONE);
            etFilter.setText("");
            requestPermissionsAndScan();
        });

        // 목록 클릭
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < fileList.size())
                openViewer(null, fileList.get(position).getAbsolutePath());
        });

        // 필터 입력 감지
        etFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();
                btnClearFilter.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter(query);
            }
        });

        // 필터 지우기 버튼
        btnClearFilter.setOnClickListener(v -> etFilter.setText(""));

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);

        // /sdcard/Bdk/ 폴더 자동 생성 (없을 경우)
        try {
            File bdkDir = new File(Environment.getExternalStorageDirectory(), "바돌");
            if (!bdkDir.exists()) bdkDir.mkdirs();
        } catch (Exception ignored) {}

        // 앱 시작: 캐시 있으면 즉시 표시, 없으면 자동 스캔
        if (hasScanCache()) {
            loadFromCache();
        } else {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("SGF 파일 검색 중...");
            requestPermissionsAndScan();
        }
    }

    // ── 캐시 관련 ─────────────────────────────────────

    private File cacheFile() {
        return new File(getFilesDir(), CACHE_FILE);
    }

    private boolean hasScanCache() {
        return cacheFile().exists();
    }

    /** JSON 캐시에서 파일 목록 로드 */
    private void loadFromCache() {
        new Thread(() -> {
            List<File> loaded = new ArrayList<>();
            String scannedAt = "";
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(cacheFile()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                scannedAt = json.optString("scanned_at", "");
                JSONArray arr = json.getJSONArray("files");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    File f = new File(obj.getString("path"));
                    if (f.exists()) loaded.add(f);
                }
            } catch (Exception e) {
                // 캐시 손상 시 재스캔
            }

            final List<File> result = loaded;
            final String scanTime  = scannedAt;

            runOnUiThread(() -> {
                if (result.isEmpty()) {
                    // 캐시는 있지만 파일이 모두 사라진 경우 → 재스캔
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("SGF 파일 검색 중...");
                    requestPermissionsAndScan();
                    return;
                }
                allFiles = result;
                etFilter.setText("");
                applyFilter("");
                updateLastScanLabel(scanTime);
                layoutSearch.setVisibility(View.VISIBLE);
                if (layoutScanHeader != null) layoutScanHeader.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    /** 스캔 결과를 JSON 파일로 저장 */
    private void saveScanCache(List<File> files) {
        new Thread(() -> {
            try {
                String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
                JSONObject json = new JSONObject();
                json.put("scanned_at", now);
                JSONArray arr = new JSONArray();
                for (File f : files) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", f.getName());
                    obj.put("path", f.getAbsolutePath());
                    arr.put(obj);
                }
                json.put("files", arr);
                try (FileWriter fw = new FileWriter(cacheFile())) {
                    fw.write(json.toString());
                }
            } catch (Exception e) {
                // 저장 실패는 무시 (다음 실행 시 재스캔)
            }
        }).start();
    }

    private void updateLastScanLabel(String scanTime) {
        if (tvLastScan == null) return;
        if (scanTime != null && !scanTime.isEmpty()) {
            tvLastScan.setText("마지막 스캔: " + scanTime);
            tvLastScan.setVisibility(View.VISIBLE);
        } else {
            tvLastScan.setVisibility(View.GONE);
        }
    }

    // ── 필터 ─────────────────────────────────────────

    /** 필터 문자열로 allFiles를 걸러 ListView 갱신 */
    private void applyFilter(String query) {
        fileList = new ArrayList<>();
        if (query.isEmpty()) {
            fileList.addAll(allFiles);
        } else {
            String lower = query.toLowerCase();
            for (File f : allFiles) {
                if (f.getName().toLowerCase().contains(lower)) fileList.add(f);
            }
        }
        List<String> names = new ArrayList<>();
        for (File f : fileList) names.add(f.getName() + "\n" + abbreviatePath(f.getParent()));
        adapter.clear();
        adapter.addAll(names);
        adapter.notifyDataSetChanged();

        if (allFiles.isEmpty()) {
            tvCount.setText("");
        } else if (query.isEmpty()) {
            tvCount.setText(allFiles.size() + "개");
        } else {
            tvCount.setText(fileList.size() + " / " + allFiles.size() + "개");
        }

        if (fileList.isEmpty() && !allFiles.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("'" + query + "' 에 해당하는 파일이 없습니다.");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    // ── 권한 및 스캔 ──────────────────────────────────

    private void requestPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    scanFiles();
                }
            } else {
                scanFiles();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
            } else {
                scanFiles();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            scanFiles();
        }
    }

    // ── 스캔 ─────────────────────────────────────────

    /**
     * 파일 스캔 개선 전략:
     * 1. 우선 탐색 경로(Download, Documents, 바둑 등)를 먼저 검색 → 빠른 초기 결과 표시
     * 2. 파일 발견 시 5개 단위로 실시간 UI 업데이트 → 체감 속도 향상
     * 3. 탐색 깊이 최대 6단계 제한 → 불필요한 시스템 폴더 탐색 방지
     */
    private static final int MAX_DEPTH        = 6;   // 최대 탐색 깊이
    private static final int BATCH_SIZE       = 5;   // 실시간 표시 단위
    private static final String[] PRIORITY_DIRS = {   // 우선 탐색 폴더명
        "Download", "Downloads", "Documents", "바둑", "바돌", "bdk",
        "기보", "baduk", "Baduk", "go", "Go"
    };

    private void scanFiles() {
        new Thread(() -> {
            List<File> found    = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            List<File> roots    = new ArrayList<>();

            // 1. 외부 저장소 루트 (API 29+ 권장: getExternalFilesDirs 상위 경로 활용)
            File[] primaryDirs = getExternalFilesDirs(null);
            if (primaryDirs != null && primaryDirs.length > 0 && primaryDirs[0] != null) {
                File root = primaryDirs[0];
                for (int i = 0; i < 4; i++) { if (root.getParentFile() != null) root = root.getParentFile(); }
                roots.add(root);
            }

            // 2. /storage/ 하위 모든 볼륨 (외장 SD 카드 포함)
            File storageDir = new File("/storage");
            if (storageDir.exists() && storageDir.isDirectory()) {
                File[] volumes = storageDir.listFiles();
                if (volumes != null) {
                    for (File vol : volumes) {
                        String name = vol.getName();
                        if (!name.equals("emulated") && !name.equals("self") && vol.isDirectory())
                            roots.add(vol);
                    }
                }
            }

            // 3. getExternalFilesDirs 상위 루트
            File[] extDirs = getExternalFilesDirs(null);
            if (extDirs != null) {
                for (File d : extDirs) {
                    if (d == null) continue;
                    File root = d;
                    for (int i = 0; i < 4; i++) {
                        File parent = root.getParentFile();
                        if (parent == null) break;
                        root = parent;
                    }
                    roots.add(root);
                }
            }

            // ── Phase 1: 우선 탐색 경로 먼저 검색 ──
            for (File root : roots) {
                if (root == null || !root.exists()) continue;
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (!child.isDirectory()) continue;
                    for (String pDir : PRIORITY_DIRS) {
                        if (child.getName().equalsIgnoreCase(pDir)) {
                            try {
                                String canonical = child.getCanonicalPath();
                                if (!visited.contains(canonical)) {
                                    visited.add(canonical);
                                    searchBdk(child, found, visited, 1, this::onBatchFound);
                                }
                            } catch (Exception ignored) {}
                            break;
                        }
                    }
                }
            }

            // ── Phase 2: 전체 저장소 탐색 (깊이 제한 적용) ──
            for (File root : roots) {
                if (root == null || !root.exists()) continue;
                try {
                    String canonical = root.getCanonicalPath();
                    if (visited.contains(canonical)) continue;
                    visited.add(canonical);
                    searchBdk(root, found, visited, 0, this::onBatchFound);
                } catch (Exception ignored) {}
            }

            // 앱 내부 파일 디렉토리
            try {
                String canonical = getFilesDir().getCanonicalPath();
                if (!visited.contains(canonical)) {
                    visited.add(canonical);
                    searchBdk(getFilesDir(), found, visited, 0, this::onBatchFound);
                }
            } catch (Exception ignored) {}

            // 최종 정렬 및 캐시 저장
            Collections.sort(found, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            saveScanCache(found);

            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());

            runOnUiThread(() -> {
                allFiles = found;
                applyFilter(etFilter.getText().toString().trim());
                updateLastScanLabel(now);
                if (found.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("SGF 파일을 찾을 수 없습니다.\n'파일 열기' 버튼을 사용하세요.");
                    layoutSearch.setVisibility(View.GONE);
                    if (layoutScanHeader != null) layoutScanHeader.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    layoutSearch.setVisibility(View.VISIBLE);
                    if (layoutScanHeader != null) layoutScanHeader.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    /** 배치 단위로 실시간 UI 업데이트 (방법 1: 중간 결과 즉시 표시) */
    private void onBatchFound(List<File> batch) {
        runOnUiThread(() -> {
            allFiles.addAll(batch);
            // 현재 필터 유지하면서 새 파일만 추가
            String query = etFilter.getText().toString().trim().toLowerCase();
            for (File f : batch) {
                if (query.isEmpty() || f.getName().toLowerCase().contains(query)) {
                    fileList.add(f);
                    adapter.add(f.getName() + "\n" + abbreviatePath(f.getParent()));
                }
            }
            adapter.notifyDataSetChanged();
            tvCount.setText(allFiles.size() + "개 (검색 중...)");
            tvEmpty.setVisibility(View.GONE);
            layoutSearch.setVisibility(View.VISIBLE);
            if (layoutScanHeader != null) layoutScanHeader.setVisibility(View.VISIBLE);
        });
    }

    /** 콜백 인터페이스 */
    interface BatchCallback { void onBatch(List<File> batch); }

    /**
     * SGF/BDK 파일 재귀 탐색
     * @param depth   현재 탐색 깊이 (MAX_DEPTH 초과 시 중단)
     * @param callback BATCH_SIZE개 발견 시마다 호출
     */
    private void searchBdk(File dir, List<File> result, Set<String> visited,
                           int depth, BatchCallback callback) {
        if (dir == null || !dir.isDirectory() || depth > MAX_DEPTH) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        List<File> batch = new ArrayList<>();
        for (File f : files) {
            String fn = f.getName().toLowerCase();
            if (f.isFile() && (fn.endsWith(".sgf") || fn.endsWith(".bdk"))) {
                result.add(f);
                batch.add(f);
                if (batch.size() >= BATCH_SIZE) {
                    callback.onBatch(new ArrayList<>(batch));
                    batch.clear();
                }
            } else if (f.isDirectory() && !f.getName().startsWith(".")) {
                try {
                    String canonical = f.getCanonicalPath();
                    if (!visited.contains(canonical)) {
                        visited.add(canonical);
                        searchBdk(f, result, visited, depth + 1, callback);
                    }
                } catch (Exception ignored) {}
            }
        }
        // 남은 배치 처리
        if (!batch.isEmpty()) callback.onBatch(batch);
    }

    // ── 경로 축약 ──────────────────────────────────────

    /**
     * 긴 절대 경로를 상위 2개 폴더명만 표시하도록 축약합니다.
     * 예: /storage/9C33-6BBD/Download/0001-0100 → Download/0001-0100
     */
    private String abbreviatePath(String path) {
        if (path == null) return "";
        String[] parts = path.split("/");
        if (parts.length <= 2) return path;
        // 마지막 2개 폴더명만 사용
        String p1 = parts[parts.length - 2];
        String p2 = parts[parts.length - 1];
        // 내부 저장소 루트인 경우 "내부 저장소" 표시
        if (p1.equals("0") || p1.equalsIgnoreCase("emulated")) return "내부 저장소/" + p2;
        if (p2.equals("0") || p2.equalsIgnoreCase("emulated")) return "내부 저장소";
        return p1 + "/" + p2;
    }

    // ── 뷰어 실행 ─────────────────────────────────────

    private void openViewer(String uriStr, String path) {
        Intent intent = new Intent(this, ViewerActivity.class);
        if (path != null) intent.putExtra(ViewerActivity.EXTRA_FILE_PATH, path);
        else              intent.putExtra(ViewerActivity.EXTRA_FILE_URI,  uriStr);
        startActivity(intent);
    }

    // ── 편집기 실행 ───────────────────────────────────

    private void openEditor(String uriStr, String path) {
        Intent intent = new Intent(this, EditorActivity.class);
        if (path != null)   intent.putExtra(EditorActivity.EXTRA_FILE_PATH, path);
        else if (uriStr != null) intent.putExtra(EditorActivity.EXTRA_FILE_URI, uriStr);
        startActivity(intent);
    }
}
