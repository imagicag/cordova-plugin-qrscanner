package com.bitpay.cordova.qrscanner;

import android.graphics.Point;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Paint;
import android.content.Context;

import androidx.annotation.ColorInt;

import com.google.zxing.ResultPoint;

public class QRCodeView extends View {
    private static final int QR_CODE_SIZE = 29; //Version 3(29x29)
    private static final int ANCHOR_SIZE = 7; // 7x7
    private static final int BORDER_WIDTH = 1;
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

        paint.setColor(color);

        Point[] corners = getCorners(newPoints, xOffset, yOffset);

        path.moveTo(corners[0].x, corners[0].y);
        path.lineTo(corners[1].x, corners[1].y);
        path.lineTo(corners[2].x, corners[2].y);
        path.lineTo(corners[3].x, corners[3].y);
        path.lineTo(corners[0].x, corners[0].y);

        invalidate();
    }

    public void setDrawAllowed(boolean isDrawAllowed) {
        this.isDrawAllowed = isDrawAllowed;
    }

    public static Point[] getCorners(ResultPoint[] newPoints, int xOffset, int yOffset) {
        int halfSide =  (QR_CODE_SIZE - 1) / 2;
        int anchorHalfSide = (ANCHOR_SIZE - 1) / 2;
        int halfSideWithoutAnchor = halfSide - anchorHalfSide;
        float M1 = (float) (halfSide + BORDER_WIDTH) / (float) halfSideWithoutAnchor; // default multiplier to calculate corner point
        float M2 = (float) (halfSide + BORDER_WIDTH) / (float) (halfSide - ANCHOR_SIZE + 1); // multiplier to calculate corner point near small anchor

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

        Point[] corners = new Point[4];
        for(int i = 0; i<corners.length; i++) {
            corners[i] = new Point();
        }
        corners[0].x = (int)(anchorX0 * M1 + (1 - M1) * xCenter);
        corners[0].y = (int)(anchorY0 * M1 + (1 - M1) * yCenter);

        corners[1].x = (int)(anchorX1 * M1 + (1 - M1) * xCenter);
        corners[1].y = (int)(anchorY1 * M1 + (1 - M1) * yCenter);

        corners[2].x = (int)(anchorX2 * M1 + (1 - M1) * xCenter);
        corners[2].y = (int)(anchorY2 * M1 + (1 - M1) * yCenter);

        corners[3].x = (int)(anchorX3 * M2 + (1 - M2) * xCenter);
        corners[3].y = (int)(anchorY3 * M2 + (1 - M2) * yCenter);

        return corners;
    }
}