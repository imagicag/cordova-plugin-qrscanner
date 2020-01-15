package com.bitpay.cordova.qrscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.firebase.FirebaseApp;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
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

import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import android.support.v4.app.ActivityCompat;

import ch.imagic.imsmobile.R;

@SuppressWarnings("deprecation")
public class QRScanner extends CordovaPlugin {

    public static final String TAG = "CameraQRScanner";
    private static final String BG_COLOR = "#222222";
    private CallbackContext callbackContext;
    private static Boolean flashAvailable;
    private boolean lightOn;
    private boolean showing;
    private boolean prepared;
    private String[] permissions = {Manifest.permission.CAMERA};
    //Preview started or paused
    private boolean previewing;

    private boolean switchFlashOn;
    private boolean switchFlashOff;
    private boolean scanning;
    private boolean denied;
    private boolean authorized;
    private boolean restricted;
    private boolean oneTime;
    private boolean keepDenied;
    private boolean appPausedWithActivePreview;

    private boolean scanWasCalled;

    private CameraSource cameraSource = null;
    private BarcodeScanningProcessor barcodeScanningProcessor;
    private GraphicOverlay graphicOverlay;
    private CameraSourcePreview preview;
    private RelativeLayout progress;

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
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        FirebaseApp.initializeApp(cordova.getContext());

        RelativeLayout rootView = (RelativeLayout) LayoutInflater.from(cordova.getContext()).inflate(R.layout.live_preview, null);
        preview = (CameraSourcePreview) rootView.getChildAt(0);
        graphicOverlay = rootView.findViewById(R.id.fireFaceOverlay);

        progress = (RelativeLayout) LayoutInflater.from(cordova.getContext()).inflate(R.layout.progress, null);
        ProgressBar progressBar = (ProgressBar) progress.getChildAt(0);
        progressBar.getIndeterminateDrawable().setColorFilter(Color.parseColor("#2d81ff"), android.graphics.PorterDuff.Mode.MULTIPLY);

        cordova.getActivity().runOnUiThread(() -> {
            ((ViewGroup) webView.getView()
                    .getParent())
                    .addView(rootView);
        });

        CommonData.getInstance().setScannerModule(this);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
        readSettings();
        try {
            ExecutorService threadPool = cordova.getThreadPool();
            switch (action) {
                case "show":
                    threadPool.execute(() -> show(callbackContext));
                    return true;
                case "scan":
                    CommonData.getInstance().setScanCallback(callbackContext);
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
                    return true;
                case "useCamera":
                    threadPool.execute(() -> switchCamera(callbackContext, args));
                    return true;
                case "resumePreview":
                    threadPool.execute(() -> resumePreview(callbackContext));
                    return true;
                case "hide":
                    threadPool.execute(() -> {
                        stopCamera();
                        hide(callbackContext);
                    });
                    return true;
                case "enableLight":
                    threadPool.execute(() -> {
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
                        CommonData.getInstance().currentCameraId = args.getInt(0);
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
            stopCamera();
            hide(null);
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
                        CommonData.getInstance().targetUrl = settings.get(targetUrlIndex);
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
        if (CommonData.getInstance().currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            callbackContext.error(QRScannerError.LIGHT_UNAVAILABLE);
            return;
        }
        if (!prepared) {
            lightOn = toggleLight;
            prepare(callbackContext);
        }
        cordova.getActivity().runOnUiThread(() -> {
            getStatus(callbackContext);
        });
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

    public void switchCamera(CallbackContext callbackContext, JSONArray args) {
        if(callbackContext != null) {
            int cameraId = 0;

            try {
                cameraId = args.getInt(0);
            } catch (JSONException d) {
                callbackContext.error(QRScannerError.UNEXPECTED_ERROR);
            }
            CommonData.getInstance().currentCameraId = cameraId;
        }
        selectCamera();
        stopCamera();
        showProgress();
        startCameraSource();
    }

    public void stopCamera() {
        preview.stop();
    }

    private void selectCamera() {
        if (cameraSource != null) {
            if (CommonData.getInstance().currentCameraId == CameraSource.CAMERA_FACING_FRONT) {
                cameraSource.setFacing(CameraSource.CAMERA_FACING_FRONT);
            } else {
                cameraSource.setFacing(CameraSource.CAMERA_FACING_BACK);
            }
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
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
        cameraSource = new CameraSource(cordova.getActivity(), graphicOverlay);
        cameraSource.setFacing(CameraSource.CAMERA_FACING_BACK);
        barcodeScanningProcessor = new BarcodeScanningProcessor(this::hideProgress);
        cameraSource.setMachineLearningFrameProcessor(barcodeScanningProcessor);

        cordova.getActivity().runOnUiThread(() -> {
            preview.bringToFront();
            preview.setVisibility(View.VISIBLE);
            webView.getView()
                    .bringToFront();
        });

        prepared = true;
        previewing = true;
    }

    private void showProgress() {
        cordova.getActivity().runOnUiThread(() -> {
            progress.bringToFront();
            progress.setVisibility(View.VISIBLE);
        });
    }

    private void hideProgress() {
        cordova.getActivity().runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
        });
    }

    /**
     * Set quality ratio. Current quality ratio is being calculated as width / height.
     * Acceptable range is [ratio : ratio + delta].
     * Delta = 1 - ratio.
     */
    public void setQualityRatio(float ratio) {
        CommonData.getInstance().qualityRatio = ratio;
    }



    // ---- BEGIN EXTERNAL API ----
    private void prepare(CallbackContext callbackContext) {
        if (!prepared) {
            if (CommonData.getInstance().currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
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
            } else if (CommonData.getInstance().currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
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
        if (!prepared) {
            prepare(callbackContext);
            cordova.getActivity().runOnUiThread(() ->
                    new Handler().postDelayed(() -> scan(callbackContext), 1000));
        }
        showProgress();
        selectCamera();
        startCameraSource();
        show(callbackContext);
    }

    private void startCameraSource() {
        if (cameraSource != null) {
            try {
                if (preview == null) {
                    Log.e(TAG, "resume: Preview is null");
                }
                if (graphicOverlay == null) {
                    Log.e(TAG, "resume: graphOverlay is null");
                }
                preview.start(cameraSource, graphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                cameraSource.release();
                cameraSource = null;
            }
        }
    }

    private void show(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            webView.getView().setBackgroundColor(Color.TRANSPARENT);
            showing = true;
        });
    }

    private void resumePreview(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
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

        if (CommonData.getInstance().currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
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
        status.put("currentCamera", Integer.toString(CommonData.getInstance().currentCameraId));

        JSONObject obj = new JSONObject(status);
        PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
        callbackContext.sendPluginResult(result);
    }

    private void hide(CallbackContext callbackContext) {
        destroy(null);
        cordova.getActivity().runOnUiThread(() -> {
            preview.setVisibility(View.GONE);
            webView.getView()
                    .setBackgroundColor(Color.TRANSPARENT);
        });
        prepared = false;
        scanWasCalled = false;
        if (callbackContext != null) {
            getStatus(callbackContext);
        }
    }

    private void destroy(CallbackContext callbackContext) {

        if (lightOn && CommonData.getInstance().currentCameraId != Camera.CameraInfo.CAMERA_FACING_FRONT) {
            switchFlash(false, callbackContext);
        }

        previewing = false;
        scanning = false;
        if (callbackContext != null) {
            getStatus(callbackContext);
        }
    }
}
