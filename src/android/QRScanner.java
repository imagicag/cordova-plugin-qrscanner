package com.bitpay.cordova.qrscanner;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import io.ionic.starter.R;


@SuppressWarnings("deprecation")
public class QRScanner extends CordovaPlugin implements BarcodeCallback {

    public static final String TAG = "CameraQRScanner";
    private CallbackContext callbackContext;
    private boolean cameraClosing;
    private static Boolean flashAvailable;
    private boolean lightOn;
    private boolean showing;
    private boolean prepared;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private String[] permissions = {Manifest.permission.CAMERA};
    //Preview started or paused
    private boolean previewing;

    private BarcodeView mBarcodeView;
    private View frameGreen;
    private View frameRed;

    /**
     * Quality ratio (width to height ). It is needed to deffer QR code (square) from other bar codes.
     */
    private float qualityRatio = 0.9f;

    private boolean switchFlashOn;
    private boolean switchFlashOff;
    private boolean cameraPreviewing;
    private boolean scanning;
    private CallbackContext nextScanCallback;
    private boolean shouldScanAgain;
    private boolean denied;
    private boolean authorized;
    private boolean restricted;
    private boolean oneTime;
    private boolean keepDenied;
    private boolean appPausedWithActivePreview;


    private boolean scanWasCalled;
    private String targetUrl = "imagic.ch";

    static class QRScannerError {
        private static final int UNEXPECTED_ERROR = 0,
                CAMERA_ACCESS_DENIED = 1,
                CAMERA_ACCESS_RESTRICTED = 2,
                BACK_CAMERA_UNAVAILABLE = 3,
                FRONT_CAMERA_UNAVAILABLE = 4,
                CAMERA_UNAVAILABLE = 5,
                SCAN_CANCELED = 6,
                LIGHT_UNAVAILABLE = 7,
                OPEN_SETTINGS_UNAVAILABLE = 8;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        Log.d(TAG, "action " + action);
        this.callbackContext = callbackContext;
        readSettings();
        try {
            ExecutorService threadPool = cordova.getThreadPool();
            switch (action) {
                case "show":
                    threadPool.execute(() -> show(callbackContext));
                    return true;
                case "scan":
                    if (!scanWasCalled) {
                        scan(callbackContext);
                    }
                    scanWasCalled = true;
                    return true;
                case "cancelScan":
                    return true;
                case "openSettings":
                    threadPool.execute(() -> openSettings(callbackContext));
                    return true;
                case "pausePreview":
                    threadPool.execute(() -> pausePreview(callbackContext));
                    return true;
                case "useCamera":
                    threadPool.execute(() -> switchCamera(callbackContext, args));
                    return true;
                case "resumePreview":
                    threadPool.execute(() -> resumePreview(callbackContext));
                    return true;
                case "hide":
                    threadPool.execute(() -> hide(callbackContext));
                    return true;
                case "enableLight":
                    threadPool.execute(() -> {
                        while (cameraClosing) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignore) {
                                Log.e(TAG, "execute: ", ignore);
                            }
                        }
                        switchFlashOn = true;
                        if (hasFlash()) {
                            if (!hasPermission()) {
                                requestPermission(33);
                            } else
                                enableLight(callbackContext);
                        } else {
                            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
                        }
                    });
                    return true;
                case "disableLight":
                    threadPool.execute(() -> {
                        switchFlashOff = true;
                        if (hasFlash()) {
                            if (!hasPermission()) {
                                requestPermission(33);
                            } else
                                disableLight(callbackContext);
                        } else {
                            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
                        }
                    });
                    return true;
                case "prepare":
                    try {
                        currentCameraId = args.getInt(0);
                    } catch (JSONException e) {
                        Log.i(TAG, "Failed to execute action 'prepare'", e);
                    }
                    prepare(callbackContext);
                    return true;
                case "destroy":
                    destroy(callbackContext);
                    scanWasCalled = false;
                    return true;
                case "getStatus":
                    threadPool.execute(() -> getStatus(callbackContext));
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            callbackContext.error(QRScannerError.UNEXPECTED_ERROR);
            return false;
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        if (previewing) {
            appPausedWithActivePreview = true;
            hide(callbackContext);
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        if (appPausedWithActivePreview) {
            appPausedWithActivePreview = false;
            show(callbackContext);
            cordova.getActivity().runOnUiThread(() ->
                    new Handler().postDelayed(() -> scan(callbackContext), 1000));
        }
    }

