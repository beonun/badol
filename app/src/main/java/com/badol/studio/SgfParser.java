package com.badol.studio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SGF(Smart Game Format) 파서
 *
 * ── SGF → BdkSection 매핑 규칙 ─────────────────────────────────────────────
 *
 * SGF 구조:
 *   (;GM[1]SZ[19]  ← 루트 노드 (게임 정보 + 기본도 AB/AW)
 *     (;B[pd]...   ← 첫 번째 분기 = 정해도 (sections[1])
 *       (;B[qd]... ← 중첩 분기 = 변화도 (sections[2], sections[3], ...)
 *     )
 *     (;W[qd]...   ← 두 번째 분기 = 변화도
 *     )
 *   )
 *
 * BdkSection 매핑:
 *   sections[0] = 기본도  (루트 노드의 AB/AW)
 *   sections[1] = 정해도  (첫 번째 분기의 착수 목록)
 *   sections[2+] = 변화도 (나머지 분기들, DFS 순서)
 *
 * 좌표 변환:
 *   SGF: 'a'=1, 'b'=2, ..., 's'=19  (소문자 알파벳)
 *   BdkSection: x,y 1-based 정수
 *
 * 지원 속성:
 *   GM, SZ, AB, AW, B, W, C (주석), LB (레이블/돌 설명), GN (게임 이름)
 *   VW (뷰 영역 = zoomX1/Y1/X2/Y2)
 */
public class SgfParser {

    public static List<BdkSection> parse(File file) throws IOException {
        byte[] bytes = readFile(file);
        String text = detectAndDecode(bytes);
        return parseSgfText(text);
    }

    public static List<BdkSection> parse(InputStream is) throws IOException {
        byte[] bytes = readStream(is);
        String text = detectAndDecode(bytes);
        return parseSgfText(text);
    }

    // ── 파일/스트림 읽기 ──────────────────────────────

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static byte[] readStream(InputStream is) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** UTF-8 BOM 또는 CA 속성으로 인코딩 감지 후 디코딩 */
    private static String detectAndDecode(byte[] bytes) {
        // UTF-8 BOM 확인
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        // 기본 UTF-8 시도
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            // CA 속성에서 인코딩 추출
            String ca = extractCA(s);
            if (ca != null && !ca.equalsIgnoreCase("UTF-8") && !ca.equalsIgnoreCase("UTF8")) {
                try {
                    return new String(bytes, ca);
                } catch (Exception ignored) {}
            }
            return s;
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** SGF 텍스트에서 CA 속성값 추출 */
    private static String extractCA(String text) {
        int idx = text.indexOf("CA[");
        if (idx < 0) return null;
        int start = idx + 3;
        int end = text.indexOf(']', start);
        if (end < 0) return null;
        return text.substring(start, end).trim();
    }

    // ── SGF 토크나이저 ────────────────────────────────

    private static final int TOK_LPAREN  = 1;  // (
    private static final int TOK_RPAREN  = 2;  // )
    private static final int TOK_SEMI    = 3;  // ;
    private static final int TOK_PROP_ID = 4;  // 속성 식별자 (대문자)
    private static final int TOK_VALUE   = 5;  // 속성 값 [...]
    private static final int TOK_EOF     = 6;

    private String src;
    private int pos;

    private int  tokType;
    private String tokVal;

    private SgfParser(String text) {
        this.src = text;
        this.pos = 0;
        advance();
    }

    private void advance() {
        skipWhitespace();
        if (pos >= src.length()) { tokType = TOK_EOF; tokVal = ""; return; }
        char c = src.charAt(pos);
        if (c == '(') { tokType = TOK_LPAREN;  tokVal = "("; pos++; }
        else if (c == ')') { tokType = TOK_RPAREN; tokVal = ")"; pos++; }
        else if (c == ';') { tokType = TOK_SEMI;   tokVal = ";"; pos++; }
        else if (c == '[') { tokType = TOK_VALUE;  tokVal = readValue(); }
        else if (Character.isUpperCase(c)) { tokType = TOK_PROP_ID; tokVal = readPropId(); }
        else { pos++; advance(); } // 알 수 없는 문자 건너뜀
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private String readPropId() {
        int start = pos;
        while (pos < src.length() && Character.isUpperCase(src.charAt(pos))) pos++;
        return src.substring(start, pos);
    }

    /** '[' 이후 ']'까지 읽기 (이스케이프 '\]' 처리) */
    private String readValue() {
        pos++; // skip '['
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos < src.length()) {
                    char esc = src.charAt(pos);
                    if (esc == '\n') { pos++; continue; } // soft line break
                    if (esc == '\r') {
                        pos++;
                        if (pos < src.length() && src.charAt(pos) == '\n') pos++;
                        continue;
                    }
                    sb.append(esc);
                    pos++;
                }
            } else if (c == ']') {
                pos++;
                break;
            } else {
                sb.append(c);
                pos++;
            }
        }
        return sb.toString();
    }

    // ── SGF 트리 파싱 ─────────────────────────────────

    /** SGF 노드: 속성 맵 */
    private static class SgfNode {
        Map<String, List<String>> props = new LinkedHashMap<>();
        void addProp(String key, String val) {
            props.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
        }
        List<String> get(String key) {
            return props.getOrDefault(key, Collections.emptyList());
        }
        String getFirst(String key) {
            List<String> v = props.get(key);
            return (v != null && !v.isEmpty()) ? v.get(0) : null;
        }
    }

    /** SGF 게임 트리: 노드 목록 + 자식 트리 목록 */
    private static class SgfTree {
        List<SgfNode> nodes = new ArrayList<>();
        List<SgfTree> children = new ArrayList<>();
    }

    /** 최상위 파싱: '(' 로 시작하는 게임 트리 파싱 */
    private SgfTree parseTree() {
        if (tokType != TOK_LPAREN) return null;
        advance(); // skip '('
        SgfTree tree = new SgfTree();
        // 노드 파싱
        while (tokType == TOK_SEMI) {
            advance(); // skip ';'
            SgfNode node = new SgfNode();
            // 속성 파싱
            while (tokType == TOK_PROP_ID) {
                String key = tokVal;
                advance();
                while (tokType == TOK_VALUE) {
                    node.addProp(key, tokVal);
                    advance();
                }
            }
            tree.nodes.add(node);
        }
        // 자식 트리 파싱
        while (tokType == TOK_LPAREN) {
            SgfTree child = parseTree();
            if (child != null) tree.children.add(child);
        }
        if (tokType == TOK_RPAREN) advance(); // skip ')'
        return tree;
    }

    // ── SGF → BdkSection 변환 ─────────────────────────

    private static List<BdkSection> parseSgfText(String text) {
        SgfParser p = new SgfParser(text);
        // 최상위 '(' 찾기
        while (p.tokType != TOK_LPAREN && p.tokType != TOK_EOF) p.advance();
        SgfTree root = p.parseTree();
        if (root == null || root.nodes.isEmpty()) return Collections.emptyList();

        List<BdkSection> sections = new ArrayList<>();

        // sections[0] = 기본도
        BdkSection base = new BdkSection();
        SgfNode rootNode = root.nodes.get(0);

        // 보드 크기
        String sz = rootNode.getFirst("SZ");
        if (sz != null) {
            try { base.boardSize = Integer.parseInt(sz.trim()); } catch (Exception ignored) {}
        }

        // 게임 이름 → 기본도 이름
        String gn = rootNode.getFirst("GN");
        base.name = (gn != null && !gn.isEmpty()) ? gn : "기본도";

        // AB/AW → initialStones
        int seq = 0;
        for (String coord : rootNode.get("AB")) {
            int[] xy = sgfCoordToXY(coord);
            if (xy != null) base.initialStones.add(new int[]{BdkSection.BLACK, xy[0], xy[1], seq++});
        }
        for (String coord : rootNode.get("AW")) {
            int[] xy = sgfCoordToXY(coord);
            if (xy != null) base.initialStones.add(new int[]{BdkSection.WHITE, xy[0], xy[1], seq++});
        }
        // seq 기준 정렬
        base.initialStones.sort(Comparator.comparingInt(a -> a[3]));

        // 루트 노드 주석
        String rootComment = rootNode.getFirst("C");
        if (rootComment != null) base.comment = rootComment;

        // VW (뷰 영역) → 기본도 줌
        String vw = rootNode.getFirst("VW");
        if (vw != null && !vw.isEmpty()) applyVW(base, vw);

        // LB (레이블) → stoneComments
        for (String lb : rootNode.get("LB")) {
            applyLB(base, lb);
        }

        sections.add(base);

        // 루트 노드 이후 노드들 (루트 트리의 나머지 노드) → 정해도에 포함
        // 분기 없이 루트 트리에 노드가 여러 개인 경우 처리
        if (root.nodes.size() > 1 || !root.children.isEmpty()) {
            // 첫 번째 분기(또는 루트 연속 노드)를 정해도로 처리
            // DFS로 모든 분기를 섹션으로 변환
            collectSections(root, base, sections, 1);
        }

        return sections;
    }

    /**
     * SGF 트리를 DFS로 순회하여 각 분기를 BdkSection으로 변환
     *
     * @param tree      현재 트리
     * @param base      기본도 섹션 (initialStones 참조용)
     * @param sections  결과 목록
     * @param nodeStart 이 트리에서 착수를 시작할 노드 인덱스 (루트 트리는 1, 나머지는 0)
     */
    private static void collectSections(SgfTree tree, BdkSection base,
                                        List<BdkSection> sections, int nodeStart) {
        // 현재 트리의 노드들에서 착수 추출
        List<int[]> moves = new ArrayList<>();
        String sectionComment = null;
        Map<String, String> stoneComments = new LinkedHashMap<>();
        int[] vwCoords = null;
        String sectionName = null;

        for (int i = nodeStart; i < tree.nodes.size(); i++) {
            SgfNode node = tree.nodes.get(i);
            // B/W 착수
            String bCoord = node.getFirst("B");
            String wCoord = node.getFirst("W");
            if (bCoord != null) {
                int[] xy = sgfCoordToXY(bCoord);
                if (xy != null) moves.add(new int[]{moves.size() + 1, BdkSection.BLACK, xy[0], xy[1]});
            } else if (wCoord != null) {
                int[] xy = sgfCoordToXY(wCoord);
                if (xy != null) moves.add(new int[]{moves.size() + 1, BdkSection.WHITE, xy[0], xy[1]});
            }
            // 주석
            String c = node.getFirst("C");
            if (c != null && !c.isEmpty()) {
                if (sectionComment == null) sectionComment = c;
                else sectionComment += "\n" + c;
            }
            // LB
            for (String lb : node.get("LB")) {
                parseLB(lb, stoneComments);
            }
            // VW
            String vw = node.getFirst("VW");
            if (vw != null && !vw.isEmpty()) vwCoords = parseVW(vw);
            // GN
            String gn = node.getFirst("GN");
            if (gn != null && !gn.isEmpty()) sectionName = gn;
        }

        // 자식 분기가 없거나, 현재 트리에 착수가 있으면 섹션 생성
        boolean hasMoves = !moves.isEmpty();
        boolean hasChildren = !tree.children.isEmpty();

        if (hasMoves || (nodeStart < tree.nodes.size())) {
            // 섹션 이름 결정
            String name;
            if (sectionName != null) {
                name = sectionName;
            } else {
                int idx = sections.size();
                String[] names = {"정해도", "변화도1", "변화도2", "변화도3", "변화도4",
                                  "변화도5", "변화도6", "변화도7", "변화도8"};
                name = (idx < names.length) ? names[idx - 1] : ("변화도" + (idx - 1));
            }

            BdkSection sec = new BdkSection();
            sec.name = name;
            sec.boardSize = base.boardSize;
            sec.initialStones.addAll(base.initialStones);
            sec.moves.addAll(moves);
            if (sectionComment != null) sec.comment = sectionComment;
            sec.stoneComments.putAll(stoneComments);
            if (vwCoords != null) {
                sec.zoomX1 = vwCoords[0]; sec.zoomY1 = vwCoords[1];
                sec.zoomX2 = vwCoords[2]; sec.zoomY2 = vwCoords[3];
            }
            sections.add(sec);
        }

        // 자식 분기 처리: 각 자식은 현재 트리의 착수를 이어받아 새 섹션
        for (SgfTree child : tree.children) {
            collectChildSection(child, base, moves, sections);
        }
    }

    /**
     * 자식 분기를 섹션으로 변환 (부모 착수를 이어받음)
     */
    private static void collectChildSection(SgfTree tree, BdkSection base,
                                            List<int[]> parentMoves,
                                            List<BdkSection> sections) {
        List<int[]> moves = new ArrayList<>(parentMoves);
        String sectionComment = null;
        Map<String, String> stoneComments = new LinkedHashMap<>();
        int[] vwCoords = null;
        String sectionName = null;

        for (SgfNode node : tree.nodes) {
            String bCoord = node.getFirst("B");
            String wCoord = node.getFirst("W");
            if (bCoord != null) {
                int[] xy = sgfCoordToXY(bCoord);
                if (xy != null) moves.add(new int[]{moves.size() + 1, BdkSection.BLACK, xy[0], xy[1]});
            } else if (wCoord != null) {
                int[] xy = sgfCoordToXY(wCoord);
                if (xy != null) moves.add(new int[]{moves.size() + 1, BdkSection.WHITE, xy[0], xy[1]});
            }
            String c = node.getFirst("C");
            if (c != null && !c.isEmpty()) {
                if (sectionComment == null) sectionComment = c;
                else sectionComment += "\n" + c;
            }
            for (String lb : node.get("LB")) parseLB(lb, stoneComments);
            String vw = node.getFirst("VW");
            if (vw != null && !vw.isEmpty()) vwCoords = parseVW(vw);
            String gn = node.getFirst("GN");
            if (gn != null && !gn.isEmpty()) sectionName = gn;
        }

        // 섹션 이름 결정
        String name;
        if (sectionName != null) {
            name = sectionName;
        } else {
            int idx = sections.size();
            String[] names = {"정해도", "변화도1", "변화도2", "변화도3", "변화도4",
                              "변화도5", "변화도6", "변화도7", "변화도8"};
            name = (idx < names.length) ? names[idx - 1] : ("변화도" + (idx - 1));
        }

        BdkSection sec = new BdkSection();
        sec.name = name;
        sec.boardSize = base.boardSize;
        sec.initialStones.addAll(base.initialStones);
        sec.moves.addAll(moves);
        if (sectionComment != null) sec.comment = sectionComment;
        sec.stoneComments.putAll(stoneComments);
        if (vwCoords != null) {
            sec.zoomX1 = vwCoords[0]; sec.zoomY1 = vwCoords[1];
            sec.zoomX2 = vwCoords[2]; sec.zoomY2 = vwCoords[3];
        }
        sections.add(sec);

        // 자식의 자식 처리
        for (SgfTree child : tree.children) {
            collectChildSection(child, base, moves, sections);
        }
    }

    // ── 좌표 변환 ─────────────────────────────────────

    /** SGF 좌표 문자열 → [x, y] (1-based) */
    private static int[] sgfCoordToXY(String coord) {
        if (coord == null || coord.length() < 2) return null;
        coord = coord.trim();
        if (coord.isEmpty() || coord.equals("tt")) return null; // 패스
        char cx = coord.charAt(0);
        char cy = coord.charAt(1);
        int x = sgfCharToInt(cx);
        int y = sgfCharToInt(cy);
        if (x < 1 || y < 1 || x > 19 || y > 19) return null;
        return new int[]{x, y};
    }

    private static int sgfCharToInt(char c) {
        if (c >= 'a' && c <= 's') return c - 'a' + 1;
        if (c >= 'A' && c <= 'S') return c - 'A' + 1; // 일부 SGF는 대문자 사용
        return -1;
    }

    // ── LB (레이블) 처리 ──────────────────────────────

    /** LB 속성값 파싱: "pd:설명" → stoneComments["x,y"] = "설명" */
    private static void parseLB(String lb, Map<String, String> stoneComments) {
        int colon = lb.indexOf(':');
        if (colon < 0) return;
        String coord = lb.substring(0, colon).trim();
        String label = lb.substring(colon + 1).trim();
        int[] xy = sgfCoordToXY(coord);
        if (xy != null) stoneComments.put(xy[0] + "," + xy[1], label);
    }

    private static void applyLB(BdkSection sec, String lb) {
        parseLB(lb, sec.stoneComments);
    }

    // ── VW (뷰 영역) 처리 ────────────────────────────

    /**
     * VW 속성값 파싱: "pd:sg" → [x1, y1, x2, y2]
     * SGF VW는 "좌상단:우하단" 형식
     */
    private static int[] parseVW(String vw) {
        if (vw == null || vw.isEmpty()) return null;
        int colon = vw.indexOf(':');
        if (colon < 0) {
            // 단일 좌표인 경우 무시
            return null;
        }
        int[] xy1 = sgfCoordToXY(vw.substring(0, colon).trim());
        int[] xy2 = sgfCoordToXY(vw.substring(colon + 1).trim());
        if (xy1 == null || xy2 == null) return null;
        return new int[]{
            Math.min(xy1[0], xy2[0]), Math.min(xy1[1], xy2[1]),
            Math.max(xy1[0], xy2[0]), Math.max(xy1[1], xy2[1])
        };
    }

    private static void applyVW(BdkSection sec, String vw) {
        int[] coords = parseVW(vw);
        if (coords != null) {
            sec.zoomX1 = coords[0]; sec.zoomY1 = coords[1];
            sec.zoomX2 = coords[2]; sec.zoomY2 = coords[3];
        }
    }
}
