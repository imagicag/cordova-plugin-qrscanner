package com.bitpay.cordova.qrscanner;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;

public class BarcodeGraphic extends GraphicOverlay.Graphic {

    private static final float STROKE_WIDTH = 4.0f;

    private final Paint barcodePaint;
    private final Path path;
    private final FirebaseVisionBarcode barcode;
    private int borderColor = Color.GREEN;

    BarcodeGraphic(GraphicOverlay overlay, FirebaseVisionBarcode barcode) {
        super(overlay);

        this.barcode = barcode;

        barcodePaint = new Paint();
        barcodePaint.setColor(borderColor);
        barcodePaint.setStyle(Paint.Style.STROKE);
        barcodePaint.setStrokeWidth(STROKE_WIDTH);

        path = new Path();
    }

    /**
     * Draws the barcode block annotations for position, size, and raw value on the supplied canvas.
     */
    @Override
    public void draw(Canvas canvas) {
        if (barcode == null) {
            throw new IllegalStateException("Attempting to draw a null barcode.");
        }

        path.reset();

        Point[] corners = barcode.getCornerPoints();

        path.moveTo(translateX(corners[0].x), translateY(corners[0].y));
        path.lineTo(translateX(corners[1].x), translateY(corners[1].y));
        path.lineTo(translateX(corners[2].x), translateY(corners[2].y));
        path.lineTo(translateX(corners[3].x), translateY(corners[3].y));
        path.lineTo(translateX(corners[0].x), translateY(corners[0].y));

        canvas.drawPath(path, barcodePaint);
    }

    public void setBorderColor(int borderColor) {
        this.borderColor = borderColor;
        barcodePaint.setColor(borderColor);
    }
}