    private void readSettings() {
        cordova.getThreadPool()
                .submit(() -> {
                    String path = cordova.getContext()
                            .getApplicationInfo()
                            .dataDir + "/files";

                    File file = new File(path, "qualityParameter.txt");

                    try (BufferedReader buffer = new BufferedReader(new FileReader(file))) {
                        List<String> settings = new ArrayList<>();
                        while (buffer.ready()) {
                            String line = buffer.readLine();
                            settings.add(line);
                        }

                        int qualityRatioIndex = 0;
                        float ratio = Float.parseFloat(settings.get(qualityRatioIndex));
                        setQualityRatio(ratio);

                        int targetUrlIndex = 1;
                        targetUrl = settings.get(targetUrlIndex);
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to read quality ratio from disk", e);
                    }
                });
    }

    private boolean hasFlash() {
        if (flashAvailable == null) {
            flashAvailable = false;
            PackageManager packageManager = cordova.getActivity().getPackageManager();
            for (FeatureInfo feature : packageManager.getSystemAvailableFeatures()) {
                if (PackageManager.FEATURE_CAMERA_FLASH.equalsIgnoreCase(feature.name)) {
                    flashAvailable = true;
                    break;
                }
            }
        }
        return flashAvailable;
    }

    private void switchFlash(boolean toggleLight, CallbackContext callbackContext) {
        try {
            if (hasFlash()) {
                doswitchFlash(toggleLight, callbackContext);
            } else {
                callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
            }
        } catch (Exception e) {
            lightOn = false;
            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
        }
    }

    private String boolToNumberString(Boolean bool) {
        return bool ? "1" : "0";
    }

