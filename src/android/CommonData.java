package com.bitpay.cordova.qrscanner;

import android.hardware.Camera;

import org.apache.cordova.CallbackContext;

public class CommonData {
    private static CommonData instance;

    public int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    public boolean canTakePhoto = true;
    /**
     * Quality ratio (width to height ). It is needed to deffer QR code (square) from other bar codes.
     */
    public float qualityRatio = 0.9f;
    public String targetUrl = "imagic.ch";
    public String flashMode = "off";

    private Camera camera;
    private boolean canTakePicture = true;
    private CallbackContext scanCallback;
    private QRScanner scannerModule;

    private CommonData() { }

    public static CommonData getInstance() {
        if(instance == null) {
            instance = new CommonData();
        }
        return instance;
    }

    public Camera getCamera() {
        return camera;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public boolean isCanTakePicture() {
        return canTakePicture;
    }

    public void setCanTakePicture(boolean canTakePicture) {
        this.canTakePicture = canTakePicture;
    }

    public CallbackContext getScanCallback() {
        return scanCallback;
    }

    public void setScanCallback(CallbackContext scanCallback) {
        this.scanCallback = scanCallback;
    }

    public QRScanner getScannerModule() {
        return scannerModule;
    }

    public void setScannerModule(QRScanner scannerModule) {
        this.scannerModule = scannerModule;
    }
}
