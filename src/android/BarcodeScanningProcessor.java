package com.bitpay.cordova.qrscanner;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cordovaplugincamerapreview.CameraPreview;
import com.google.android.gms.tasks.Task;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;

public class BarcodeScanningProcessor extends VisionProcessorBase<List<FirebaseVisionBarcode>> {

    private static final String TAG = "BarcodeScanProc";
    private static final int BORDER_DELTA = 5;
    private static final long delayToUnlockCamera = 1000L;

    private final FirebaseVisionBarcodeDetector detector;
    private BarcodeProcessorListener listener;
    private long lastSuccessScan = 0L;

    interface BarcodeProcessorListener {
        void onScanSuccess();
    }


    public BarcodeScanningProcessor(BarcodeProcessorListener listener) {
        this.listener = listener;
        // Note that if you know which format of barcode your app is dealing with, detection will be
        // faster to specify the supported barcode formats one by one, e.g.
        FirebaseVisionBarcodeDetectorOptions options =
                new FirebaseVisionBarcodeDetectorOptions.Builder()
                        .setBarcodeFormats(FirebaseVisionBarcode.FORMAT_QR_CODE)
                        .build();
        detector = FirebaseVision.getInstance().getVisionBarcodeDetector(options);
    }

    @Override
    public void stop() {
        try {
            detector.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception thrown while trying to close Barcode Detector: " + e);
        }
    }

    @Override
    protected Task<List<FirebaseVisionBarcode>> detectInImage(FirebaseVisionImage image) {
        return detector.detectInImage(image);
    }

    @Override
    protected void onSuccess(
            @Nullable Bitmap originalCameraImage,
            @NonNull List<FirebaseVisionBarcode> barcodes,
            @NonNull FrameMetadata frameMetadata,
            @NonNull GraphicOverlay graphicOverlay) {
        if (listener != null) {
            listener.onScanSuccess();
        }
        graphicOverlay.clear();
        long currentTime = Calendar.getInstance().getTimeInMillis();
        if (currentTime - lastSuccessScan < delayToUnlockCamera ) {
            CommonData.getInstance().canTakePhoto = true;
        }
        if (originalCameraImage != null) {
            CameraImageGraphic imageGraphic = new CameraImageGraphic(graphicOverlay, originalCameraImage);
            graphicOverlay.add(imageGraphic);
        }
        for (int i = 0; i < barcodes.size(); ++i) {
            FirebaseVisionBarcode barcode = barcodes.get(i);
            boolean isUrlMatches = barcode.getDisplayValue() != null
                    && barcode.getDisplayValue().contains(CommonData.getInstance().targetUrl);

            if(isUrlMatches
                    && barcode.getCornerPoints() != null) {
                BarcodeGraphic barcodeGraphic = new BarcodeGraphic(graphicOverlay, barcode);
                float currentQualityRatio = 0;
                if(isCodeFullyVisible(barcode, barcodeGraphic)) {
                    lastSuccessScan = Calendar.getInstance().getTimeInMillis();
                    currentQualityRatio = getCurrentQualityRatio(barcode, barcodeGraphic);
                    setBarcodeColor(currentQualityRatio, barcodeGraphic);
                    graphicOverlay.add(barcodeGraphic);
                }
            }
        }
        graphicOverlay.postInvalidate();
    }

    private boolean isCodeFullyVisible(FirebaseVisionBarcode barcode, BarcodeGraphic barcodeGraphic) {
        boolean isAllPointsVisible = true;
        Point[] corners = barcode.getCornerPoints();
        for (Point point : corners) {
            isAllPointsVisible = isAllPointsVisible && isPointVisible(point, barcodeGraphic);
        }
        return isAllPointsVisible;
    }

    private boolean isPointVisible(Point point, BarcodeGraphic barcodeGraphic) {
        return barcodeGraphic.translateX(point.x) > BORDER_DELTA
                && barcodeGraphic.translateX(point.x) < CameraPreview.PREVIEW_X - BORDER_DELTA
                && barcodeGraphic.translateY(point.y) > BORDER_DELTA
                && barcodeGraphic.translateY(point.y) < CameraPreview.PREVIEW_Y - BORDER_DELTA;
    }

    private void setBarcodeColor(float currentQualityRatio, BarcodeGraphic barcodeGraphic) {
        if(currentQualityRatio >= CommonData.getInstance().qualityRatio) {
            barcodeGraphic.setBorderColor(Color.GREEN);
            CommonData.getInstance().canTakePhoto = true;
        } else {
            barcodeGraphic.setBorderColor(Color.RED);
            CommonData.getInstance().canTakePhoto = false;
        }
    }

    private float getCurrentQualityRatio(FirebaseVisionBarcode barcode, BarcodeGraphic barcodeGraphic) {
        Point[] corners = barcode.getCornerPoints();

        float x0 = barcodeGraphic.translateX(corners[0].x);
        float y0 = barcodeGraphic.translateY(corners[0].y);

        float x1 = barcodeGraphic.translateX(corners[1].x);
        float y1 = barcodeGraphic.translateY(corners[1].y);

        float x2 = barcodeGraphic.translateX(corners[2].x);
        float y2 = barcodeGraphic.translateY(corners[2].y);

        double side1 = calcDistanceByPoints(x0, y0, x1, y1);
        double side2 = calcDistanceByPoints(x1, y1, x2, y2);

        return (float) (Math.min(side1, side2) / Math.max(side1, side2));
    }

    private double calcDistanceByPoints(float x0, float y0, float x1, float y1) {
        return Math.sqrt(Math.pow(x1 - x0, 2) + Math.pow(y1 - y0, 2));
    }


    @Override
    protected void onFailure(@NonNull Exception e) {
        Log.e(TAG, "Barcode detection failed " + e);
    }
}
