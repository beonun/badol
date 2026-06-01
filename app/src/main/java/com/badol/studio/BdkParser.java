package com.badol.studio;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BDK 파일 파서
 *
 * 파일 구조:
 *   [0~11]  : 파일 헤더 (12바이트)
 *             bytes[0:2] = 버전 (0x2717 or 0x2718)
 *             bytes[4:8] = 섹션 수 (little-endian)
 *             bytes[8:12] = 플래그
 *   [12~15] : FF FF FF FF (첫 번째 섹션 구분자)
 *   각 섹션 구분자(FF FF FF FF) 이후:
 *     - 섹션0만: 추가 FF FF FF FF 4바이트
 *     - 고정 헤더 블록 48바이트
 *       - [7]       바둑판 크기 (19 = 0x13)
 *       - [8~11]    zoom x1 (0-based, little-endian)
 *       - [12~15]   zoom y1 (0-based, little-endian)
 *       - [16~19]   zoom x2 (0-based, little-endian)
 *       - [20~23]   zoom y2 (0-based, little-endian)
 *     - FF FE FF [글자수] [UTF-16LE 이름] FF FE FF 00 0A 00 00 00
 *     - 레코드들 (13바이트씩)
 *     - 패딩 (00 00 00 00)
 *     - 돌별 설명 RTF 블록들 (0개 이상)
 *       - [0]   x (0-based)
 *       - [1]   y (0-based)
 *       - [2~3] extra (seq 추정)
 *       - [4~7] RTF 크기+2 (little-endian)
 *       - [8~]  RTF 본문 {\rtf1...}
 *     - 섹션 전체 설명 RTF 블록 (x=0,y=0 이거나 좌표 무의미)
 *
 * 레코드 구조 (13바이트):
 *   [0] color   : 0=흑, 1=백
 *   [1] x       : 0-based (0~18) → +1 하면 1-based (1~19)
 *   [2] y       : 0-based (0~18) → +1 하면 1-based (1~19)
 *   [3] seq_lo  : 착수 순서 하위 바이트
 *   [4] seq_hi  : 착수 순서 상위 바이트
 *   [5~8]       : 00 00 00 00
 *   [9~12]      : FE FF FF FF  (레코드 꼬리 식별자)
 *
 * seq 규칙:
 *   - 섹션0(기본도): seq=0~999 → 모두 initialStones
 *   - 섹션1+(정해도/오답도): seq>=2000 → initialStones, seq<2000 → moves (표시번호=seq+1)
 */
public class BdkParser {

    private static final int[] SECTION_SEP = {0xFF, 0xFF, 0xFF, 0xFF};
    private static final int[] RECORD_TAIL = {0xFE, 0xFF, 0xFF, 0xFF};

