package com.badol.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * 바둑 규칙 헬퍼 클래스
 *
 * ── 핵심 설계 원칙 ──────────────────────────────────────────────────────────
 *
 * 착수 이력은 단순한 리스트(moveHistory)로 관리한다.
 *   moveHistory.get(i) = [color, x, y]  (i=0이 1번째 착수)
 *
 * 착수 번호(moveNo)는 항상 moveHistory 인덱스 + 1 이다.
 *   1번째 착수 = moveNo 1 (흑, 홀수)
 *   2번째 착수 = moveNo 2 (백, 짝수)
 *   ...
 *
 * 따내기가 발생해도 moveHistory는 절대 변경되지 않는다.
 * undo는 moveHistory의 마지막 항목을 제거하는 것이다.
 *
 * 화면 표시용 moveNum[y][x]는 항상 moveHistory를 기반으로 재계산한다.
 *   - 현재 보드에 살아있는 돌만 번호를 표시한다.
 *   - 같은 자리에 여러 번 착수된 경우 가장 최근 번호를 표시한다.
 *
 * ── 좌표 규칙 ──────────────────────────────────────────────────────────────
 *   board[y][x], 1-based (x=1~size, y=1~size)
 */
public class GoRules {

    public static final String REASON_OCCUPIED = "OCCUPIED"; // 이미 돌이 있음
    public static final String REASON_SUICIDE  = "SUICIDE";  // 자살수
    public static final String REASON_KO       = "KO";       // 패

    private final int size;

    public GoRules(int boardSize) {
        this.size = boardSize;
    }

    // ── 착수 결과 ──────────────────────────────────────

    public static class PlaceResult {
        public final boolean      ok;
        public final String       reason;
        public final int[][]      newBoard;
        public final List<int[]>  capturedStones; // [{x,y,color}, ...]
        public final int          newKoX;
        public final int          newKoY;

        PlaceResult(int[][] board, List<int[]> captured, int koX, int koY) {
            this.ok             = true;
            this.reason         = null;
            this.newBoard       = board;
            this.capturedStones = captured;
            this.newKoX         = koX;
            this.newKoY         = koY;
        }

        PlaceResult(String reason) {
            this.ok             = false;
            this.reason         = reason;
            this.newBoard       = null;
            this.capturedStones = null;
            this.newKoX         = -1;
            this.newKoY         = -1;
        }
    }

