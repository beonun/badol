package com.badol.studio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SGF(Smart Game Format) 라이터
 *
 * ── BdkSection → SGF 매핑 규칙 ─────────────────────────────────────────────
 *
 * sections[0] = 기본도  → 루트 노드 (AB/AW)
 * sections[1] = 정해도  → 첫 번째 분기
 * sections[2+] = 변화도 → 나머지 분기 (루트의 형제 분기)
 *
 * 출력 SGF 구조:
 *   (;GM[1]FF[4]CA[UTF-8]SZ[19]GN[기본도]
 *     AB[pd][qd]AW[dd]
 *     (;B[pd];W[qd];B[rd]C[정해도 주석])
 *     (;B[qd];W[pd]C[변화도1 주석])
 *   )
 *
 * 좌표 변환:
 *   BdkSection x,y (1-based) → SGF 'a'~'s' (소문자 알파벳)
 */
public class SgfWriter {

    public static void write(List<BdkSection> sections, File file) throws IOException {
        byte[] data = serialize(sections);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    public static byte[] serialize(List<BdkSection> sections) {
        return buildSgf(sections).getBytes(StandardCharsets.UTF_8);
    }

    public static String buildSgf(List<BdkSection> sections) {
        if (sections == null || sections.isEmpty()) return "(;GM[1]FF[4]CA[UTF-8]SZ[19])";

        BdkSection base = sections.get(0);
        StringBuilder sb = new StringBuilder();

        sb.append("(\n");
        sb.append(";GM[1]FF[4]CA[UTF-8]AP[바돌:1.0]\n");
        sb.append("SZ[").append(base.boardSize).append("]\n");

        // 게임 이름
        if (base.name != null && !base.name.isEmpty()) {
            sb.append("GN[").append(escapeSgf(base.name)).append("]\n");
        }

        // 게임 정보
        if (!isEmpty(base.playerBlack))  sb.append("PB[").append(escapeSgf(base.playerBlack)).append("]\n");
        if (!isEmpty(base.rankBlack))    sb.append("BR[").append(escapeSgf(base.rankBlack)).append("]\n");
        if (!isEmpty(base.playerWhite))  sb.append("PW[").append(escapeSgf(base.playerWhite)).append("]\n");
        if (!isEmpty(base.rankWhite))    sb.append("WR[").append(escapeSgf(base.rankWhite)).append("]\n");
        if (!isEmpty(base.komi))         sb.append("KM[").append(escapeSgf(base.komi)).append("]\n");
        if (!isEmpty(base.result))       sb.append("RE[").append(escapeSgf(base.result)).append("]\n");
        if (!isEmpty(base.date))         sb.append("DT[").append(escapeSgf(base.date)).append("]\n");
        if (!isEmpty(base.event))        sb.append("EV[").append(escapeSgf(base.event)).append("]\n");
        if (!isEmpty(base.place))        sb.append("PC[").append(escapeSgf(base.place)).append("]\n");
        if (!isEmpty(base.round))        sb.append("RO[").append(escapeSgf(base.round)).append("]\n");

        // 기본도 돌 (AB/AW)
        if (!base.initialStones.isEmpty()) {
            // seq 순서로 정렬
            List<int[]> sorted = new ArrayList<>(base.initialStones);
            sorted.sort(Comparator.comparingInt(a -> a[3]));

            StringBuilder abSb = new StringBuilder();
            StringBuilder awSb = new StringBuilder();
            for (int[] stone : sorted) {
                String coord = xyToSgf(stone[1], stone[2]);
                if (stone[0] == BdkSection.BLACK) abSb.append("[").append(coord).append("]");
                else awSb.append("[").append(coord).append("]");
            }
            if (abSb.length() > 0) sb.append("AB").append(abSb).append("\n");
            if (awSb.length() > 0) sb.append("AW").append(awSb).append("\n");
        }

        // 기본도 주석
        if (base.comment != null && !base.comment.isEmpty()) {
            sb.append("C[").append(escapeSgf(base.comment)).append("]\n");
        }

        // 기본도 LB (돌 설명)
        appendLB(sb, base);

        // 기본도 VW (줌 영역)
        if (base.hasZoom()) {
            sb.append("VW[").append(xyToSgf(base.zoomX1, base.zoomY1))
              .append(":").append(xyToSgf(base.zoomX2, base.zoomY2)).append("]\n");
        }

        // 정해도/변화도 분기
        for (int i = 1; i < sections.size(); i++) {
            BdkSection sec = sections.get(i);
            sb.append("\n(");
            appendMoves(sb, sec);
            sb.append(")");
        }

        sb.append("\n)");
        return sb.toString();
    }

    /** 착수 목록을 SGF 노드 시퀀스로 출력 */
    private static void appendMoves(StringBuilder sb, BdkSection sec) {
        if (sec.moves.isEmpty()) {
            // 착수 없는 섹션: 이름만 기록
            sb.append(";GN[").append(escapeSgf(sec.name)).append("]");
            if (sec.comment != null && !sec.comment.isEmpty()) {
                sb.append("C[").append(escapeSgf(sec.comment)).append("]");
            }
            return;
        }

        for (int i = 0; i < sec.moves.size(); i++) {
            int[] move = sec.moves.get(i); // [moveNum, color, x, y]
            String color = (move[1] == BdkSection.BLACK) ? "B" : "W";
            String coord = xyToSgf(move[2], move[3]);
            sb.append(";").append(color).append("[").append(coord).append("]");

            // 첫 번째 착수에 섹션 이름 기록
            if (i == 0 && sec.name != null && !sec.name.isEmpty()) {
                sb.append("GN[").append(escapeSgf(sec.name)).append("]");
            }

            // 돌 설명 (LB)
            String key = move[2] + "," + move[3];
            String label = sec.stoneComments.get(key);
            if (label != null && !label.isEmpty()) {
                sb.append("LB[").append(coord).append(":").append(escapeSgf(label)).append("]");
            }
        }

        // 섹션 주석 (마지막 노드에 추가)
        if (sec.comment != null && !sec.comment.isEmpty()) {
            sb.append("C[").append(escapeSgf(sec.comment)).append("]");
        }

        // LB (착수 외 돌 설명)
        appendLB(sb, sec);

        // 마커 (TR/CR/SQ/MA/AR/LN)
        appendMarkers(sb, sec);

        // VW (줄 영역)
        if (sec.hasZoom()) {
            sb.append("VW[").append(xyToSgf(sec.zoomX1, sec.zoomY1))
              .append(":").append(xyToSgf(sec.zoomX2, sec.zoomY2)).append("]");
        }
    }

    /** 마커 속성 출력 (TR/CR/SQ/MA/LB/AR/LN) */
    private static void appendMarkers(StringBuilder sb, BdkSection sec) {
        if (!sec.markersTriangle.isEmpty()) {
            sb.append("TR");
            for (int[] m : sec.markersTriangle) sb.append("[").append(xyToSgf(m[0], m[1])).append("]");
        }
        if (!sec.markersCircle.isEmpty()) {
            sb.append("CR");
            for (int[] m : sec.markersCircle) sb.append("[").append(xyToSgf(m[0], m[1])).append("]");
        }
        if (!sec.markersSquare.isEmpty()) {
            sb.append("SQ");
            for (int[] m : sec.markersSquare) sb.append("[").append(xyToSgf(m[0], m[1])).append("]");
        }
        if (!sec.markersX.isEmpty()) {
            sb.append("MA");
            for (int[] m : sec.markersX) sb.append("[").append(xyToSgf(m[0], m[1])).append("]");
        }
        for (Map.Entry<String, String> e : sec.markersLabel.entrySet()) {
            String[] parts = e.getKey().split(",");
            if (parts.length != 2) continue;
            try {
                int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
                sb.append("LB[").append(xyToSgf(x, y)).append(":").append(escapeSgf(e.getValue())).append("]");
            } catch (NumberFormatException ignored) {}
        }
        for (int[] m : sec.markersArrow) {
            sb.append("AR[").append(xyToSgf(m[0], m[1])).append(":").append(xyToSgf(m[2], m[3])).append("]");
        }
        for (int[] m : sec.markersLine) {
            sb.append("LN[").append(xyToSgf(m[0], m[1])).append(":").append(xyToSgf(m[2], m[3])).append("]");
        }
    }

    /** stoneComments → LB 속성 출력 */
    private static void appendLB(StringBuilder sb, BdkSection sec) {
        if (sec.stoneComments.isEmpty()) return;
        for (Map.Entry<String, String> entry : sec.stoneComments.entrySet()) {
            String[] parts = entry.getKey().split(",");
            if (parts.length != 2) continue;
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                sb.append("LB[").append(xyToSgf(x, y)).append(":")
                  .append(escapeSgf(entry.getValue())).append("]");
            } catch (NumberFormatException ignored) {}
        }
    }

    // ── 좌표 변환 ─────────────────────────────────────

    /** BdkSection x,y (1-based) → SGF 좌표 문자열 */
    static String xyToSgf(int x, int y) {
        return String.valueOf((char)('a' + x - 1)) + (char)('a' + y - 1);
    }

    // ── SGF 이스케이프 ────────────────────────────────

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    /** SGF 속성값 이스케이프: ']' → '\]', '\' → '\\' */
    static String escapeSgf(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("]", "\\]");
    }
}
