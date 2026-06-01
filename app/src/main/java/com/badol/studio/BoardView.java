package com.badol.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 바둑판 뷰
 *
 * 좌표 규칙: board[y][x], 1-based (x=1~size, y=1~size)
 * 줌/패닝: 핀치 줌 및 드래그 패닝 항상 활성화
 */
public class BoardView extends View {

    public interface TouchListener     { void onTouch(int x, int y); }
    public interface DoubleTapListener { void onDoubleTap(); }

    // 드래그 착수 모드 활성화 여부 (에디터에서 true, 뷰어에서 false)
    private boolean dragPlaceMode = false;
    // 드래그 중 현재 후보 교차점 (-1이면 비활성)
    private int hoverX = -1, hoverY = -1;
    // 드래그 중 표시할 미리보기 돌 색 (BdkSection.BLACK or WHITE)
    private int hoverColor = 1; // 기본 흑
    // 반투명 미리보기 돌용 Paint
    private final Paint hoverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int size = 19;
    private int[][] board;
    private int[][] moveNum;
    private int lastX = -1, lastY = -1;
    private int triX  = -1, triY  = -1;

    private TouchListener     touchListener;
    private DoubleTapListener doubleTapListener;
    private boolean showNumbers = true;
    private boolean showCoords  = false;

    // 레이아웃 (onSizeChanged에서 계산)
    private float cell;
    private float left;
    private float top;
    private float radius;

    // 줌/패닝
    private float scaleFactor = 1.0f;
    private float panX = 0f, panY = 0f;
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    // 드래그 추적
    private float   lastTouchX = 0f, lastTouchY = 0f;
    private boolean isDragging = false;
    private static final float DRAG_THRESHOLD = 8f;

    // Paint 객체 (재사용)
    private final Paint bgPaint     = new Paint();
    private final Paint linePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coordPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint triPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stonePaint  = new Paint(Paint.ANTI_ALIAS_FLAG); // 돌 그리기용 재사용

    private GestureDetector      gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    public BoardView(Context ctx)                          { super(ctx);       init(); }
    public BoardView(Context ctx, AttributeSet a)          { super(ctx, a);    init(); }
    public BoardView(Context ctx, AttributeSet a, int d)   { super(ctx, a, d); init(); }

    private void init() {
        board   = new int[21][21];
        moveNum = new int[21][21];

        bgPaint.setColor(getResources().getColor(R.color.board_color, null));
        bgPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(Color.BLACK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f);

        dotPaint.setColor(Color.BLACK);
        dotPaint.setStyle(Paint.Style.FILL);

        numPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setFakeBoldText(true);

        coordPaint.setColor(0xFF333333);
        coordPaint.setTextAlign(Paint.Align.CENTER);

        markPaint.setColor(Color.RED);
        markPaint.setStyle(Paint.Style.FILL);

        triPaint.setStyle(Paint.Style.STROKE);
        triPaint.setStrokeWidth(2.5f);

        shadowPaint.setColor(0x44000000);
        shadowPaint.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFF888888);
        borderPaint.setStrokeWidth(1.5f);

        stonePaint.setStyle(Paint.Style.FILL);

        // 반투명 미리보기 돌용 Paint (alpha 150/255 약 60%)
        hoverPaint.setStyle(Paint.Style.FILL);

