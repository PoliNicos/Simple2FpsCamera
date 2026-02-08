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

    /**
     * Captures a photo with a mandatory "warm-up" period for light adjustment.
     */
    public void capturePhoto(String customPath, PhotoCallback callback) {
        if (cameraDevice == null) {
            if (callback != null) callback.onError("Camera device is null");
            return;
        }

        try {
            if (photoSize == null) photoSize = new Size(1920, 1080);

            // 1. Prepare the ImageReader for the final JPG
            imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> processImage(reader, customPath, callback), backgroundHandler);

            // 2. Prepare a dummy surface for Auto-Exposure warm-up
            SurfaceTexture dummyTexture = new SurfaceTexture(1);
            Surface dummySurface = new Surface(dummyTexture);

            // 3. Create Session with BOTH surfaces
            cameraDevice.createCaptureSession(Arrays.asList(dummySurface, imageReader.getSurface()), 
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        runPreCaptureSequence(dummySurface, callback);
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        callback.onError("Failed to configure capture session");
                    }
                }, backgroundHandler);

        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }

    /**
     * Starts a preview on a hidden surface to let the AE/AF stabilize.
     */
    private void runPreCaptureSequence(Surface dummySurface, PhotoCallback callback) {
        try {
            CaptureRequest.Builder previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(dummySurface);
            
            // Apply Night/HDR enhancements to the preview so the sensor knows what's coming
            applyEnhancements(previewBuilder);

            // Start streaming frames internally
            captureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);

            Log.d(TAG, "Warming up sensor for 1500ms...");

            // 4. WAIT 1.5 Seconds for the "Xiaomi-style" light calculation
            backgroundHandler.postDelayed(() -> {
                executeStillCapture(callback);
            }, 1500);

        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }

    /**
     * The actual shutter click.
     */
    private void executeStillCapture(PhotoCallback callback) {
        try {
            CaptureRequest.Builder shotBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            shotBuilder.addTarget(imageReader.getSurface());
            
            applyEnhancements(shotBuilder);
            
            // Higher priority for the final shot
            shotBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);

            captureSession.stopRepeating();
            captureSession.capture(shotBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    Log.d(TAG, "Shutter triggered!");
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }

    private void applyEnhancements(CaptureRequest.Builder builder) {
        if (hdrMode) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
        } else if (nightMode) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
            // Boost exposure compensation to brighten dark areas
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 4); // Max brightness
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        }
        
        // Ensure focus is sharp
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    }

    private void processImage(ImageReader reader, String customPath, PhotoCallback callback) {
        try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            File file = (customPath != null) ? new File(customPath) : 
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), 
                "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");

            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
                callback.onPhotoSaved(file.getAbsolutePath());
            }
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }
}