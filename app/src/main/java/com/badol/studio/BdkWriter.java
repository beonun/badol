package com.badol.studio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

/**
 * BDK 파일 저장기
 *
 * 파일 구조:
 *   [0~11]  : 파일 헤더 (12바이트)
 *             bytes[0:2] = 0x2717 (버전)
 *             bytes[2:4] = 0x0000
 *             bytes[4:8] = 섹션 수 (little-endian)
 *             bytes[8:12] = 0x00000000
 *   [12~15] : FF FF FF FF (첫 번째 섹션 구분자)
 *   각 섹션:
 *     - 섹션0만: FF FF FF FF (추가 4바이트)
 *     - 고정 헤더 블록 48바이트 (zoom 좌표 포함)
 *     - FF FE FF [글자수] [UTF-16LE 이름] FF FE FF 00 0A 00 00 00
 *     - 레코드들 (13바이트씩)
 *     - 패딩 (00 00 00 00)
 *     - 돌별 설명 RTF 블록들 (0개 이상)
 *       - [0]   x (0-based)
 *       - [1]   y (0-based)
 *       - [2~3] extra (00 00)
 *       - [4~7] RTF 크기+2 (little-endian)
 *       - [8~]  RTF 본문
 *     - 섹션 전체 설명 RTF 블록 (x=0, y=0)
 *     - FF FF FF FF (섹션 구분자)
 *
 * 레코드 구조 (13바이트):
 *   [0]    color  : 0=흑, 1=백
 *   [1]    x      : 0-based (0~18)
 *   [2]    y      : 0-based (0~18)
 *   [3~4]  seq    : little-endian 2바이트
 *   [5~8]  00 00 00 00
 *   [9~12] FE FF FF FF  (레코드 꼬리)
 */
public class BdkWriter {

    private static final byte[] SECTION_SEP  = {(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF};
    private static final byte[] RECORD_ZEROS = {0x00, 0x00, 0x00, 0x00};
    private static final byte[] RECORD_TAIL  = {(byte)0xFE,(byte)0xFF,(byte)0xFF,(byte)0xFF};

    // 이름 블록 꼬리 (FF FE FF 00 0A 00 00 00)
    private static final byte[] NAME_TAIL = {
        (byte)0xFF, (byte)0xFE, (byte)0xFF, 0x00, 0x0A, 0x00, 0x00, 0x00
    };

    /**
     * BdkSection 목록을 BDK 바이너리로 직렬화하여 파일에 저장
     */
    public static void write(List<BdkSection> sections, File file) throws Exception {
        byte[] data = serialize(sections);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    /**
     * BdkSection 목록을 BDK 바이너리로 직렬화
     */
    public static byte[] serialize(List<BdkSection> sections) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // ── 파일 헤더 (12바이트) ──────────────────────────────────────
        out.write(0x17);
        out.write(0x27);
        out.write(0x00);
        out.write(0x00);
        int secCount = sections.size();
        writeInt32LE(out, secCount);
        writeInt32LE(out, 0);

        // ── 첫 번째 섹션 구분자 ───────────────────────────────────────
        out.write(SECTION_SEP);

        for (int i = 0; i < sections.size(); i++) {
            BdkSection sec = sections.get(i);

            // ── 섹션 헤더 블록 ────────────────────────────────────────────
            // 모든 섹션: 구분자(4) + FF×4 = 총 8바이트 FF 연속
            out.write(0xFF); out.write(0xFF); out.write(0xFF); out.write(0xFF);
            // 고정 블록 48바이트 (zoom 좌표 포함)
            writeSectionFixed(out, sec);
            // 이름 블록
            writeNameBlock(out, sec.name);

            // ── 레코드 기록 ───────────────────────────────────────────
            if (i == 0) {
                // 기본도 섹션: initialStones만
                for (int[] stone : sec.initialStones) {
                    int color = stone[0];
                    int x     = stone[1] - 1;
                    int y     = stone[2] - 1;
                    int seq   = stone[3];
                    writeRecord(out, color, x, y, seq);
                }
            } else {
                // 정해도 / 변화도 섹션
                // 1) 기본도 초기 배치 돌 (seq=2000+)
                BdkSection base = sections.get(0);
                for (int[] stone : base.initialStones) {
                    int color = stone[0];
                    int x     = stone[1] - 1;
                    int y     = stone[2] - 1;
                    int seq   = stone[3] + 2000;
                    writeRecord(out, color, x, y, seq);
                }
                // 2) 실제 착수 (seq=0, 1, 2...)
                for (int[] move : sec.moves) {
                    int seq   = move[0] - 1;
                    int color = move[1];
                    int x     = move[2] - 1;
                    int y     = move[3] - 1;
                    writeRecord(out, color, x, y, seq);
                }
            }

            // ── 패딩 (00 00 00 00) ────────────────────────────────────
            out.write(RECORD_ZEROS);

            // ── 돌별 설명 RTF 블록들 ──────────────────────────────────
            if (sec.stoneComments != null && !sec.stoneComments.isEmpty()) {
                for (Map.Entry<String, String> entry : sec.stoneComments.entrySet()) {
                    String key = entry.getKey(); // "x,y" (1-based)
                    String text = entry.getValue();
                    if (text == null || text.isEmpty()) continue;
                    String[] parts = key.split(",");
                    if (parts.length != 2) continue;
                    try {
                        int x1 = Integer.parseInt(parts[0]) - 1; // 1-based → 0-based
                        int y1 = Integer.parseInt(parts[1]) - 1;
                        writeStoneCommentBlock(out, x1, y1, 0, text);
                    } catch (NumberFormatException ignored) {}
                }
            }

            // ── 섹션 전체 설명 RTF 블록 ───────────────────────────────
            if (sec.comment != null && !sec.comment.isEmpty()) {
                writeStoneCommentBlock(out, 0, 0, 0, sec.comment);
            }

            // ── 섹션 구분자 ───────────────────────────────────────────
            out.write(SECTION_SEP);
        }

        return out.toByteArray();
    }

    /**
     * 고정 헤더 블록 48바이트 기록 (zoom 좌표 포함)
     * 구조:
     *   [0~3]   FF FF FF FF
     *   [4~6]   FF FF FF
     *   [7]     바둑판 크기 (0x13 = 19)
     *   [8~11]  zoom x1 (0-based, little-endian)
     *   [12~15] zoom y1 (0-based, little-endian)
     *   [16~19] zoom x2 (0-based, little-endian)
     *   [20~23] zoom y2 (0-based, little-endian)
     *   [24~47] 나머지 고정값
     */
    private static void writeSectionFixed(ByteArrayOutputStream out, BdkSection sec) throws Exception {
        // [0~3] boardSize (int32)
        writeInt32LE(out, sec.boardSize);
        // [4~7] zoom x1 (0-based)
        int zx1 = sec.hasZoom() ? (sec.zoomX1 - 1) : 0;
        int zy1 = sec.hasZoom() ? (sec.zoomY1 - 1) : 0;
        int zx2 = sec.hasZoom() ? (sec.zoomX2 - 1) : (sec.boardSize - 1);
        int zy2 = sec.hasZoom() ? (sec.zoomY2 - 1) : (sec.boardSize - 1);
        writeInt32LE(out, zx1);
        writeInt32LE(out, zy1);
        writeInt32LE(out, zx2);
        writeInt32LE(out, zy2);
        // [20~47] 나머지 28바이트 고정값 (0으로 채움)
        for (int k = 0; k < 28; k++) out.write(0x00);
    }

    /**
     * 돌별 설명 또는 섹션 전체 설명 RTF 블록 기록
     * 구조:
     *   [0]   x (0-based)
     *   [1]   y (0-based)
     *   [2~3] extra (00 00)
     *   [4~7] RTF 크기+2 (little-endian)
     *   [8~]  RTF 본문
     */
    private static void writeStoneCommentBlock(ByteArrayOutputStream out,
                                               int x0, int y0, int extra,
                                               String text) throws Exception {
        // 텍스트를 RTF 형식으로 변환
        byte[] rtfBytes = textToRtf(text);
        // 헤더 8바이트
        out.write(x0 & 0xFF);
        out.write(y0 & 0xFF);
        out.write(extra & 0xFF);
        out.write((extra >> 8) & 0xFF);
        writeInt32LE(out, rtfBytes.length + 2);
        // RTF 본문
        out.write(rtfBytes);
    }

    /**
     * 순수 텍스트를 EUC-KR RTF 형식으로 변환
     * {\rtf1\ansi\ansicpg949\deff0{\fonttbl{\f0\fnil\fcharset129 맑은 고딕;}}
     * viewkind4 uc1 pard lang1042 f0 fs20 텍스트 par}
     */
    private static byte[] textToRtf(String text) throws Exception {
        StringBuilder rtf = new StringBuilder();
        rtf.append("{\\rtf1\\ansi\\ansicpg949\\deff0");
        rtf.append("{\\fonttbl{\\f0\\fnil\\fcharset129 ");
        // 폰트 이름 EUC-KR 인코딩
        appendEucKrRtf(rtf, "맑은 고딕");
        rtf.append(";}}");
        rtf.append("\\viewkind4\\u" + "c1\\pard\\lang1042\\f0\\fs20 ");
        // 본문 텍스트
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            appendEucKrRtf(rtf, lines[i]);
            if (i < lines.length - 1) {
                rtf.append("\\par\n");
            }
        }
        rtf.append("\\par}");
        return rtf.toString().getBytes("EUC-KR");
    }

