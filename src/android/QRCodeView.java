package com.bitpay.cordova.qrscanner;

import android.view.View;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Paint;
import android.content.Context;
import android.support.annotation.ColorInt;

import com.google.zxing.ResultPoint;

public class QRCodeView extends View {
    private static final int QR_CODE_SIZE = 29; //Version 3(29x29)
    private static final int ANCHOR_SIZE = 7; // 7x7
    private static final int BORDER_WIDTH = 1;
    private final float M1; // default multiplier to calculate corner point
    private final float M2; // multiplier to calculate corner point near small anchor
    private Paint paint;
    private Path path;
    private boolean isDrawAllowed = false;

    public QRCodeView(Context context) {
        super(context);
        paint = new Paint();
        path = new Path();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStrokeWidth(4f);

        int halfSide =  (QR_CODE_SIZE - 1) / 2;
        int anchorHalfSide = (ANCHOR_SIZE - 1) / 2;
        int halfSideWithoutAnchor = halfSide - anchorHalfSide;
        M1 = (float) (halfSide + BORDER_WIDTH) / (float) halfSideWithoutAnchor;
        M2 = (float) (halfSide + BORDER_WIDTH) / (float) (halfSide - ANCHOR_SIZE + 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isDrawAllowed) {
            canvas.drawPath(path, paint);
        }
    }

    public void update(ResultPoint[] newPoints, int xOffset, int yOffset, @ColorInt int color) {
        path.reset();

//        int xOffset = 108;
//        int yOffset = 492;

        paint.setColor(color);

        int anchorX0 = (int) (newPoints[0].getX() + xOffset);
        int anchorY0 = (int) (newPoints[0].getY() + yOffset);

        int anchorX1 = (int) (newPoints[1].getX() + xOffset);
        int anchorY1 = (int) (newPoints[1].getY() + yOffset);

        int anchorX2 = (int) (newPoints[2].getX() + xOffset);
        int anchorY2 = (int) (newPoints[2].getY() + yOffset);

        int anchorX3 = (int) (newPoints[3].getX() + xOffset);//small anchor
        int anchorY3 = (int) (newPoints[3].getY() + yOffset);//small anchor

        int xCenter = (anchorX0 + anchorX2) / 2;
        int yCenter = (anchorY0 + anchorY2) / 2;


        int x0 = (int)(anchorX0 * M1 + (1 - M1) * xCenter);
        int y0 = (int)(anchorY0 * M1 + (1 - M1) * yCenter);

        int x1 = (int)(anchorX1 * M1 + (1 - M1) * xCenter);
        int y1 = (int)(anchorY1 * M1 + (1 - M1) * yCenter);

        int x2 = (int)(anchorX2 * M1 + (1 - M1) * xCenter);
        int y2 = (int)(anchorY2 * M1 + (1 - M1) * yCenter);

        int x3 = (int)(anchorX3 * M2 + (1 - M2) * xCenter);
        int y3 = (int)(anchorY3 * M2 + (1 - M2) * yCenter);

        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x0, y0);

        invalidate();
    }

    public void setDrawAllowed(boolean isDrawAllowed) {
        this.isDrawAllowed = isDrawAllowed;
    }

}