    /**
     * 착수 시도. 보드를 직접 수정하지 않고 결과를 반환한다.
     * moveNum 배열은 더 이상 여기서 관리하지 않는다.
     * 번호 표시는 호출자가 moveHistory 기반으로 재계산한다.
     *
     * @param board  현재 보드 상태 [y][x]
     * @param color  착수할 색
     * @param x      착수 x (1-based)
     * @param y      착수 y (1-based)
     * @param koX    현재 패 금지 x (-1이면 없음)
     * @param koY    현재 패 금지 y
     */
    public PlaceResult tryPlace(int[][] board,
                                int color, int x, int y,
                                int koX, int koY) {
        if (board[y][x] != BdkSection.EMPTY)
            return new PlaceResult(REASON_OCCUPIED);

        if (x == koX && y == koY)
            return new PlaceResult(REASON_KO);

        int[][] b = copyBoard(board);
        b[y][x] = color;

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
                        b[s[1]][s[0]] = BdkSection.EMPTY;
                    }
                }
            }
        }

        // 자살수 판별
        if (liberties(b, getGroup(b, x, y)) == 0)
            return new PlaceResult(REASON_SUICIDE);

        // 패 판별
        int nextKoX = -1, nextKoY = -1;
        if (captured.size() == 1 && getGroup(b, x, y).size() == 1) {
            nextKoX = captured.get(0)[0];
            nextKoY = captured.get(0)[1];
        }

        return new PlaceResult(b, captured, nextKoX, nextKoY);
    }

    /**
     * 보드에 직접 착수 적용 (따내기 포함). 착수 가능 여부 검사 없이 강제 적용.
     * 뷰어의 기보 재생 / 편집기의 goToMoveStep 재계산 시 사용.
     *
     * @return 따낸 돌 목록 [{x,y,color}, ...]
     */
    public List<int[]> applyMove(int[][] board, int color, int x, int y) {
        board[y][x] = color;

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
                        board[s[1]][s[0]] = BdkSection.EMPTY;
                    }
                }
            }
        }
        return captured;
    }

    /**
     * moveHistory를 기반으로 moveNum[y][x] 배열을 재계산한다.
     *
     * 알고리즘:
     *   1. moveHistory를 처음부터 순서대로 재생하여 보드 상태를 구성한다.
     *   2. 각 착수 시 board[y][x] = color, moveNum[y][x] = moveNo(=index+1) 기록.
     *   3. 따내기 발생 시 따낸 돌의 board[y][x] = EMPTY, moveNum[y][x] = 0 처리.
     *   4. 결과적으로 moveNum[y][x]는 현재 보드에 살아있는 돌의 착수 번호를 나타낸다.
     *
     * @param baseBoard  기본도 보드 상태 (initialStones 적용된 상태)
     * @param moveHistory 착수 이력 리스트 [color, x, y]
     * @param targetStep  재생할 수순 수 (0이면 기본도 상태)
     * @param outBoard    결과 보드 상태 [y][x] (출력)
     * @param outMoveNum  결과 수순 번호 배열 [y][x] (출력)
     * @return 마지막 수순의 패 좌표 [koX, koY] (-1이면 없음)
     */
    public int[] rebuildState(int[][] baseBoard,
                              List<int[]> moveHistory, int targetStep,
                              int[][] outBoard, int[][] outMoveNum) {
        // 기본도 복사
        for (int y = 1; y <= size; y++)
            for (int x = 1; x <= size; x++) {
                outBoard[y][x]   = baseBoard[y][x];
                outMoveNum[y][x] = 0;
            }

        int koX = -1, koY = -1;
        int step = Math.min(targetStep, moveHistory.size());

        for (int i = 0; i < step; i++) {
            int[] h = moveHistory.get(i);
            int color = h[0], mx = h[1], my = h[2];
            int moveNo = i + 1; // 착수 번호 = 인덱스 + 1

            outBoard[my][mx]   = color;
            outMoveNum[my][mx] = moveNo;

            // 따내기
            int opp = opponent(color);
            int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
            List<int[]> captured = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = mx + d[0], ny = my + d[1];
                if (inBounds(nx, ny) && outBoard[ny][nx] == opp) {
                    List<int[]> group = getGroup(outBoard, nx, ny);
                    if (liberties(outBoard, group) == 0) {
                        for (int[] s : group) {
                            captured.add(new int[]{s[0], s[1]});
                            outBoard[s[1]][s[0]]   = BdkSection.EMPTY;
                            outMoveNum[s[1]][s[0]] = 0;
                        }
                    }
                }
            }

            // 패 갱신
            if (captured.size() == 1 && getGroup(outBoard, mx, my).size() == 1) {
                koX = captured.get(0)[0];
                koY = captured.get(0)[1];
            } else {
                koX = -1; koY = -1;
            }
        }

        return new int[]{koX, koY};
    }

    // ── 내부 헬퍼 ────────────────────────────────────────

    public List<int[]> getGroup(int[][] b, int x, int y) {
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

    public int liberties(int[][] b, List<int[]> group) {
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

    public boolean inBounds(int x, int y) {
        return x >= 1 && x <= size && y >= 1 && y <= size;
    }

    private int opponent(int color) {
        return (color == BdkSection.BLACK) ? BdkSection.WHITE : BdkSection.BLACK;
    }

    private int[][] copyBoard(int[][] src) {
        int[][] copy = new int[src.length][];
        for (int i = 0; i < src.length; i++) copy[i] = src[i].clone();
        return copy;
    }
}
