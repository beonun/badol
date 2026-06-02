package com.badol.studio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** BDK 파일 내 하나의 섹션 (기본도/정해도/오답도 등) */
public class BdkSection {
    public String name = "";          // 섹션 이름 (헤더에서 추출)
    public int boardSize = 19;

    /** 초기 배치 돌: [color, x, y, seq] */
    public List<int[]> initialStones = new ArrayList<>();

    /** 착수 목록: [moveNum(1-based), color, x, y] */
    public List<int[]> moves = new ArrayList<>();

    /** 섹션 전체 주석 */
    public String comment = "";

    /**
     * 돌별 설명: 키="x,y" (1-based), 값=설명 텍스트
     * 예: stoneComments.get("9,11") = "흑1 설명"
     */
    public Map<String, String> stoneComments = new HashMap<>();

    // ── 마커 (SGF: TR/CR/SQ/MA/LB/AR) ──────────────────────────────────────

    /** 삼각형 마커 좌표 목록: [x, y] (SGF: TR) */
    public List<int[]> markersTriangle = new ArrayList<>();

    /** 원 마커 좌표 목록: [x, y] (SGF: CR) */
    public List<int[]> markersCircle = new ArrayList<>();

    /** 사각 마커 좌표 목록: [x, y] (SGF: SQ) */
    public List<int[]> markersSquare = new ArrayList<>();

    /** 엑스 마커 좌표 목록: [x, y] (SGF: MA) */
    public List<int[]> markersX = new ArrayList<>();

    /** 레이블 마커: 키="x,y", 값=레이블 문자열 (SGF: LB) */
    public Map<String, String> markersLabel = new LinkedHashMap<>();

    /** 화살표: [x1, y1, x2, y2] (SGF: AR) */
    public List<int[]> markersArrow = new ArrayList<>();

    /** 선: [x1, y1, x2, y2] (SGF: LN) */
    public List<int[]> markersLine = new ArrayList<>();

    /** 마커 전체 초기화 */
    public void clearMarkers() {
        markersTriangle.clear();
        markersCircle.clear();
        markersSquare.clear();
        markersX.clear();
        markersLabel.clear();
        markersArrow.clear();
        markersLine.clear();
    }

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

    // ── 게임 정보 (SGF 루트 속성, sections[0]에만 저장) ──────────────────────

    /** 흑 기사명 (SGF: PB) */
    public String playerBlack = "";

    /** 백 기사명 (SGF: PW) */
    public String playerWhite = "";

    /** 흑 기사 단급 (SGF: BR) */
    public String rankBlack = "";

    /** 백 기사 단급 (SGF: WR) */
    public String rankWhite = "";

    /** 덤 (SGF: KM, 예: "6.5") */
    public String komi = "";

    /** 결과 (SGF: RE, 예: "B+3.5", "W+R") */
    public String result = "";

    /** 날짜 (SGF: DT, 예: "2024-01-01") */
    public String date = "";

    /** 대회/이벤트명 (SGF: EV) */
    public String event = "";

    /** 장소 (SGF: PC) */
    public String place = "";

    /** 라운드 (SGF: RO) */
    public String round = "";

    // color 상수
    public static final int BLACK = 1;
    public static final int WHITE = 2;
    public static final int EMPTY = 0;
}
