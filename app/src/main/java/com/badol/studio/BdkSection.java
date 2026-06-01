package com.badol.studio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BDK 파일 내 하나의 섹션 (기본도/정해도/오답도 등) */
public class BdkSection {
    public String name = "";          // 섹션 이름 (헤더에서 추출)
    public int boardSize = 19;

    /** 초기 배치 돌: [color, x, y, seq] */
    public List<int[]> initialStones = new ArrayList<>();

    /** 착수 목록: [moveNum(1-based), color, x, y] */
    public List<int[]> moves = new ArrayList<>();

    /** 섹션 전체 주석 (RTF 텍스트) */
    public String comment = "";

    /**
     * 돌별 설명: 키="x,y" (1-based), 값=설명 텍스트
     * 예: stoneComments.get("9,11") = "흑1 설명"
     */
    public Map<String, String> stoneComments = new HashMap<>();

    /**
     * 줌 영역 (0이면 전체 보기)
     * zoomX1, zoomY1: 좌상단 (1-based)
     * zoomX2, zoomY2: 우하단 (1-based)
     */
    public int zoomX1 = 0, zoomY1 = 0, zoomX2 = 0, zoomY2 = 0;

    /** 줌 영역이 설정되어 있는지 여부 */
    public boolean hasZoom() {
        return zoomX1 > 0 && zoomY1 > 0 && zoomX2 > zoomX1 && zoomY2 > zoomY1;
    }

    // color 상수
    public static final int BLACK = 1;
    public static final int WHITE = 2;
    public static final int EMPTY = 0;
}