    private void doswitchFlash(boolean toggleLight, CallbackContext callbackContext) throws IOException, CameraAccessException {        //No flash for front facing cameras
        if (getCurrentCameraId() == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
            return;
        }
        if (!prepared) {
            lightOn = toggleLight;
            prepare(callbackContext);
        }
        cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.setTorch(toggleLight);
                lightOn = toggleLight;
            }
            getStatus(callbackContext);
        });
    }

    public int getCurrentCameraId() {
        return currentCameraId;
    }

    private boolean canChangeCamera() {
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (Camera.CameraInfo.CAMERA_FACING_FRONT == info.facing) {
                return true;
            }
        }
        return false;
    }

    private void switchCamera(CallbackContext callbackContext, JSONArray args) {
        int cameraId = 0;

        try {
            cameraId = args.getInt(0);
        } catch (JSONException d) {
            callbackContext.error(QRScannerError.UNEXPECTED_ERROR);
        }
        currentCameraId = cameraId;
        if (scanning) {
            scanning = false;
            prepared = false;
            if (cameraPreviewing) {
                cordova.getActivity().runOnUiThread(() -> {
                    ((ViewGroup) mBarcodeView.getParent()).removeView(mBarcodeView);
                    cameraPreviewing = false;
                });
            }
            closeCamera();
            prepare(callbackContext);
            scan(nextScanCallback);
        } else {
            prepare(callbackContext);
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        oneTime = false;
        if (requestCode == 33) {
            // for each permission check if the user granted/denied them
            // you may want to group the rationale in a single dialog,
            // this is just an example
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(cordova.getActivity(), permission);
                    if (!showRationale) {
                        // user denied flagging NEVER ASK AGAIN
                        denied = true;
                        authorized = false;
                        callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
                        return;
                    } else {
                        authorized = false;
                        denied = false;
                        callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
                        return;
                    }
                } else if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    authorized = true;
                    denied = false;
                    switch (requestCode) {
                        case 33:
                            if (switchFlashOn && !scanning && !switchFlashOff)
                                switchFlash(true, callbackContext);
                            else if (switchFlashOff && !scanning)
                                switchFlash(false, callbackContext);
                            else {
                                setupCamera(callbackContext);
                                if (!scanning)
                                    getStatus(callbackContext);
                            }
                            break;
                    }
                } else {
                    authorized = false;
                    denied = false;
                    restricted = false;
                }
            }
        }
    }

    public boolean hasPermission() {
        for (String p : permissions) {
            if (!PermissionHelper.hasPermission(this, p)) {
                return false;
            }
        }
        return true;
    }

    private void requestPermission(int requestCode) {
        PermissionHelper.requestPermissions(this, requestCode, permissions);
    }

    private void closeCamera() {
        cameraClosing = true;
        cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.pause();
            }

            cameraClosing = false;
        });
    }

    private boolean hasCamera() {
        return cordova.getActivity()
                .getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    private boolean hasFrontCamera() {
        return cordova.getActivity()
                .getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
    }

    private void setupCamera(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            // Create our Preview view and set it as the content of our activity.
            if (mBarcodeView == null) {
                mBarcodeView = new BarcodeView(cordova.getActivity());
            } else {
                mBarcodeView.setVisibility(View.VISIBLE);
            }

            //Configure the decoder
            ArrayList<BarcodeFormat> formatList = new ArrayList<BarcodeFormat>();
            formatList.add(BarcodeFormat.QR_CODE);
            mBarcodeView.setDecoderFactory(new DefaultDecoderFactory(formatList, null, null));

            //Configure the camera (front/back)
            CameraSettings settings = new CameraSettings();
            settings.setRequestedCameraId(getCurrentCameraId());
            mBarcodeView.setCameraSettings(settings);

            FrameLayout.LayoutParams cameraPreviewParams =
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);


            if (mBarcodeView.getParent() == null) {
                ((ViewGroup) webView.getView()
                        .getParent())
                        .addView(mBarcodeView, cameraPreviewParams);
            }

            cameraPreviewing = true;
            webView.getView()
                    .bringToFront();

            mBarcodeView.resume();
        });
        prepared = true;
        previewing = true;
    }

    /**
     * Set quality ratio. Current quality ratio is being calculated as width / height.
     * Acceptable range is [ratio : ratio + delta].
     * Delta = 1 - ratio.
     */
    public void setQualityRatio(float ratio) {
        qualityRatio = ratio;
    }

    @Override
    public void possibleResultPoints(List<ResultPoint> resultPoints) {
        initFrames();
    }

    @Override
    public void barcodeResult(BarcodeResult result) {
        if (nextScanCallback == null) {
            return;
        }
        initFrames();
        ResultPoint[] points = result.getResultPoints();
        float currentRatio = calcCurrentRatio(points);
        boolean isAcceptableQuality = qualityRatio <= currentRatio && currentRatio <= (1 - qualityRatio + 1);
        String resultText = result.getText();
        boolean matchesTargetUrl = matchesTargetUrl(resultText);
        if (isAcceptableQuality && matchesTargetUrl) {
            clearRedFrame();
            drawGreenFrame(result);
        } else if (!matchesTargetUrl) {
            clearGreenFrame();
            clearRedFrame();
        } else {
            clearGreenFrame();
            drawRedFrame(result);
        }

        clearFramesDelayed();

        boolean isRecognized = resultText != null;
        if (isRecognized) {
            scanning = true;
            JSONObject scanResult = composePluginResult(matchesTargetUrl, result, currentRatio);
            String message = scanResult.toString();
            nextScanCallback.success(message);
        }
    }

    private boolean matchesTargetUrl(String resultText) {
        return resultText != null && resultText.contains(targetUrl);
    }

    private JSONObject composePluginResult(boolean matchesTargetUrl, BarcodeResult result, float currentRatio) {
        ResultPoint[] points = result.getResultPoints();
        JSONObject data = new JSONObject();
        if (points.length < 4) {
            return data;
        }
        try {
            String resultText = matchesTargetUrl ? result.getText() : "";
            JSONArray coordinates = new JSONArray();
            coordinates.put(points[0]);
            coordinates.put(points[1]);
            coordinates.put(points[2]);
            coordinates.put(points[3]);

            data.put("coords", coordinates);
            data.put("text", resultText);
            data.put("relation", currentRatio);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compose a result data", e);
        }
        return data;
    }

    private void clearFramesDelayed() {
        webView.getView()
                .getHandler()
                .postDelayed(() -> {
                    clearRedFrame();
                    clearGreenFrame();
                }, 4_000L);
    }

    private float calcCurrentRatio(ResultPoint[] points) {
        float x0 = points[0].getX();
        float y0 = points[0].getY();

        float x1 = points[1].getX();
        float y1 = points[1].getY();

        float x2 = points[2].getX();
        float y2 = points[2].getY();

        double side1 = calcDistanceByPoints(x0, y0, x1, y1);
        double side2 = calcDistanceByPoints(x1, y1, x2, y2);

        float currentRatio = (float) (Math.max(side1, side2) / Math.min(side1, side2));
        return currentRatio;
    }

    private float calcDistanceByPoints(float x0, float y0, float x1, float y1) {
        return (float) Math.sqrt(Math.pow(x1 - x0, 2) + Math.pow(y1 - y0, 2));
    }

    private void initFrames() {
        if (frameGreen != null && frameRed != null) {
            return;
        }
        Context context = webView.getContext();
        Resources resources = context.getResources();

        frameGreen = new View(context);
        frameGreen.setBackground(resources.getDrawable(R.drawable.frame_green));
        frameGreen.setVisibility(View.INVISIBLE);

        frameRed = new View(context);
        frameRed.setBackground(resources.getDrawable(R.drawable.frame_red));
        frameRed.setVisibility(View.INVISIBLE);

        ViewGroup parent = (ViewGroup) webView.getView()
                .getParent();
        parent.addView(frameGreen);
        parent.addView(frameRed);
    }

    private void drawGreenFrame(BarcodeResult result) {
        FrameLayout.LayoutParams params = calcFrameLayoutParams(result);
        frameGreen.setLayoutParams(params);
        float rotation = calcRotation(result);
        frameGreen.setRotation(rotation);
        frameGreen.setVisibility(View.VISIBLE);
    }

    private void drawRedFrame(BarcodeResult result) {
        FrameLayout.LayoutParams params = calcFrameLayoutParams(result);
        frameRed.setLayoutParams(params);
        float rotation = calcRotation(result);
        frameRed.setRotation(rotation);
        frameRed.setVisibility(View.VISIBLE);
    }

    private float calcRotation(BarcodeResult result) {
        ResultPoint[] points = result.getResultPoints();
        float dx = points[1].getX() - points[0].getX();
        float dy = points[0].getY() - points[1].getY();
        double atan = Math.atan(dx / dy);
        return (float) Math.toDegrees(atan);
    }

    private FrameLayout.LayoutParams calcFrameLayoutParams(BarcodeResult result) {
        Bitmap bitmap = result.getBitmap();
        int bitmapScaleFactor = result.getBitmapScaleFactor();
        int bitmapDensity = bitmap.getDensity();
        Context context = webView.getContext();
        float displayDensity = context.getResources()
                .getDisplayMetrics()
                .density;
        float densityAdjustment = displayDensity / bitmapScaleFactor;

        ResultPoint[] points = result.getResultPoints();
        int left = Math.round(calcFrameLeft(points));
        int top = Math.round(calcFrameTop(points) + bitmapDensity / densityAdjustment);

        float x0 = points[0].getX();
        float y0 = points[0].getY();

        float x1 = points[1].getX();
        float y1 = points[1].getY();

        float x2 = points[2].getX();
        float y2 = points[2].getY();

        int distance1 = Math.round(calcDistanceByPoints(x0, y0, x2, y2));
        int side1 = Math.round(distance1 * displayDensity / bitmapScaleFactor);

        int side;
        if (points.length > 3) {
            float x3 = points[3].getX();
            float y3 = points[3].getY();

            int distance2 = Math.round(calcDistanceByPoints(x1, y1, x3, y3));
            int side2 = Math.round(distance2 * displayDensity / bitmapScaleFactor);

            side = Math.max(side1, side2);
        } else {
            side = side1;
        }

        float barcodeViewWidth = mBarcodeView.getWidth();
        float barcodeViewHeight = mBarcodeView.getHeight();
        float barCodeViewRatio = barcodeViewHeight / barcodeViewWidth;

        side *= (barCodeViewRatio < 1.6) ? 1.2f : 1.15;
        if (barCodeViewRatio < 1.6f) {
            left *= densityAdjustment;
            top *= 1.25f;
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(side, side);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private int calcFrameLeft(ResultPoint[] resultPoints) {
        float x0 = resultPoints[0].getX();
        float x1 = resultPoints[1].getX();
        float min = Math.min(x0, x1);
        return Math.round(min);
    }

    private int calcFrameTop(ResultPoint[] points) {
        float y1 = points[1].getY();
        float y2 = points[2].getY();
        float min = Math.min(y1, y2);
        return Math.round(min);
    }

    private void clearGreenFrame() {
        frameGreen.setVisibility(View.INVISIBLE);
    }

    private void clearRedFrame() {
        frameRed.setVisibility(View.INVISIBLE);
    }

    // ---- BEGIN EXTERNAL API ----
    private void prepare(CallbackContext callbackContext) {
        if (!prepared) {
            if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                if (hasCamera()) {
                    if (!hasPermission()) {
                        requestPermission(33);
                    } else {
                        setupCamera(callbackContext);
                        if (!scanning)
                            getStatus(callbackContext);
                    }
                } else {
                    callbackContext.error(QRScannerError.BACK_CAMERA_UNAVAILABLE);
                }
            } else if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                if (hasFrontCamera()) {
                    if (!hasPermission()) {
                        requestPermission(33);
                    } else {
                        setupCamera(callbackContext);
                        if (!scanning)
                            getStatus(callbackContext);
                    }
                } else {
                    callbackContext.error(QRScannerError.FRONT_CAMERA_UNAVAILABLE);
                }
            } else {
                callbackContext.error(QRScannerError.CAMERA_UNAVAILABLE);
            }
        }
    }

    private void scan(CallbackContext callbackContext) {
        scanning = true;
        shouldScanAgain = true;
        Activity activity = cordova.getActivity();

        if (prepared) {
            activity.runOnUiThread(() -> mBarcodeView.resume());
            previewing = true;
        } else {
            prepare(callbackContext);
        }

        nextScanCallback = callbackContext;
        activity.runOnUiThread(() -> mBarcodeView.decodeContinuous(this));
    }

    private void show(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            webView.getView().setBackgroundColor(Color.argb(1, 0, 0, 0));
            showing = true;
            getStatus(callbackContext);
        });
    }

    private void pausePreview(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.pause();
                previewing = false;
                if (lightOn)
                    lightOn = false;
            }


        });
    }

    private void resumePreview(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.resume();
                previewing = true;
                if (switchFlashOn)
                    lightOn = true;
            }

            if (callbackContext != null)
                getStatus(callbackContext);
        });
    }

    private void enableLight(CallbackContext callbackContext) {
        lightOn = true;
        if (hasPermission()) {
            switchFlash(true, callbackContext);
        } else {
            callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
        }
    }

    private void disableLight(CallbackContext callbackContext) {
        lightOn = false;
        switchFlashOn = false;
        if (hasPermission()) {
            switchFlash(false, callbackContext);
        } else {
            callbackContext.error(QRScannerError.CAMERA_ACCESS_DENIED);
        }
    }

    private void openSettings(CallbackContext callbackContext) {
        oneTime = true;
        if (denied)
            keepDenied = true;
        try {
            denied = false;
            authorized = false;
            boolean shouldPrepare = prepared;
            boolean shouldFlash = lightOn;
            boolean shouldShow = showing;
            if (prepared) {
                destroy(callbackContext);
            }
            lightOn = false;
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri = Uri.fromParts("package", cordova.getActivity().getPackageName(), null);
            intent.setData(uri);
            cordova.getActivity().getApplicationContext().startActivity(intent);
            getStatus(callbackContext);
            if (shouldPrepare) {
                prepare(callbackContext);
            }
            if (shouldFlash) {
                enableLight(callbackContext);
            }
            if (shouldShow) {
                show(callbackContext);
            }
        } catch (Exception e) {
            callbackContext.error(QRScannerError.OPEN_SETTINGS_UNAVAILABLE);
            Log.e(TAG, "Failed to open settings", e);
        }
    }

    private void getStatus(CallbackContext callbackContext) {
        if (oneTime) {
            boolean authorizationStatus = hasPermission();

            authorized = false;
            if (authorizationStatus) {
                authorized = true;
            }

            denied = keepDenied && !authorized;

            //No applicable API
            restricted = false;
        }
        boolean canOpenSettings = true;

        boolean canEnableLight = hasFlash();

        if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            canEnableLight = false;
        }

        Map<String, String> status = new HashMap<>();
        status.put("authorized", boolToNumberString(authorized));
        status.put("denied", boolToNumberString(denied));
        status.put("restricted", boolToNumberString(restricted));
        status.put("prepared", boolToNumberString(prepared));
        status.put("scanning", boolToNumberString(scanning));
        status.put("previewing", boolToNumberString(previewing));
        status.put("showing", boolToNumberString(showing));
        status.put("lightEnabled", boolToNumberString(lightOn));
        status.put("canOpenSettings", boolToNumberString(canOpenSettings));
        status.put("canEnableLight", boolToNumberString(canEnableLight));
        status.put("canChangeCamera", boolToNumberString(canChangeCamera()));
        status.put("currentCamera", Integer.toString(getCurrentCameraId()));

        JSONObject obj = new JSONObject(status);
        PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
        callbackContext.sendPluginResult(result);
    }

    private void hide(CallbackContext callbackContext) {
        destroy(null);
        clearGreenFrame();
        clearRedFrame();
        cordova.getActivity().runOnUiThread(() -> mBarcodeView.setVisibility(View.GONE));
        prepared = false;
        scanWasCalled = false;
        getStatus(callbackContext);
    }

    private void makeOpaque() {
        cordova.getActivity()
                .runOnUiThread(() -> webView.getView()
                        .setBackgroundColor(Color.TRANSPARENT));
        showing = false;
    }

    private void destroy(CallbackContext callbackContext) {
        cordova.getActivity()
                .runOnUiThread(() -> mBarcodeView.pause());

        if (lightOn && currentCameraId != Camera.CameraInfo.CAMERA_FACING_FRONT) {
            switchFlash(false, callbackContext);
        }

        previewing = false;
        scanning = false;
        cameraPreviewing = false;
        if (callbackContext != null) {
            getStatus(callbackContext);
        }
    }
}