    /**
     * 텍스트를 RTF EUC-KR 이스케이프 형식으로 변환
     * 한글은 \'xx\'xx 형식으로, ASCII는 그대로
     */
    private static void appendEucKrRtf(StringBuilder sb, String text) throws Exception {
        byte[] bytes = text.getBytes("EUC-KR");
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b >= 0xA1 && b <= 0xFE && i + 1 < bytes.length) {
                // EUC-KR 2바이트 한글
                int b2 = bytes[i+1] & 0xFF;
                sb.append(String.format("\\'%02x\\'%02x", b, b2));
                i += 2;
            } else if (b == '\\' || b == '{' || b == '}') {
                sb.append('\\').append((char)b);
                i++;
            } else {
                sb.append((char)b);
                i++;
            }
        }
    }

    /**
     * 섹션 이름 블록 기록
     * 구조: FF FE FF [글자수(1바이트)] [UTF-16LE 이름] FF FE FF 00 0A 00 00 00
     */
    private static void writeNameBlock(ByteArrayOutputStream out, String name) throws Exception {
        if (name == null) name = "";
        byte[] nameUtf16 = name.getBytes("UTF-16LE");
        int charCount = name.length();
        out.write(0xFF);
        out.write(0xFE);
        out.write(0xFF);
        out.write(charCount & 0xFF);
        out.write(nameUtf16);
        out.write(NAME_TAIL);
    }

    /**
     * 레코드 1개 (13바이트) 기록
     */
    private static void writeRecord(ByteArrayOutputStream out,
                                    int color, int x, int y, int seq) {
        out.write(color == BdkSection.BLACK ? 0 : 1);
        out.write(x & 0xFF);
        out.write(y & 0xFF);
        out.write(seq & 0xFF);
        out.write((seq >> 8) & 0xFF);
        out.write(RECORD_ZEROS, 0, 4);
        out.write(RECORD_TAIL, 0, 4);
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
