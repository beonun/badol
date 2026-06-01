package com.bdk.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * 바둑 규칙 헬퍼 클래스
 *
 * - 따내기 (capture)
 * - 자살수 판별 (suicide)
 * - 패 판별 (ko)
 *
 * 좌표 규칙: board[y][x], 1-based (x=1~size, y=1~size)
 *
 * 사용법:
 *   GoRules rules = new GoRules(boardSize);
 *   PlaceResult result = rules.tryPlace(board, moveNum, color, x, y, koX, koY);
 *   if (result.ok) {
 *       // result.newBoard, result.newMoveNum 적용
 *       // result.capturedStones: 따낸 돌 목록
 *       // result.newKoX, result.newKoY: 다음 패 금지 좌표 (-1이면 패 없음)
 *   } else {
 *       // result.reason: "OCCUPIED" | "SUICIDE" | "KO"
 *   }
 */
public class GoRules {

    public static final String REASON_OCCUPIED = "OCCUPIED"; // 이미 돌이 있음
    public static final String REASON_SUICIDE  = "SUICIDE";  // 자살수
    public static final String REASON_KO       = "KO";       // 패

    private final int size;

    public GoRules(int boardSize) {
        this.size = boardSize;
    }

    /**
     * 착수 결과를 담는 클래스
     */
    public static class PlaceResult {
        public final boolean    ok;             // 착수 가능 여부
        public final String     reason;         // 불가 이유 (ok=false일 때)
        public final int[][]    newBoard;       // 착수 후 보드 상태
        public final int[][]    newMoveNum;     // 착수 후 수순 번호 배열
        public final List<int[]> capturedStones; // 따낸 돌 목록 [{x,y,color}, ...]
        public final int        newKoX;         // 다음 패 금지 x (-1이면 없음)
        public final int        newKoY;         // 다음 패 금지 y

        // 성공
        PlaceResult(int[][] board, int[][] moveNum, List<int[]> captured, int koX, int koY) {
            this.ok             = true;
            this.reason         = null;
            this.newBoard       = board;
            this.newMoveNum     = moveNum;
            this.capturedStones = captured;
            this.newKoX         = koX;
            this.newKoY         = koY;
        }

        // 실패
        PlaceResult(String reason) {
            this.ok             = false;
            this.reason         = reason;
            this.newBoard       = null;
            this.newMoveNum     = null;
            this.capturedStones = null;
            this.newKoX         = -1;
            this.newKoY         = -1;
        }
    }

