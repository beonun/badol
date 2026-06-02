package com.badol.studio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SGF(Smart Game Format) 파서
 *
 * ── SGF → BdkSection 매핑 규칙 ─────────────────────────────────────────────
 *
 * SGF는 두 가지 주요 형식이 있습니다:
 *
 * [형식 A] 메인 라인 + 변화도 (GoKifu, KGS 등 일반 기보)
 *   각 착수마다 자식 분기로 표현. 첫 번째 자식 = 실제 진행, 나머지 = 변화도
 *   (;FF[4]...;B[pd]
 *     (;W[dd]          ← 실제 진행 (첫 번째 자식)
 *       (;B[qp]...)
 *       (;B[변화]...)  ← 변화도
 *     )
 *     (;W[변화]...)    ← 변화도
 *   )
 *
 * [형식 B] 문제집 형식 (루트에 AB/AW 기본도, 자식 분기들이 정해도/변화도)
 *   (;FF[4]...AB[pd]AW[dd]
 *     (;B[정해]...)
 *     (;B[오답]...)
 *   )
 *
 * BdkSection 매핑:
 *   sections[0] = 기본도  (루트 노드의 AB/AW, 또는 메인 라인 기보의 빈 기본도)
 *   sections[1] = 정해도  (메인 라인의 전체 착수, 또는 첫 번째 자식 분기)
 *   sections[2+] = 변화도 (각 분기점의 대안 변화도들, 최대 MAX_VARIATIONS개)
 *
 * 좌표 변환:
 *   SGF: 'a'=1, 'b'=2, ..., 's'=19  (소문자 알파벳)
 *   BdkSection: x,y 1-based 정수
 */
public class SgfParser {

    /** 최대 변화도 수 (너무 많으면 앱이 느려짐) */
    private static final int MAX_VARIATIONS = 20;

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
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF
                && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            String ca = extractCA(s);
            if (ca != null && !ca.equalsIgnoreCase("UTF-8") && !ca.equalsIgnoreCase("UTF8")) {
                try { return new String(bytes, ca); } catch (Exception ignored) {}
            }
            return s;
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static String extractCA(String text) {
        int idx = text.indexOf("CA[");
        if (idx < 0) return null;
        int start = idx + 3;
        int end = text.indexOf(']', start);
        if (end < 0) return null;
        return text.substring(start, end).trim();
    }

    // ── SGF 토크나이저 ────────────────────────────────

    private static final int TOK_LPAREN  = 1;
    private static final int TOK_RPAREN  = 2;
    private static final int TOK_SEMI    = 3;
    private static final int TOK_PROP_ID = 4;
    private static final int TOK_VALUE   = 5;
    private static final int TOK_EOF     = 6;

    private String src;
    private int    pos;
    private int    tokType;
    private String tokVal;

    private SgfParser(String text) {
        this.src = text;
        this.pos = 0;
        advance();
    }

    private void advance() {
        skipWS();
        if (pos >= src.length()) { tokType = TOK_EOF; tokVal = ""; return; }
        char c = src.charAt(pos);
        if      (c == '(') { tokType = TOK_LPAREN;  tokVal = "("; pos++; }
        else if (c == ')') { tokType = TOK_RPAREN;  tokVal = ")"; pos++; }
        else if (c == ';') { tokType = TOK_SEMI;    tokVal = ";"; pos++; }
        else if (c == '[') { tokType = TOK_VALUE;   tokVal = readValue(); }
        else if (Character.isUpperCase(c)) { tokType = TOK_PROP_ID; tokVal = readPropId(); }
        else { pos++; advance(); }
    }

    private void skipWS() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private String readPropId() {
        int start = pos;
        while (pos < src.length() && Character.isUpperCase(src.charAt(pos))) pos++;
        return src.substring(start, pos);
    }

