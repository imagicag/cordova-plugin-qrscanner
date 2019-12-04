package com.bitpay.cordova.qrscanner;

import android.graphics.Bitmap;

import com.google.firebase.ml.common.FirebaseMLException;

import java.nio.ByteBuffer;

public interface VisionImageProcessor {
    void process(ByteBuffer data, FrameMetadata frameMetadata, GraphicOverlay graphicOverlay)
            throws FirebaseMLException;

    void process(Bitmap bitmap, GraphicOverlay graphicOverlay);

    void stop();
}
