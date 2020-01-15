package com.bitpay.cordova.qrscanner;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.common.images.Size;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;

public class CameraUtils {

    private CameraPreviewListener eventListener;
    private static final String TAG = "CameraUtils";
    private static final double ASPECT_TOLERANCE = 0.1;
    private static final int SCALE_FACTOR = 4;
    private static String photoPath;

    private int currentQuality;

    public boolean disableExifHeaderStripping;
    public boolean storeToFile = true;

    public int width;
    public int height;
    public int x;
    public int y;

    private Activity activity;

    public CameraUtils(Activity activity) {
        this.activity = activity;
    }

    public interface CameraPreviewListener {
        void onPictureTaken(String originalPicture);

        void onPictureTakenError(String message);

        void onSnapshotTaken(String originalPicture);

        void onSnapshotTakenError(String message);

        void onFocusSet(int pointX, int pointY);

        void onFocusSetError(String message);

        void onBackButton();

        void onCameraStarted();

        void onDataReady(String data);
    }

    public void setEventListener(CameraUtils.CameraPreviewListener listener) {
        eventListener = listener;
    }


    Bitmap bitmap = null;

    Camera.PictureCallback jpegPictureCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera arg1) {
            bitmap = null;
            try {
                if (!disableExifHeaderStripping) {
                    Matrix matrix = new Matrix();
                    if (CommonData.getInstance().currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        matrix.preScale(-1.0f, 1.0f);
                    }

                    ExifInterface exifInterface = new ExifInterface(new ByteArrayInputStream(data));
                    int rotation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    int rotationInDegrees = exifToDegrees(rotation);

                    if (rotation != 0f) {
                        matrix.preRotate(rotationInDegrees);
                    }

                    // Check if matrix has changed. In that case, apply matrix and override data
                    if (!matrix.isIdentity()) {
                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        bitmap = applyMatrix(bitmap, matrix);
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream);
                        data = outputStream.toByteArray();
                    } else {
                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                    }
                }

                WeakReference<CameraUtils.SavePhotoTask.IPhotoPathProducer> savePhotoCallback = new WeakReference<>(
                        path -> {
                            if (eventListener != null) {
                                eventListener.onPictureTaken(path);
                            }
                        });
                CameraUtils.SavePhotoTask.Args args = new CameraUtils.SavePhotoTask.Args(data, activity.getCacheDir(), storeToFile);
                new CameraUtils.SavePhotoTask(savePhotoCallback).execute(args);
                Log.d(TAG, "CameraPreview pictureTakenHandler called back");
            } catch (OutOfMemoryError e) {
                // most likely failed to allocate memory for rotateBitmap
                Log.e(TAG, "OoME ))", e);
                // failed to allocate memory
                eventListener.onPictureTakenError("Picture too large (memory)");
            } catch (IOException e) {
                Log.e(TAG, "CameraPreview IOException", e);
                eventListener.onPictureTakenError("IO Error when extracting exif");
            } catch (Exception e) {
                Log.e(TAG, "CameraPreview onPictureTaken general exception", e);
            } finally {
                CommonData.getInstance().setCanTakePicture(true);
                CommonData.getInstance().getScannerModule().stopCamera();
            }

            new Thread(() -> {
                if (bitmap != null) {
                    getCodeFromBitmap(Bitmap.createScaledBitmap(bitmap,
                            bitmap.getWidth() / SCALE_FACTOR,   // big images do not supported
                            bitmap.getHeight() / SCALE_FACTOR,
                            false));
                    bitmap = null;
                }
            }).run();

        }
    };

    private void getCodeFromBitmap(Bitmap bitmap) {
        FirebaseVisionBarcodeDetectorOptions options =
                new FirebaseVisionBarcodeDetectorOptions.Builder()
                        .setBarcodeFormats(
                                FirebaseVisionBarcode.FORMAT_QR_CODE)
                        .build();
        FirebaseVisionImage image = FirebaseVisionImage.fromBitmap(bitmap);

        FirebaseVisionBarcodeDetector detector = FirebaseVision.getInstance()
                .getVisionBarcodeDetector(options);

        Task<List<FirebaseVisionBarcode>> result = detector.detectInImage(image)
                .addOnSuccessListener(new OnSuccessListener<List<FirebaseVisionBarcode>>() {
                    @Override
                    public void onSuccess(List<FirebaseVisionBarcode> barcodes) {
                        String message = composePluginResult(false, null, null, 0).toString();
                        for (FirebaseVisionBarcode barcode : barcodes) {
                            Point[] corners = barcode.getCornerPoints();
                            float currentRatio = calcCurrentRatio(corners);
                            boolean isAcceptableQuality = currentRatio >= CommonData.getInstance().qualityRatio;
                            String rawValue = barcode.getRawValue();
                            boolean matchesTargetUrl = (rawValue != null && rawValue.contains(CommonData.getInstance().targetUrl));
                            if (isAcceptableQuality && matchesTargetUrl) {
                                JSONObject scanResult = composePluginResult(true, corners, rawValue, currentRatio);
                                message = scanResult.toString();
                            }
                        }
                        eventListener.onDataReady(message);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                    }
                });
    }

    private float calcCurrentRatio(Point[] points) {
        float x0 = points[0].x;
        float y0 = points[0].y;

        float x1 = points[1].x;
        float y1 = points[1].y;

        float x2 = points[2].x;
        float y2 = points[2].y;

        double side1 = calcDistanceByPoints(x0, y0, x1, y1);
        double side2 = calcDistanceByPoints(x1, y1, x2, y2);

        return (float) (Math.min(side1, side2) / Math.max(side1, side2));
    }

    private float calcDistanceByPoints(float x0, float y0, float x1, float y1) {
        return (float) Math.sqrt(Math.pow(x1 - x0, 2) + Math.pow(y1 - y0, 2));
    }

    private JSONObject composePluginResult(boolean matchesTargetUrl, Point[] points, String resultText, float currentRatio) {
        JSONObject data = new JSONObject();
        if (points == null || points.length < 4) {
            try {
                data.put("fileName", photoPath);
            } catch (Exception e) {
                Log.e(TAG, "Failed to compose a result data", e);
            }
            return data;
        }
        try {
            resultText = matchesTargetUrl ? resultText : "";
            JSONArray coordinates = new JSONArray();
            for (Point point: points) {
                JSONArray jsonPoints = new JSONArray();
                jsonPoints.put(Integer.toString(point.x * SCALE_FACTOR));
                jsonPoints.put(Integer.toString(point.y * SCALE_FACTOR));
                coordinates.put(jsonPoints);
            }

            data.put("fileName", photoPath);
            data.put("coords", coordinates);
            data.put("text", resultText);
            data.put("relation", Float.toString(currentRatio));
        } catch (Exception e) {
            Log.e(TAG, "Failed to compose a result data", e);
        }
        return data;
    }


    public static Bitmap applyMatrix(Bitmap source, Matrix matrix) {
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    Camera.ShutterCallback shutterCallback = new Camera.ShutterCallback() {
        public void onShutter() {
            // do nothing, availabilty of this callback causes default system shutter sound to work
        }
    };

    private static int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        }
        return 0;
    }


    private Camera.Size prepareRequestedSize(final int width, final int height) {
        int newWidth = width;
        int newHeight = height;

        // convert to landscape if necessary
        if (width < height) {
            newWidth = height;
            newHeight = width;
        }

        return CommonData.getInstance().getCamera().new Size(newWidth, newHeight);
    }

    private double prepareAspectRatio(Camera.Size size) {
        double previewAspectRatio = (double) size.width / (double) size.height;

        if (previewAspectRatio < 1.0) {
            // reset ratio to landscape
            Log.d(TAG, "reset ratio to landscape ");
            previewAspectRatio = 1.0 / previewAspectRatio;
        }
        return previewAspectRatio;
    }

    private Camera.Size getOptimalPictureSize(final int width, final int height, final Camera.Size previewSize, final List<Camera.Size> supportedSizes) {
        Log.d(TAG, "----GET OPTIMAL PICTURE SIZE 2----");
        Log.d(TAG, "INPUT PARAMS width : " + width + " height " + height + " preview.width " + previewSize.width + " preview.height " + previewSize.height);
        Camera.Size optimalSize = null;
        Camera.Size requestedSize = prepareRequestedSize(width, height);
        Log.d(TAG, "requested SIZE width: " + requestedSize.width + " previewSize.height: " + requestedSize.height);
        double previewAspectRatio = prepareAspectRatio(previewSize);
        double ratioMinDelta = ASPECT_TOLERANCE;
        Log.d(TAG, "CameraPreview previewAspectRatio " + String.format("%.02f", previewAspectRatio));
        while (optimalSize == null && ratioMinDelta <= 1f) {
            Log.d(TAG, "!!!!!! ratioMinDelta " + ratioMinDelta);
            optimalSize = findOptimalSizeByClosestHeightAndMinimumRatio(supportedSizes, requestedSize, previewAspectRatio, ratioMinDelta, width, height);
            ratioMinDelta = ratioMinDelta + 0.2;
        }
        Log.d(TAG, "RETURNED  SIZE width: " + optimalSize.width + " height: " + optimalSize.height);
        return optimalSize;
    }

    private Camera.Size findOptimalSizeByClosestHeightAndMinimumRatio(final List<Camera.Size> supportedSizes, Camera.Size requestedSize, double previewAspectRatio, double ratioMinDelta, int maxWidth, int maxHeight) {
        Camera.Size optimalSize = null;
        double minHeightDelta = Double.MAX_VALUE;
        for (Camera.Size size : supportedSizes) {
            Log.d(TAG, "ITERATED supported optimalSize width: " + size.width + " height: " + size.height);
            // Perfect match
            if (size.equals(requestedSize)) {
                Log.d(TAG, "GOT EXACT SIZE return ");
                return size;
            }
            if (size.height == 0) {
                continue;
            }
            double iteratedRatio = (double) size.width / size.height;
            Log.d(TAG, "iteratedRatio " + String.format("%.02f", iteratedRatio));
            double ratioDifference = Math.abs(previewAspectRatio - iteratedRatio);
            Log.d(TAG, "ratioDifference " + String.format("%.02f", ratioDifference) + " targetHeight " + requestedSize.height);
            if (ratioDifference <= ratioMinDelta && checkIfSizeLessThanMaximum(size, maxWidth, maxHeight)) {
                double heightDelta = Math.abs(requestedSize.height - size.height);
                Log.d(TAG, "heightDelta " + String.format("%.02f", heightDelta));
                boolean isHeightCloser = heightDelta < minHeightDelta;
                Log.d(TAG, "isHeightCloser " + isHeightCloser);
                if (isHeightCloser) {
                    optimalSize = size;
                    minHeightDelta = heightDelta;
                    Log.d(TAG, "NEW Actual minHeightDelta " + String.format("%.02f", minHeightDelta));
                    Log.d(TAG, "NEW Actual optimalSize width: " + optimalSize.width + " height: " + optimalSize.height);
                }
            } else {
                Log.d(TAG, "ratioDifference too high or picture too big");
            }
        }
        return optimalSize;
    }


    private boolean checkIfSizeLessThanMaximum(Camera.Size size, int maxWidth, int maxHeight) {
        return size.width * size.height <= maxWidth * maxHeight;
    }

    static byte[] rotateNV21(final byte[] yuv,
                             final int width,
                             final int height,
                             final int rotation) {
        if (rotation == 0) return yuv;
        if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
            throw new IllegalArgumentException("0 <= rotation < 360, rotation % 90 == 0");
        }

        final byte[] output = new byte[yuv.length];
        final int frameSize = width * height;
        final boolean swap = rotation % 180 != 0;
        final boolean xflip = rotation % 270 != 0;
        final boolean yflip = rotation >= 180;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                final int yIn = j * width + i;
                final int uIn = frameSize + (j >> 1) * width + (i & ~1);
                final int vIn = uIn + 1;

                final int wOut = swap ? height : width;
                final int hOut = swap ? width : height;
                final int iSwapped = swap ? j : i;
                final int jSwapped = swap ? i : j;
                final int iOut = xflip ? wOut - iSwapped - 1 : iSwapped;
                final int jOut = yflip ? hOut - jSwapped - 1 : jSwapped;

                final int yOut = jOut * wOut + iOut;
                final int uOut = frameSize + (jOut >> 1) * wOut + (iOut & ~1);
                final int vOut = uOut + 1;

                output[yOut] = (byte) (0xff & yuv[yIn]);
                output[uOut] = (byte) (0xff & yuv[uIn]);
                output[vOut] = (byte) (0xff & yuv[vIn]);
            }
        }
        return output;
    }

    public void takePicture(final int width, int height, final int quality) {
        Log.d(TAG, "CameraPreview takePicture width: " + width + ", height: " + height + ", quality: " + quality);
        if (!CommonData.getInstance().isCanTakePicture()) {
            return;
        }

        CommonData.getInstance().setCanTakePicture(false);
        Camera mCamera = CommonData.getInstance().getCamera();

        new Thread() {
            public void run() {
                Camera.Parameters params = mCamera.getParameters();

                Size size = CameraSource.getPictureSize(mCamera, width, height);
                Log.d(TAG, "TAKE PICTURE SIZE width: " + size.getWidth() + ", size.height: " + size.getHeight());
                params.setPictureSize(size.getWidth(), size.getHeight());
                currentQuality = quality;

                if (CommonData.getInstance().currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT && !storeToFile) {
                    // The image will be recompressed in the callback
                    params.setJpegQuality(99);
                } else {
                    params.setJpegQuality(quality);
                }

                mCamera.setParameters(params);


                mCamera.startPreview();
                mCamera.takePicture(shutterCallback, null, jpegPictureCallback);
            }
        }.start();
    }


    private Rect calculateTapArea(float x, float y, float coefficient) {
        if (x < 100) {
            x = 100;
        }
        if (x > width - 100) {
            x = width - 100;
        }
        if (y < 100) {
            y = 100;
        }
        if (y > height - 100) {
            y = height - 100;
        }
        return new Rect(
                Math.round((x - 100) * 2000 / width - 1000),
                Math.round((y - 100) * 2000 / height - 1000),
                Math.round((x + 100) * 2000 / width - 1000),
                Math.round((y + 100) * 2000 / height - 1000)
        );
    }

    static class SavePhotoTask extends AsyncTask<CameraUtils.SavePhotoTask.Args, String, String> {

        private final WeakReference<CameraUtils.SavePhotoTask.IPhotoPathProducer> photoPathProducer;

        SavePhotoTask(WeakReference<CameraUtils.SavePhotoTask.IPhotoPathProducer> photoPathProducer) {
            this.photoPathProducer = photoPathProducer;
        }

        private File preparePhotoFile(File cacheDirFile) {
            String generatedName = "/cpcp_capture_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".jpg";
            return new File(cacheDirFile, generatedName);
        }

        @Override
        protected String doInBackground(CameraUtils.SavePhotoTask.Args... args) {
            CameraUtils.SavePhotoTask.Args arg = args[0];
            if (arg.storeToFile) {
                return storeToFile(arg);
            } else {
                return Base64.encodeToString(arg.photoToWrite, Base64.NO_WRAP);
            }
        }

        private String storeToFile(CameraUtils.SavePhotoTask.Args args) {
            File photo = preparePhotoFile(args.cacheDir);

            if (photo.exists()) {
                photo.delete();
            }

            try {
                FileOutputStream fos = new FileOutputStream(photo.getPath());

                fos.write(args.photoToWrite);
                // fos.flush();
                fos.close();
            } catch (java.io.IOException e) {
                Log.e("PictureDemo", "Exception in photoCallback", e);
            }
            photoPath = photo.getAbsolutePath();
            return photo.getAbsolutePath();
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            CameraUtils.SavePhotoTask.IPhotoPathProducer iPhotoPathProducer = photoPathProducer.get();
            if (iPhotoPathProducer != null) {
                iPhotoPathProducer.onPhotoResult(s);
            }
        }

        interface IPhotoPathProducer {
            void onPhotoResult(String path);
        }

        static class Args {
            private boolean storeToFile;
            private byte[] photoToWrite;
            private File cacheDir;

            Args(byte[] photoToWrite, File cacheDir, boolean storeToFile) {
                this.photoToWrite = photoToWrite;
                this.cacheDir = cacheDir;
                this.storeToFile = storeToFile;
            }
        }

    }
}