    public static List<BdkSection> parse(File file) throws Exception {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(file)) {
            data = new byte[(int) file.length()];
            fis.read(data);
        }
        return parse(data);
    }

    public static List<BdkSection> parse(byte[] data) {
        List<BdkSection> sections = new ArrayList<>();
        
        // 파일 버전 확인 (0x2717 = 3.0, 0x2718 = 4.0)
        boolean isVersion4 = false;
        if (data.length >= 2) {
            int version = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
            if (version == 0x2718) {
                isVersion4 = true;
            }
        }

        // ── 1. 섹션 구분자(0xFFFFFFFF) 위치 찾기 ──────────────────────
        List<Integer> separators = new ArrayList<>();
        for (int i = 0; i < data.length - 3; i++) {
            if ((data[i]   & 0xFF) == 0xFF && (data[i+1] & 0xFF) == 0xFF
             && (data[i+2] & 0xFF) == 0xFF && (data[i+3] & 0xFF) == 0xFF) {
                separators.add(i);
            }
        }

        // 연속된 FF FF FF FF 중 첫 번째만 유효 구분자로 사용 (13바이트 이내 중복 제거)
        List<Integer> validSeps = new ArrayList<>();
        validSeps.add(-1); // 시작
        for (int sep : separators) {
            if (sep - validSeps.get(validSeps.size()-1) > 13) {
                validSeps.add(sep);
            }
        }
        validSeps.add(data.length); // 끝

        // ── 2. 각 구간에서 레코드 파싱 ────────────────────────────────
        // [offset, seq, color(1=흑,2=백), x(0-based), y(0-based), isStoneComment(0=형식A, 1=형식B)]
        List<int[]> allRecords = new ArrayList<>();
        for (int i = 0; i < data.length - 12; i++) {
            if (isValidRecord(data, i, isVersion4)) {
                int recOffset = isVersion4 ? i + 4 : i; // 4.0은 앞에 4바이트 패딩 있음
                int colorRaw = data[recOffset] & 0xFF;
                int x0 = data[recOffset+1] & 0xFF; // 0-based
                int y0 = data[recOffset+2] & 0xFF; // 0-based
                int seq = (data[recOffset+3] & 0xFF) | ((data[recOffset+4] & 0xFF) << 8);
                int color = (colorRaw == 0) ? BdkSection.BLACK : BdkSection.WHITE;
                // 형식B(돌 설명 있음) 판별: bytes[5~6]에 RTF 크기가 있으면 형식B
                int rtfSize = (data[recOffset+5] & 0xFF) | ((data[recOffset+6] & 0xFF) << 8);
                int isStoneComment = (rtfSize > 0) ? 1 : 0;
                allRecords.add(new int[]{i, seq, color, x0, y0, isStoneComment});
            }
        }

        // ── 3. 구분자 기준으로 섹션별 레코드 분류 ─────────────────────
        List<List<int[]>> sectionRecords = new ArrayList<>();
        // validSeps 구간 인덱스 → sectionRecords 인덱스 매핑
        // (-1 이면 해당 구간에 레코드 없음)
        int[] sepToSection = new int[validSeps.size() - 1];
        java.util.Arrays.fill(sepToSection, -1);
        int secCount = 0;
        for (int s = 0; s < validSeps.size() - 1; s++) {
            int sStart = validSeps.get(s);
            int sEnd   = validSeps.get(s + 1);
            List<int[]> recs = new ArrayList<>();
            for (int[] rec : allRecords) {
                if (rec[0] > sStart && rec[0] < sEnd) {
                    recs.add(rec);
                }
            }
            if (!recs.isEmpty()) {
                sepToSection[s] = secCount++;
                sectionRecords.add(recs);
            }
        }

        // ── 4. 섹션별 이름 추출 (FF FE FF [글자수] [UTF-16LE] 구조) ───
        List<String> sectionNames = extractSectionNames(data, validSeps, sectionRecords.size());

        // ── 5. 섹션별 zoom 좌표 추출 ──────────────────────────────────
        List<int[]> sectionZooms = extractSectionZooms(data, validSeps, sectionRecords.size());

        // ── 6. 섹션 객체 생성 ──────────────────────────────────────────
        String[] defaultNames = {"기본도", "정해도", "오답도1", "오답도2", "오답도3",
                                  "오답도4", "오답도5", "오답도6", "오답도7", "오답도8"};
        int nameIdx = 0;

        for (List<int[]> recs : sectionRecords) {
            BdkSection section = new BdkSection();

            // 파일에서 읽은 이름 우선, 없으면 기본값
            if (nameIdx < sectionNames.size() && !sectionNames.get(nameIdx).isEmpty()) {
                section.name = sectionNames.get(nameIdx);
            } else {
                section.name = (nameIdx < defaultNames.length)
                               ? defaultNames[nameIdx] : ("섹션" + (nameIdx + 1));
            }

            // zoom 좌표 설정
            if (nameIdx < sectionZooms.size()) {
                int[] zoom = sectionZooms.get(nameIdx);
                section.zoomX1 = zoom[0];
                section.zoomY1 = zoom[1];
                section.zoomX2 = zoom[2];
                section.zoomY2 = zoom[3];
            }

            nameIdx++;
            section.boardSize = 19;

            // seq 기준으로 정렬
            Collections.sort(recs, (a, b) -> Integer.compare(a[1], b[1]));

            // 섹션 유형 판별:
            // - 섹션0(기본도): seq가 모두 0~999 → 전부 initialStones
            // - 섹션1+(정해도/오답도): seq>=2000 → initialStones, seq 0~999 → moves
            boolean hasHighSeq = false;
            for (int[] rec : recs) {
                if (rec[1] >= 2000) { hasHighSeq = true; break; }
            }

            for (int[] rec : recs) {
                int seq   = rec[1];
                int color = rec[2]; // BLACK=1 or WHITE=2
                int x0    = rec[3]; // 0-based
                int y0    = rec[4]; // 0-based
                int x = x0 + 1;    // 1-based
                int y = y0 + 1;    // 1-based

                if (!hasHighSeq) {
                    // 기본도 섹션: seq=0~999 모두 초기 배치 돌
                    section.initialStones.add(new int[]{color, x, y, seq});
                } else if (seq >= 2000) {
                    // 정해도/오답도 섹션: 초기 배치 돌
                    section.initialStones.add(new int[]{color, x, y, seq - 2000});
                } else {
                    // 실제 착수 (seq=0부터 시작, 표시 번호는 seq+1)
                    section.moves.add(new int[]{seq + 1, color, x, y});
                }
            }

            // 초기 배치 돌이 있거나 착수가 있는 섹션만 추가
            if (!section.initialStones.isEmpty() || !section.moves.isEmpty()) {
                sections.add(section);
            }
        }

        // ── 7. RTF 주석 및 돌별 설명 추출 ─────────────────────────────
        extractComments(data, validSeps, sepToSection, sections);

        return sections;
    }

    /**
     * 각 섹션 구분자 이후 헤더 블록에서 섹션 이름을 추출한다.
     * 구조: FF FE FF [글자수(1바이트)] [UTF-16LE 이름 바이트] ...
     */
    private static List<String> extractSectionNames(byte[] data, List<Integer> validSeps, int sectionCount) {
        List<String> names = new ArrayList<>();

        for (int s = 1; s < validSeps.size() - 1 && names.size() < sectionCount; s++) {
            int sep = validSeps.get(s);
            int searchStart = sep + 4;
            int searchEnd = Math.min(searchStart + 150, data.length);

            String name = "";
            for (int i = searchStart; i < searchEnd - 2; i++) {
                if ((data[i] & 0xFF) == 0xFF && (data[i+1] & 0xFF) == 0xFE && (data[i+2] & 0xFF) == 0xFF) {
                    if (i + 3 >= data.length) break;
                    int charCount = data[i+3] & 0xFF;
                    if (charCount == 0) break;
                    int nameByteLen = charCount * 2;
                    if (i + 4 + nameByteLen > data.length) break;
                    byte[] nameBytes = new byte[nameByteLen];
                    System.arraycopy(data, i + 4, nameBytes, 0, nameByteLen);
                    try {
                        name = new String(nameBytes, "UTF-16LE");
                    } catch (Exception e) {
                        name = "";
                    }
                    break;
                }
            }
            names.add(name);
        }

        return names;
    }

    /**
     * 각 섹션 헤더 블록에서 zoom 좌표를 추출한다.
     * 헤더 블록 구조 (FF FF FF FF 구분자 이후):
     *   [0~3]  FF FF FF FF (또는 섹션0은 FF FF FF FF FF FF FF FF)
     *   이후 고정 블록:
     *     [7]      바둑판 크기 (0x13 = 19)
     *     [8~11]   zoom x1 (0-based, little-endian)
     *     [12~15]  zoom y1 (0-based, little-endian)
     *     [16~19]  zoom x2 (0-based, little-endian)
     *     [20~23]  zoom y2 (0-based, little-endian)
     *
     * zoom 값이 0이면 전체 보기 (줌 없음)
     */
    private static List<int[]> extractSectionZooms(byte[] data, List<Integer> validSeps, int sectionCount) {
        List<int[]> zooms = new ArrayList<>();

        for (int s = 1; s < validSeps.size() - 1 && zooms.size() < sectionCount; s++) {
            int sep = validSeps.get(s);
            // 연속된 FF 바이트를 모두 건너뜀 (섹션0=7바이트, 섹션1+=8바이트)
            int base = sep;
            while (base < data.length && (data[base] & 0xFF) == 0xFF) base++;
            // base는 이제 FF 연속 이후 첫 번째 비-FF 바이트
            // 헤더 블록 구조:
            //   [0~3]   boardSize (int32)
            //   [4~7]   zoomX1 (int32, 0-based)
            //   [8~11]  zoomY1 (int32, 0-based)
            //   [12~15] zoomX2 (int32, 0-based)
            //   [16~19] zoomY2 (int32, 0-based)
            int zx1 = 0, zy1 = 0, zx2 = 0, zy2 = 0;
            if (base + 20 <= data.length) {
                zx1 = readInt32LE(data, base + 4);
                zy1 = readInt32LE(data, base + 8);
                zx2 = readInt32LE(data, base + 12);
                zy2 = readInt32LE(data, base + 16);
                // 0-based → 1-based 변환, 유효 범위 확인 (0~18 범위)
                if (zx1 >= 0 && zy1 >= 0 && zx2 > zx1 && zy2 > zy1
                        && zx2 <= 18 && zy2 <= 18) {
                    zx1++; zy1++; zx2++; zy2++;
                } else {
                    zx1 = 0; zy1 = 0; zx2 = 0; zy2 = 0;
                }
            }
            zooms.add(new int[]{zx1, zy1, zx2, zy2});
        }

        return zooms;
    }

    private static int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
             | ((data[offset+1] & 0xFF) << 8)
             | ((data[offset+2] & 0xFF) << 16)
             | ((data[offset+3] & 0xFF) << 24);
    }

    /**
     * 레코드 유효성 검사 - 두 가지 형식 지원.
     *
     * 형식 A (돌 설명 없음, 13바이트 또는 17바이트):
     *   [0]color [1]x [2]y [3~4]seq [5~8]=00000000 [9~12]=FEFFFFFF
     *   4.0의 경우 앞에 4바이트 패딩이 추가됨.
     *
     * 형식 B (돌 설명 있음, 9바이트 + RTF 본문):
     *   [0]color [1]x [2]y [3~4]seq [5~6]=RTF크기(LE) [7~8]=0000 [9~]={\rtf...}
     */
    private static boolean isValidRecord(byte[] data, int offset, boolean isVersion4) {
        int recOffset = isVersion4 ? offset + 4 : offset;
        
        // 4.0 포맷이면 앞 4바이트가 00 00 00 00인지 확인
        if (isVersion4) {
            if (offset + 4 > data.length) return false;
            if (data[offset] != 0 || data[offset+1] != 0 || data[offset+2] != 0 || data[offset+3] != 0) {
                return false;
            }
        }
        
        if (recOffset + 9 > data.length) return false;
        int colorRaw = data[recOffset] & 0xFF;
        if (colorRaw != 0 && colorRaw != 1) return false;
        int x = data[recOffset+1] & 0xFF;
        int y = data[recOffset+2] & 0xFF;
        if (x > 18 || y > 18) return false;
        // bytes[7~8] 반드시 00 00 (두 형식 공통)
        if ((data[recOffset+7] & 0xFF) != 0x00) return false;
        if ((data[recOffset+8] & 0xFF) != 0x00) return false;
        // 형식 A: bytes[5~6]=00 00, bytes[9~12]=FE FF FF FF
        if ((data[recOffset+5] & 0xFF) == 0x00 && (data[recOffset+6] & 0xFF) == 0x00) {
            if (recOffset + 13 > data.length) return false;
            return (data[recOffset+9]  & 0xFF) == 0xFE
                && (data[recOffset+10] & 0xFF) == 0xFF
                && (data[recOffset+11] & 0xFF) == 0xFF
                && (data[recOffset+12] & 0xFF) == 0xFF;
        }
        // 형식 B: bytes[5~6]=RTF크기(!=0), bytes[9~]={\rtf 패턴
        int rtfSize = (data[recOffset+5] & 0xFF) | ((data[recOffset+6] & 0xFF) << 8);
        if (rtfSize > 0 && recOffset + 9 + 5 <= data.length) {
            return (data[recOffset+9]  & 0xFF) == 0x7B
                && (data[recOffset+10] & 0xFF) == 0x5C
                && (data[recOffset+11] & 0xFF) == 0x72
                && (data[recOffset+12] & 0xFF) == 0x74
                && (data[recOffset+13] & 0xFF) == 0x66;
        }
        return false;
    }

    /**
     * RTF 주석 및 돌별 설명 추출.
     * RTF 블록 앞 8바이트:
     *   [0]   x (0-based) → x=0,y=0 이면 섹션 전체 설명
     *   [1]   y (0-based)
     *   [2~3] extra (seq 추정)
     *   [4~7] RTF 크기+2 (little-endian)
     *
     * sepToSection[i] = validSeps 구간 i가 매핑되는 sections 인덱스 (-1이면 레코드 없는 구간)
     */
    private static void extractComments(byte[] data, List<Integer> seps,
                                        int[] sepToSection, List<BdkSection> sections) {
        for (int i = 0; i < data.length - 5; i++) {
            // RTF 시작 패턴: 7B 5C 72 74 66 = "{\rtf"
            if ((data[i] & 0xFF) == 0x7B && (data[i+1] & 0xFF) == 0x5C
                    && (data[i+2] & 0xFF) == 0x72 && (data[i+3] & 0xFF) == 0x74
                    && (data[i+4] & 0xFF) == 0x66) {

                // RTF 끝 위치 찾기
                int depth = 0;
                int end = i;
                for (int j = i; j < data.length; j++) {
                    if ((data[j] & 0xFF) == 0x7B) depth++;
                    else if ((data[j] & 0xFF) == 0x7D) {
                        depth--;
                        if (depth == 0) { end = j + 1; break; }
                    }
                }
                if (end <= i) continue;

                byte[] rtfBytes = new byte[end - i];
                System.arraycopy(data, i, rtfBytes, 0, rtfBytes.length);
                String text = extractTextFromRtf(rtfBytes);

                // validSeps 구간 인덱스 찾기
                int sepIdx = findSepIndex(i, seps);
                int secIdx = (sepIdx >= 0 && sepIdx < sepToSection.length)
                             ? sepToSection[sepIdx] : -1;
                if (secIdx < 0 || secIdx >= sections.size()) continue;

                BdkSection section = sections.get(secIdx);

                // RTF 앞 8바이트 헤더 확인 (돌별 설명 vs 섹션 전체 설명)
                if (i >= 8) {
                    int rx0 = data[i-8] & 0xFF; // x (0-based)
                    int ry0 = data[i-7] & 0xFF; // y (0-based)
                    // x=0, y=0 이면 섹션 전체 설명
                    if (rx0 == 0 && ry0 == 0) {
                        if (section.comment.isEmpty() && !text.isEmpty()) {
                            section.comment = text;
                        }
                    } else {
                        // 돌별 설명: 키 = "x,y" (1-based)
                        String key = (rx0 + 1) + "," + (ry0 + 1);
                        if (!section.stoneComments.containsKey(key) && !text.isEmpty()) {
                            section.stoneComments.put(key, text);
                        }
                    }
                } else {
                    // 헤더 없는 경우 섹션 전체 설명으로 처리
                    if (section.comment.isEmpty() && !text.isEmpty()) {
                        section.comment = text;
                    }
                }

                // RTF 블록 끝으로 이동
                i = end - 1;
            }
        }
    }

    /** RTF 위치가 속하는 validSeps 구간 인덱스 반환 */
    private static int findSepIndex(int offset, List<Integer> seps) {
        for (int i = 0; i < seps.size() - 1; i++) {
            if (offset > seps.get(i) && offset < seps.get(i + 1)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * RTF에서 순수 텍스트 추출
     * - fonttbl/colortbl/stylesheet 등 헤더 그룹 명시적 제외
     * - {\* ...} 파트도 제외
     * - \par, \line 제어어 줄바꿈으로 변환
     * - EUC-KR 2바이트 한글 (\'xx\'xx 패턴) 정확히 디코딩
     */
    private static final java.util.Set<String> RTF_SKIP_GROUPS;
    static {
        RTF_SKIP_GROUPS = new java.util.HashSet<>();
        RTF_SKIP_GROUPS.add("fonttbl");
        RTF_SKIP_GROUPS.add("colortbl");
        RTF_SKIP_GROUPS.add("stylesheet");
        RTF_SKIP_GROUPS.add("info");
        RTF_SKIP_GROUPS.add("pict");
        RTF_SKIP_GROUPS.add("object");
        RTF_SKIP_GROUPS.add("header");
        RTF_SKIP_GROUPS.add("footer");
        RTF_SKIP_GROUPS.add("headerl");
        RTF_SKIP_GROUPS.add("headerr");
        RTF_SKIP_GROUPS.add("footerl");
        RTF_SKIP_GROUPS.add("footerr");
    }

    public static String extractTextFromRtf(byte[] rtfBytes) {
        try {
            String rtf = new String(rtfBytes, "EUC-KR");
            StringBuilder sb = new StringBuilder();
            int i = 0;
            int n = rtf.length();
            int depth = 0;
            java.util.ArrayDeque<Integer> skipStack = new java.util.ArrayDeque<>();

            while (i < n) {
                char c = rtf.charAt(i);

                if (c == '{') {
                    depth++;
                    boolean shouldSkip = false;
                    int j = i + 1;
                    while (j < n && (rtf.charAt(j) == ' ' || rtf.charAt(j) == '\t')) j++;
                    if (j < n && rtf.charAt(j) == '\\') {
                        j++;
                        if (j < n && rtf.charAt(j) == '*') {
                            shouldSkip = true;
                        } else {
                            StringBuilder kw = new StringBuilder();
                            while (j < n && Character.isLetter(rtf.charAt(j))) kw.append(rtf.charAt(j++));
                            if (RTF_SKIP_GROUPS.contains(kw.toString())) shouldSkip = true;
                        }
                    }
                    if (shouldSkip) skipStack.push(depth);
                    i++;
                } else if (c == '}') {
                    if (!skipStack.isEmpty() && skipStack.peek() == depth) skipStack.pop();
                    depth--;
                    i++;
                } else if (c == '\\') {
                    i++;
                    if (i >= n) break;
                    char nc = rtf.charAt(i);
                    if (nc == '\'') {
                        i++;
                        if (i + 1 < n) {
                            try {
                                int b1 = Integer.parseInt(rtf.substring(i, i + 2), 16);
                                i += 2;
                                if (b1 >= 0xA1 && b1 <= 0xFE
                                        && i + 3 < n
                                        && rtf.charAt(i) == '\\'
                                        && rtf.charAt(i + 1) == '\'') {
                                    i += 2;
                                    int b2 = Integer.parseInt(rtf.substring(i, i + 2), 16);
                                    i += 2;
                                    if (skipStack.isEmpty()) {
                                        sb.append(new String(new byte[]{(byte) b1, (byte) b2}, "EUC-KR"));
                                    }
                                } else {
                                    if (skipStack.isEmpty() && b1 >= 0x20) sb.append((char) b1);
                                }
                            } catch (Exception ignored) { i += 2; }
                        }
                    } else if (nc == '\n' || nc == '\r') {
                        i++;
                    } else if (nc == '{' || nc == '}' || nc == '\\') {
                        if (skipStack.isEmpty()) sb.append(nc);
                        i++;
                    } else {
                        StringBuilder kw = new StringBuilder();
                        while (i < n && Character.isLetter(rtf.charAt(i))) kw.append(rtf.charAt(i++));
                        if (i < n && (rtf.charAt(i) == '-' || Character.isDigit(rtf.charAt(i)))) {
                            while (i < n && (rtf.charAt(i) == '-' || Character.isDigit(rtf.charAt(i)))) i++;
                        }
                        if (i < n && rtf.charAt(i) == ' ') i++;
                        String kwStr = kw.toString();
                        if ((kwStr.equals("par") || kwStr.equals("line")) && skipStack.isEmpty()) {
                            sb.append('\n');
                        }
                    }
                } else if (c == '\n' || c == '\r') {
                    i++;
                } else {
                    if (skipStack.isEmpty() && c >= ' ') sb.append(c);
                    i++;
                }
            }

            String result = sb.toString();
            result = result.replaceAll("\\n{3,}", "\n\n");
            result = result.replaceAll("[ \\t]+", " ");
            return result.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