    /**
     * 착수 시도. 보드를 직접 수정하지 않고 결과를 반환한다.
     *
     * @param board    현재 보드 상태 [y][x]
     * @param moveNum  현재 수순 번호 배열 [y][x]
     * @param color    착수할 색 (BdkSection.BLACK or WHITE)
     * @param x        착수 x (1-based)
     * @param y        착수 y (1-based)
     * @param koX      현재 패 금지 x (-1이면 없음)
     * @param koY      현재 패 금지 y
     * @param moveNo   이 수의 수순 번호 (moveNum 배열에 기록)
     */
    public PlaceResult tryPlace(int[][] board, int[][] moveNum,
                                int color, int x, int y,
                                int koX, int koY, int moveNo) {
        // 1. 이미 돌이 있는 자리
        if (board[y][x] != BdkSection.EMPTY) {
            return new PlaceResult(REASON_OCCUPIED);
        }

        // 2. 패 금지
        if (x == koX && y == koY) {
            return new PlaceResult(REASON_KO);
        }

        // 3. 보드 복사 후 착수
        int[][] b = copyBoard(board);
        int[][] mn = copyMoveNum(moveNum);
        b[y][x]  = color;
        mn[y][x] = moveNo;

        // 4. 따내기: 인접 상대 그룹 중 자유도 0인 것 제거
        int opp = opponent(color);
        List<int[]> captured = new ArrayList<>();
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1];
            if (inBounds(nx, ny) && b[ny][nx] == opp) {
                List<int[]> group = getGroup(b, nx, ny);
                if (liberties(b, group) == 0) {
                    for (int[] s : group) {
                        captured.add(new int[]{s[0], s[1], opp});
                        b[s[1]][s[0]]  = BdkSection.EMPTY;
                        mn[s[1]][s[0]] = 0;
                    }
                }
            }
        }

        // 5. 자살수 판별: 따내기 후에도 자기 그룹 자유도 0이면 자살수
        List<int[]> myGroup = getGroup(b, x, y);
        if (liberties(b, myGroup) == 0) {
            return new PlaceResult(REASON_SUICIDE);
        }

        // 6. 패 판별: 1점 따내고 자기도 1점 그룹이면 패 가능성
        //    → 따낸 자리가 1곳이고, 내 그룹도 1점이면 그 자리가 다음 패 금지
        int nextKoX = -1, nextKoY = -1;
        if (captured.size() == 1 && myGroup.size() == 1) {
            nextKoX = captured.get(0)[0];
            nextKoY = captured.get(0)[1];
        }

        return new PlaceResult(b, mn, captured, nextKoX, nextKoY);
    }

    /**
     * 보드에 직접 착수 적용 (따내기 포함). 착수 가능 여부 검사 없이 강제 적용.
     * 뷰어의 기보 재생 시 사용 (파일에 저장된 수순을 그대로 재생).
     *
     * @return 따낸 돌 목록
     */
    public List<int[]> applyMove(int[][] board, int[][] moveNum,
                                 int color, int x, int y, int moveNo) {
        board[y][x]  = color;
        moveNum[y][x] = moveNo;

        int opp = opponent(color);
        List<int[]> captured = new ArrayList<>();
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1];
            if (inBounds(nx, ny) && board[ny][nx] == opp) {
                List<int[]> group = getGroup(board, nx, ny);
                if (liberties(board, group) == 0) {
                    for (int[] s : group) {
                        captured.add(new int[]{s[0], s[1], opp});
                        board[s[1]][s[0]]  = BdkSection.EMPTY;
                        moveNum[s[1]][s[0]] = 0;
                    }
                }
            }
        }
        return captured;
    }

    // ── 내부 헬퍼 ────────────────────────────────────────

    private List<int[]> getGroup(int[][] b, int x, int y) {
        List<int[]> group = new ArrayList<>();
        boolean[][] visited = new boolean[size + 2][size + 2];
        int color = b[y][x];
        if (color != BdkSection.EMPTY) fill(b, x, y, color, group, visited);
        return group;
    }

    private void fill(int[][] b, int x, int y, int color, List<int[]> g, boolean[][] v) {
        if (!inBounds(x, y) || v[y][x] || b[y][x] != color) return;
        v[y][x] = true;
        g.add(new int[]{x, y});
        fill(b, x+1, y, color, g, v);
        fill(b, x-1, y, color, g, v);
        fill(b, x, y+1, color, g, v);
        fill(b, x, y-1, color, g, v);
    }

    private int liberties(int[][] b, List<int[]> group) {
        boolean[][] counted = new boolean[size + 2][size + 2];
        int libs = 0;
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (int[] s : group)
            for (int[] d : dirs) {
                int nx = s[0] + d[0], ny = s[1] + d[1];
                if (inBounds(nx, ny) && !counted[ny][nx] && b[ny][nx] == BdkSection.EMPTY) {
                    counted[ny][nx] = true;
                    libs++;
                }
            }
        return libs;
    }

    private boolean inBounds(int x, int y) {
        return x >= 1 && x <= size && y >= 1 && y <= size;
    }

    private int opponent(int color) {
        return (color == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
    }

    private int[][] copyBoard(int[][] src) {
        int[][] copy = new int[size + 2][size + 2];
        for (int i = 0; i < src.length; i++) System.arraycopy(src[i], 0, copy[i], 0, src[i].length);
        return copy;
    }

    private int[][] copyMoveNum(int[][] src) {
        int[][] copy = new int[size + 2][size + 2];
        for (int i = 0; i < src.length; i++) System.arraycopy(src[i], 0, copy[i], 0, src[i].length);
        return copy;
    }
}