    private String readValue() {
        pos++; // skip '['
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos < src.length()) {
                    char esc = src.charAt(pos);
                    if (esc == '\n') { pos++; continue; }
                    if (esc == '\r') {
                        pos++;
                        if (pos < src.length() && src.charAt(pos) == '\n') pos++;
                        continue;
                    }
                    sb.append(esc); pos++;
                }
            } else if (c == ']') { pos++; break; }
            else { sb.append(c); pos++; }
        }
        return sb.toString();
    }

    // ── SGF 트리 구조 ─────────────────────────────────

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

    private static class SgfTree {
        List<SgfNode> nodes    = new ArrayList<>();
        List<SgfTree> children = new ArrayList<>();
    }

    private SgfTree parseTree() {
        if (tokType != TOK_LPAREN) return null;
        advance();
        SgfTree tree = new SgfTree();
        while (tokType == TOK_SEMI) {
            advance();
            SgfNode node = new SgfNode();
            while (tokType == TOK_PROP_ID) {
                String key = tokVal; advance();
                while (tokType == TOK_VALUE) { node.addProp(key, tokVal); advance(); }
            }
            tree.nodes.add(node);
        }
        while (tokType == TOK_LPAREN) {
            SgfTree child = parseTree();
            if (child != null) tree.children.add(child);
        }
        if (tokType == TOK_RPAREN) advance();
        return tree;
    }

    // ── SGF → BdkSection 변환 ─────────────────────────

    private static List<BdkSection> parseSgfText(String text) {
        SgfParser p = new SgfParser(text);
        while (p.tokType != TOK_LPAREN && p.tokType != TOK_EOF) p.advance();
        SgfTree root = p.parseTree();
        if (root == null || root.nodes.isEmpty()) return Collections.emptyList();

        List<BdkSection> sections = new ArrayList<>();

        // ── sections[0] = 기본도 ──────────────────────
        BdkSection base = new BdkSection();
        SgfNode rootNode = root.nodes.get(0);

        // 보드 크기
        String sz = rootNode.getFirst("SZ");
        if (sz != null) {
            try { base.boardSize = Integer.parseInt(sz.trim()); } catch (Exception ignored) {}
        }

        // 게임 이름: GN 속성은 인코딩 문제가 있을 수 있으므로 PB vs PW 형식 우선 사용
        String gn = rootNode.getFirst("GN");
        String pbName = rootNode.getFirst("PB");
        String pwName = rootNode.getFirst("PW");
        if (pbName != null && !pbName.isEmpty() && pwName != null && !pwName.isEmpty()) {
            base.name = pbName + " vs " + pwName;
        } else if (pbName != null && !pbName.isEmpty()) {
            base.name = pbName;
        } else if (gn != null && !gn.isEmpty()) {
            base.name = gn;
        } else {
            base.name = "기본도";
        }

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
        base.initialStones.sort(Comparator.comparingInt(a -> a[3]));

        // 게임 정보
        String pb = rootNode.getFirst("PB"); if (pb != null) base.playerBlack = pb;
        String pw = rootNode.getFirst("PW"); if (pw != null) base.playerWhite = pw;
        String br = rootNode.getFirst("BR"); if (br != null) base.rankBlack = br;
        String wr = rootNode.getFirst("WR"); if (wr != null) base.rankWhite = wr;
        String km = rootNode.getFirst("KM"); if (km != null) base.komi = km;
        String re = rootNode.getFirst("RE"); if (re != null) base.result = re;
        String dt = rootNode.getFirst("DT"); if (dt != null) base.date = dt;
        String ev = rootNode.getFirst("EV"); if (ev != null) base.event = ev;
        String pc = rootNode.getFirst("PC"); if (pc != null) base.place = pc;
        String ro = rootNode.getFirst("RO"); if (ro != null) base.round = ro;

        String rootComment = rootNode.getFirst("C");
        if (rootComment != null) base.comment = rootComment;

        String vw = rootNode.getFirst("VW");
        if (vw != null && !vw.isEmpty()) applyVW(base, vw);
        for (String lb : rootNode.get("LB")) applyLB(base, lb);

        sections.add(base);

        // ── 메인 라인 추출 ────────────────────────────
        // 루트 노드 이후 연속 노드들 + 각 분기점의 첫 번째 자식을 따라가며 메인 라인 구성
        // 동시에 각 분기점의 두 번째 이후 자식들을 변화도로 수집

        List<int[]> mainMoves = new ArrayList<>();
        StringBuilder mainComment = new StringBuilder();
        Map<String, String> mainStoneComments = new LinkedHashMap<>();
        int[] mainVW = null;

        // 루트 노드 이후 연속 노드들 처리 (root.nodes[1..])
        for (int i = 1; i < root.nodes.size(); i++) {
            SgfNode node = root.nodes.get(i);
            extractMoveFromNode(node, mainMoves);
            extractMetaFromNode(node, mainComment, mainStoneComments);
            String nodeVW = node.getFirst("VW");
            if (nodeVW != null && !nodeVW.isEmpty()) mainVW = parseVW(nodeVW);
        }

        // 루트의 자식들: 첫 번째 자식이 메인 라인 계속, 나머지는 변화도
        // 단, 루트에 AB/AW가 있고 루트 연속 노드에 착수가 없으면 형식 B (문제집)
        boolean isFormB = !base.initialStones.isEmpty() && mainMoves.isEmpty()
                          && root.nodes.size() == 1; // 루트 노드 하나뿐

        List<int[]> variationCollector = new ArrayList<>(); // 변화도 수집용 (섹션 수 제한)

        if (isFormB) {
            // 형식 B: 각 자식 분기가 독립적인 정해도/변화도
            int secIdx = 1;
            for (SgfTree child : root.children) {
                if (sections.size() >= MAX_VARIATIONS + 1) break;
                String name = (secIdx == 1) ? "정해도" : ("변화도" + (secIdx - 1));
                BdkSection sec = buildSectionFromTree(child, base, new ArrayList<>(), name);
                sections.add(sec);
                secIdx++;
                // 자식의 자식도 변화도로 수집
                collectChildVariations(child, base, sec.moves, sections, secIdx);
                secIdx += countDescendants(child);
            }
        } else {
            // 형식 A: 첫 번째 자식을 따라가며 메인 라인 구성
            SgfTree current = root;
            List<int[]> currentMoves = mainMoves;

            while (!current.children.isEmpty()) {
                // 두 번째 이후 자식 = 변화도 (현재까지의 착수를 prefix로)
                for (int ci = 1; ci < current.children.size(); ci++) {
                    if (sections.size() >= MAX_VARIATIONS + 1) break;
                    SgfTree varChild = current.children.get(ci);
                    String varName = "변화도" + sections.size();
                    BdkSection varSec = buildSectionFromTree(varChild, base,
                                                             currentMoves, varName);
                    sections.add(varSec);
                }
                // 첫 번째 자식 = 메인 라인 계속
                SgfTree firstChild = current.children.get(0);
                for (SgfNode node : firstChild.nodes) {
                    extractMoveFromNode(node, mainMoves);
                    extractMetaFromNode(node, mainComment, mainStoneComments);
                    String nodeVW = node.getFirst("VW");
                    if (nodeVW != null && !nodeVW.isEmpty()) mainVW = parseVW(nodeVW);
                }
                currentMoves = mainMoves;
                current = firstChild;
            }

            // 메인 라인 섹션 생성 (착수가 있을 때만)
            if (!mainMoves.isEmpty()) {
                BdkSection mainSec = new BdkSection();
                mainSec.name = "정해도";
                mainSec.boardSize = base.boardSize;
                mainSec.initialStones.addAll(base.initialStones);
                mainSec.moves.addAll(mainMoves);
                if (mainComment.length() > 0) mainSec.comment = mainComment.toString();
                mainSec.stoneComments.putAll(mainStoneComments);
                if (mainVW != null) {
                    mainSec.zoomX1 = mainVW[0]; mainSec.zoomY1 = mainVW[1];
                    mainSec.zoomX2 = mainVW[2]; mainSec.zoomY2 = mainVW[3];
                }
                // 정해도를 sections[1]에 삽입 (변화도들 앞에)
                sections.add(1, mainSec);
            }
        }

        return sections;
    }

    /** 노드에서 착수 추출하여 moves에 추가 */
    private static void extractMoveFromNode(SgfNode node, List<int[]> moves) {
        String bCoord = node.getFirst("B");
        String wCoord = node.getFirst("W");
        if (bCoord != null) {
            int[] xy = sgfCoordToXY(bCoord);
            if (xy != null) moves.add(new int[]{moves.size() + 1, BdkSection.BLACK, xy[0], xy[1]});
        } else if (wCoord != null) {
            int[] xy = sgfCoordToXY(wCoord);
            if (xy != null) moves.add(new int[]{moves.size() + 1, BdkSection.WHITE, xy[0], xy[1]});
        }
    }

    /** 노드에서 메타데이터(주석, LB) 추출 */
    private static void extractMetaFromNode(SgfNode node, StringBuilder comment,
                                            Map<String, String> stoneComments) {
        String c = node.getFirst("C");
        if (c != null && !c.isEmpty()) {
            if (comment.length() > 0) comment.append("\n");
            comment.append(c);
        }
        for (String lb : node.get("LB")) parseLB(lb, stoneComments);
    }

    /** 트리에서 섹션 생성 (prefix 착수 + 트리 착수) */
    private static BdkSection buildSectionFromTree(SgfTree tree, BdkSection base,
                                                   List<int[]> prefixMoves, String name) {
        List<int[]> moves = new ArrayList<>(prefixMoves);
        // prefix moves의 seq 재계산
        for (int i = 0; i < moves.size(); i++) {
            moves.set(i, new int[]{i + 1, moves.get(i)[1], moves.get(i)[2], moves.get(i)[3]});
        }

        StringBuilder comment = new StringBuilder();
        Map<String, String> stoneComments = new LinkedHashMap<>();
        int[] vwCoords = null;

        for (SgfNode node : tree.nodes) {
            extractMoveFromNode(node, moves);
            extractMetaFromNode(node, comment, stoneComments);
            String vw = node.getFirst("VW");
            if (vw != null && !vw.isEmpty()) vwCoords = parseVW(vw);
        }

        BdkSection sec = new BdkSection();
        sec.name = name;
        sec.boardSize = base.boardSize;
        sec.initialStones.addAll(base.initialStones);
        sec.moves.addAll(moves);
        if (comment.length() > 0) sec.comment = comment.toString();
        sec.stoneComments.putAll(stoneComments);
        if (vwCoords != null) {
            sec.zoomX1 = vwCoords[0]; sec.zoomY1 = vwCoords[1];
            sec.zoomX2 = vwCoords[2]; sec.zoomY2 = vwCoords[3];
        }
        return sec;
    }

    /** 형식 B에서 자식의 자식 변화도 수집 */
    private static void collectChildVariations(SgfTree tree, BdkSection base,
                                               List<int[]> parentMoves,
                                               List<BdkSection> sections, int startIdx) {
        int idx = startIdx;
        for (SgfTree child : tree.children) {
            if (sections.size() >= MAX_VARIATIONS + 1) return;
            String name = "변화도" + (idx - 1);
            BdkSection sec = buildSectionFromTree(child, base, parentMoves, name);
            sections.add(sec);
            collectChildVariations(child, base, sec.moves, sections, idx + 1);
            idx++;
        }
    }

    /** 트리의 총 자손 수 */
    private static int countDescendants(SgfTree tree) {
        int count = tree.children.size();
        for (SgfTree child : tree.children) count += countDescendants(child);
        return count;
    }

    // ── 좌표 변환 ─────────────────────────────────────

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
        if (c >= 'A' && c <= 'S') return c - 'A' + 1;
        return -1;
    }

    // ── LB (레이블) 처리 ──────────────────────────────

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

    private static int[] parseVW(String vw) {
        if (vw == null || vw.isEmpty()) return null;
        int colon = vw.indexOf(':');
        if (colon < 0) return null;
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