        gestureDetector = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (touchListener == null || cell == 0) return false;
                    float bx = (e.getX() - panX) / scaleFactor;
                    float by = (e.getY() - panY) / scaleFactor;
                    int ix = Math.round((bx - left) / cell) + 1;
                    int iy = Math.round((by - top)  / cell) + 1;
                    if (ix >= 1 && ix <= size && iy >= 1 && iy <= size) {
                        touchListener.onTouch(ix, iy);
                        return true;
                    }
                    return false;
                }
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (doubleTapListener != null) { doubleTapListener.onDoubleTap(); return true; }
                    return false;
                }
                @Override public boolean onDown(MotionEvent e) { return true; }
            });
        gestureDetector.setIsLongpressEnabled(false); // 롱프레스 비활성화 → 오래 눌러도 onSingleTapConfirmed 정상 호출

        scaleGestureDetector = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float prev = scaleFactor;
                    scaleFactor = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scaleFactor * d.getScaleFactor()));
                    float fx = d.getFocusX(), fy = d.getFocusY();
                    panX = fx - (fx - panX) * (scaleFactor / prev);
                    panY = fy - (fy - panY) * (scaleFactor / prev);
                    clampPan();
                    invalidate();
                    return true;
                }
            });
    }

    // ── 공개 API ──────────────────────────────────────

    public void resetZoom() { scaleFactor = 1.0f; panX = 0f; panY = 0f; invalidate(); }

    public void setBoard(int[][] board)        { this.board   = board;   invalidate(); }
    public void setMoveNumbers(int[][] mn)     { this.moveNum = mn;      invalidate(); }
    public void setLastMove(int x, int y)      { lastX = x; lastY = y; }
    public void clearLastMove()                { lastX = -1; lastY = -1; }
    public void setTriangleMark(int x, int y)  { triX = x; triY = y; invalidate(); }
    public void clearTriangleMark()            { triX = -1; triY = -1; invalidate(); }
    public void setShowNumbers(boolean v)      { showNumbers = v; invalidate(); }
    public void setShowCoords(boolean v)       { showCoords  = v; recalcLayout(); invalidate(); }
    public void setTouchListener(TouchListener l)         { touchListener = l; }
    public void setDoubleTapListener(DoubleTapListener l) { doubleTapListener = l; }

    /** 드래그 착수 모드 설정 (에디터: true, 뷰어: false) */
    public void setDragPlaceMode(boolean enabled) { dragPlaceMode = enabled; }

    /** 드래그 중 표시할 미리보기 돌 색 설정 */
    public void setHoverColor(int color) { hoverColor = color; }

    /**
     * 파일에서 읽은 zoom 영역을 적용한다.
     * x1, y1: 좌상단 (1-based), x2, y2: 우하단 (1-based)
     * x1=0 이면 전체 보기(resetZoom)로 처리
     */
    public void setZoomRegion(int x1, int y1, int x2, int y2) {
        if (x1 <= 0 || y1 <= 0 || x2 <= x1 || y2 <= y1) {
            resetZoom();
            return;
        }
        // 뷰 크기가 아직 0이면 post로 지연 처리
        if (getWidth() == 0 || getHeight() == 0 || cell == 0) {
            final int fx1 = x1, fy1 = y1, fx2 = x2, fy2 = y2;
            post(() -> setZoomRegion(fx1, fy1, fx2, fy2));
            return;
        }
        applyZoomRegion(x1, y1, x2, y2);
    }

    private void applyZoomRegion(int x1, int y1, int x2, int y2) {
        if (cell == 0) return;
        int w = getWidth(), h = getHeight();
        // zoom 영역의 픽셀 좌표 (1-based → 0-based → 픽셀)
        float px1 = left + (x1 - 1) * cell;
        float py1 = top  + (y1 - 1) * cell;
        float px2 = left + (x2 - 1) * cell;
        float py2 = top  + (y2 - 1) * cell;
        float regionW = px2 - px1;
        float regionH = py2 - py1;
        if (regionW <= 0 || regionH <= 0) return;
        // 여백 포함 (10%)
        float padding = Math.min(regionW, regionH) * 0.10f;
        regionW += padding * 2;
        regionH += padding * 2;
        px1 -= padding;
        py1 -= padding;
        // 화면에 꽉 차도록 scaleFactor 계산
        float scaleX = w / regionW;
        float scaleY = h / regionH;
        scaleFactor = Math.min(scaleX, scaleY);
        scaleFactor = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scaleFactor));
        // 영역 중심이 화면 중심에 오도록 panX/panY 계산
        float centerX = px1 + regionW / 2f;
        float centerY = py1 + regionH / 2f;
        panX = w / 2f - centerX * scaleFactor;
        panY = h / 2f - centerY * scaleFactor;
        clampPan();
        invalidate();
    }

    /**
     * 현재 zoom 상태를 BDK 파일 zoom 좌표로 반환한다.
     * 반환값: int[4] = {x1, y1, x2, y2} (1-based)
     * scaleFactor == 1.0이면 전체 보기 → {0, 0, 0, 0} 반환
     */
    public int[] getZoomCoords() {
        if (scaleFactor <= 1.01f || cell == 0) {
            return new int[]{0, 0, 0, 0};
        }
        int w = getWidth(), h = getHeight();
        // 화면 좌상단/우하단을 보드 좌표로 역산
        float bx1 = (0 - panX) / scaleFactor;
        float by1 = (0 - panY) / scaleFactor;
        float bx2 = (w - panX) / scaleFactor;
        float by2 = (h - panY) / scaleFactor;
        // 픽셀 → 보드 좌표 (1-based)
        int ix1 = Math.max(1, (int)Math.floor((bx1 - left) / cell) + 1);
        int iy1 = Math.max(1, (int)Math.floor((by1 - top)  / cell) + 1);
        int ix2 = Math.min(size, (int)Math.ceil((bx2 - left) / cell) + 1);
        int iy2 = Math.min(size, (int)Math.ceil((by2 - top)  / cell) + 1);
        if (ix2 <= ix1 || iy2 <= iy1) return new int[]{0, 0, 0, 0};
        return new int[]{ix1, iy1, ix2, iy2};
    }

    public void setBoardSize(int s) {
        size    = s;
        board   = new int[s + 2][s + 2];
        moveNum = new int[s + 2][s + 2];
        recalcLayout();
        invalidate();
    }

    // ── 레이아웃 계산 ─────────────────────────────────

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int h = MeasureSpec.getSize(hs);
        int hMode = MeasureSpec.getMode(hs);
        // wrap_content 또는 높이가 너비보다 크면 정사각형으로 제한
        int finalH = (hMode == MeasureSpec.EXACTLY && h <= w) ? h : w;
        setMeasuredDimension(w, finalH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        recalcLayout();
    }

    private void recalcLayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int s = Math.min(w, h);
        boolean isLandscape = w > h;
        float margin;
        if (showCoords) {
            margin = s * 0.06f;
        } else if (isLandscape) {
            margin = s * 0.05f;  // 가로 보기: 상하 여백 확보
        } else {
            margin = s * 0.025f; // 세로 보기: 기존 유지
        }
        cell = (s - 2f * margin) / (size - 1);
        float boardSpan = margin * 2 + cell * (size - 1);
        left = (w - boardSpan) / 2f + margin;
        top  = margin;
        radius = cell * 0.46f;
        numPaint.setTextSize(radius * 1.0f);
        coordPaint.setTextSize(cell * 0.38f);
    }

    private void clampPan() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        panX = Math.max(w - w * scaleFactor, Math.min(0f, panX));
        panY = Math.max(h - h * scaleFactor, Math.min(0f, panY));
    }

    // ── 그리기 ────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (cell == 0) recalcLayout();
        if (scaleFactor != 1.0f) {
            canvas.save();
            canvas.translate(panX, panY);
            canvas.scale(scaleFactor, scaleFactor);
            drawBoard(canvas);
            canvas.restore();
        } else {
            drawBoard(canvas);
        }
    }

    private void drawBoard(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        drawGrid(canvas);
        drawStarPoints(canvas);
        if (showCoords) drawCoordinates(canvas);
        drawStones(canvas);
        // 반투명 미리보기 돌은 실제 돌 위에 그려야 하므로 마지막에
        if (dragPlaceMode && hoverX >= 1 && hoverY >= 1
                && board[hoverY][hoverX] == BdkSection.EMPTY) {
            drawHoverStone(canvas);
        }
    }

    /** 드래그 중 반투명 미리보기 돌 */
    private void drawHoverStone(Canvas canvas) {
        float cx = left + (hoverX - 1) * cell;
        float cy = top  + (hoverY - 1) * cell;
        float r  = radius;
        if (hoverColor == BdkSection.BLACK) {
            hoverPaint.setShader(new RadialGradient(
                cx - r*0.3f, cy - r*0.3f, r*1.3f,
                new int[]{0x99666666, 0x99000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        } else {
            hoverPaint.setShader(new RadialGradient(
                cx - r*0.3f, cy - r*0.3f, r*1.3f,
                new int[]{0x99FFFFFF, 0x99CCCCCC}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawCircle(cx, cy, r, hoverPaint);
    }

    private void drawGrid(Canvas canvas) {
        float endX = left + (size - 1) * cell;
        float endY = top  + (size - 1) * cell;
        for (int i = 0; i < size; i++) {
            float x = left + i * cell, y = top + i * cell;
            canvas.drawLine(left, y, endX, y, linePaint);
            canvas.drawLine(x, top, x, endY, linePaint);
        }
    }

    private void drawStarPoints(Canvas canvas) {
        int[] pts;
        if      (size == 19) pts = new int[]{4, 10, 16};
        else if (size == 13) pts = new int[]{4, 7, 10};
        else if (size == 9)  pts = new int[]{3, 5, 7};
        else return;
        float r = cell * 0.1f;
        for (int px : pts)
            for (int py : pts)
                canvas.drawCircle(left + (px-1)*cell, top + (py-1)*cell, r, dotPaint);
    }

    private void drawCoordinates(Canvas canvas) {
        String cols = "ABCDEFGHIJKLMNOPQRS";
        float ts = coordPaint.getTextSize();
        for (int i = 0; i < size; i++) {
            float px = left + i * cell, py = top + i * cell;
            if (i < cols.length()) {
                String c = String.valueOf(cols.charAt(i));
                canvas.drawText(c, px, top - ts * 0.4f, coordPaint);
                canvas.drawText(c, px, top + (size-1)*cell + ts*1.3f, coordPaint);
            }
            String row = String.valueOf(i + 1);
            canvas.drawText(row, left - ts*1.2f,               py + ts*0.35f, coordPaint);
            canvas.drawText(row, left + (size-1)*cell + ts*1.2f, py + ts*0.35f, coordPaint);
        }
    }

    private void drawStones(Canvas canvas) {
        for (int y = 1; y <= size; y++)
            for (int x = 1; x <= size; x++) {
                int c = board[y][x];
                if (c == BdkSection.BLACK || c == BdkSection.WHITE)
                    drawStone(canvas, x, y, c);
            }
    }

    private void drawStone(Canvas canvas, int bx, int by, int color) {
        float cx = left + (bx-1)*cell;
        float cy = top  + (by-1)*cell;
        float r  = radius;

        // 그림자
        canvas.drawCircle(cx + 2f, cy + 2f, r, shadowPaint);

        // 돌 (stonePaint 재사용)
        if (color == BdkSection.BLACK) {
            stonePaint.setShader(new RadialGradient(
                cx - r*0.3f, cy - r*0.3f, r*1.3f,
                new int[]{0xFF666666, 0xFF000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, stonePaint);
            if (showNumbers && moveNum[by][bx] > 0) {
                numPaint.setColor(Color.WHITE);
                canvas.drawText(String.valueOf(moveNum[by][bx]), cx, cy + numPaint.getTextSize()*0.35f, numPaint);
            }
        } else {
            stonePaint.setShader(new RadialGradient(
                cx - r*0.3f, cy - r*0.3f, r*1.3f,
                new int[]{0xFFFFFFFF, 0xFFCCCCCC}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, stonePaint);
            canvas.drawCircle(cx, cy, r, borderPaint);
            if (showNumbers && moveNum[by][bx] > 0) {
                numPaint.setColor(Color.BLACK);
                canvas.drawText(String.valueOf(moveNum[by][bx]), cx, cy + numPaint.getTextSize()*0.35f, numPaint);
            }
        }

        // 마지막 수 표시 (빨간 점)
        if (bx == lastX && by == lastY)
            canvas.drawCircle(cx, cy, r*0.3f, markPaint);

        // 삼각형 표시
        if (bx == triX && by == triY) {
            triPaint.setColor(color == BdkSection.BLACK ? Color.WHITE : Color.BLACK);
            float tr = r * 0.55f;
            Path path = new Path();
            path.moveTo(cx,              cy - tr);
            path.lineTo(cx - tr*0.866f,  cy + tr*0.5f);
            path.lineTo(cx + tr*0.866f,  cy + tr*0.5f);
            path.close();
            canvas.drawPath(path, triPaint);
        }
    }

    // ── 터치 이벤트 ───────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleGestureDetector.onTouchEvent(e);
        int action = e.getActionMasked();

        if (dragPlaceMode && e.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
            // 드래그 착수 모드: DOWN/MOVE/UP 모두 후보 좌표 추적
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    lastTouchX = e.getX();
                    lastTouchY = e.getY();
                    isDragging = false;
                    int[] pt = toBoard(e.getX(), e.getY());
                    hoverX = pt[0]; hoverY = pt[1];
                    invalidate();
                    gestureDetector.onTouchEvent(e); // 더블탭 감지용
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    gestureDetector.onTouchEvent(e); // 더블탭 감지 유지
                    float dx = e.getX() - lastTouchX, dy = e.getY() - lastTouchY;
                    if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD))
                        isDragging = true;
                    int[] pt = toBoard(e.getX(), e.getY());
                    if (pt[0] != hoverX || pt[1] != hoverY) {
                        hoverX = pt[0]; hoverY = pt[1];
                        invalidate();
                    }
                    lastTouchX = e.getX(); lastTouchY = e.getY();
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    int[] pt = toBoard(e.getX(), e.getY());
                    hoverX = -1; hoverY = -1;
                    invalidate();
                    if (isDragging) {
                        // 드래그한 경우: 즉시 착수 (더블탭 기다리지 않음)
                        if (touchListener != null && pt[0] >= 1 && pt[0] <= size
                                && pt[1] >= 1 && pt[1] <= size) {
                            touchListener.onTouch(pt[0], pt[1]);
                        }
                        isDragging = false;
                    } else {
                        // 드래그 없음: gestureDetector에 넘기어 더블탭 vs 단일탭 판단
                        // onSingleTapConfirmed에서 dragPlaceMode 처리하도록 수정함
                        gestureDetector.onTouchEvent(e);
                        isDragging = false;
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    hoverX = -1; hoverY = -1;
                    isDragging = false;
                    invalidate();
                    gestureDetector.onTouchEvent(e);
                    break;
            }
        } else {
            // 기존 모드 (뷰어 등): 단일탭 착수 + 패닝
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = e.getX();
                    lastTouchY = e.getY();
                    isDragging = false;
                    gestureDetector.onTouchEvent(e);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleGestureDetector.isInProgress() && e.getPointerCount() == 1) {
                        float dx = e.getX() - lastTouchX, dy = e.getY() - lastTouchY;
                        if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD))
                            isDragging = true;
                        if (isDragging) {
                            panX += dx; panY += dy;
                            clampPan(); invalidate();
                            lastTouchX = e.getX(); lastTouchY = e.getY();
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!isDragging && !scaleGestureDetector.isInProgress())
                        gestureDetector.onTouchEvent(e);
                    isDragging = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    break;
            }
        }
        return true;
    }

    /** 화면 좌표 → 보드 교차점 좌표 (1-based) */
    private int[] toBoard(float screenX, float screenY) {
        float bx = (screenX - panX) / scaleFactor;
        float by = (screenY - panY) / scaleFactor;
        int ix = Math.round((bx - left) / cell) + 1;
        int iy = Math.round((by - top)  / cell) + 1;
        // 바둑판 바깥 영역은 -1로 반환 (클래핑 안 함)
        if (ix < 1 || ix > size || iy < 1 || iy > size) return new int[]{-1, -1};
        return new int[]{ix, iy};
    }
}
