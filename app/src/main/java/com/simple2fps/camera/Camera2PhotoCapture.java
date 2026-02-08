package com.simple2fps.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class Camera2PhotoCapture {
    private static final String TAG = "Camera2PhotoCapture";
    private Activity activity;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private Size photoSize;
    private boolean nightMode = false; 
    private boolean hdrMode = false;

    public interface PhotoCallback {
        void onPhotoSaved(String filepath);
        void onError(String error);
    }

    public Camera2PhotoCapture(Activity activity, CameraDevice camera, Handler handler) {
        this.activity = activity;
        this.cameraDevice = camera;
        this.backgroundHandler = handler;
    }

    public void setPhotoSize(Size size) { this.photoSize = size; }
    public void setNightMode(boolean enabled) { this.nightMode = enabled; }
    public void setHdrMode(boolean enabled) { this.hdrMode = enabled; }

    public void capturePhoto(String customPath, PhotoCallback callback) {
        if (cameraDevice == null) {
            callback.onError("Camera device is null");
            return;
        }
        try {
            if (photoSize == null) photoSize = new Size(1920, 1080);
            imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> processImage(reader, customPath, callback), backgroundHandler);

            SurfaceTexture dummyTexture = new SurfaceTexture(1);
            Surface dummySurface = new Surface(dummyTexture);

            cameraDevice.createCaptureSession(Arrays.asList(dummySurface, imageReader.getSurface()), 
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        runPreCaptureSequence(dummySurface, callback);
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        callback.onError("Session Configuration Failed");
                    }
                }, backgroundHandler);
        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }

    private void runPreCaptureSequence(Surface dummySurface, PhotoCallback callback) {
        try {
            CaptureRequest.Builder previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(dummySurface);
            
            // Standard preview enhancements
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);

            // Give the sensor 1.5s to "see" the light levels before the snap
            backgroundHandler.postDelayed(() -> executeStillCapture(callback), 1500);
        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }

    private void executeStillCapture(PhotoCallback callback) {
        try {
            CaptureRequest.Builder shotBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            shotBuilder.addTarget(imageReader.getSurface());
            
            applyEnhancements(shotBuilder);
            
            shotBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            captureSession.stopRepeating();
            captureSession.capture(shotBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }

    private void applyEnhancements(CaptureRequest.Builder builder) {
        try {
            if (hdrMode) {
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR);
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
            } else if (nightMode) {
                // --- PRO STABLE NIGHT MODE ---
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                
                CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics chars = manager.getCameraCharacteristics(cameraDevice.getId());
                Range<Long> timeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                Range<Integer> isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

                // Target ISO 100 for clean image, Target 1.0s for light
                long targetTime = 1_000_000_000L; 
                int targetIso = 100;

                if (timeRange != null) targetTime = Math.min(targetTime, timeRange.getUpper());
                if (isoRange != null) targetIso = Math.max(targetIso, isoRange.getLower());

                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetTime);
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso);
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, targetTime);
                
                Log.d(TAG, "Stable Night Shot: " + (targetTime/1000000) + "ms at ISO " + targetIso);
            } else {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } catch (Exception e) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        }
    }

    private void processImage(ImageReader reader, String customPath, PhotoCallback callback) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            File file = new File(customPath);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
                callback.onPhotoSaved(file.getAbsolutePath());
            }
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }
}