package com.simple2fps.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.widget.Toast;
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
    private boolean nightMode = false; // Auto night mode
    
    public interface PhotoCallback {
        void onPhotoSaved(String filepath);
        void onError(String error);
    }
    
    public Camera2PhotoCapture(Activity activity, CameraDevice camera, Handler handler) {
        this.activity = activity;
        this.cameraDevice = camera;
        this.backgroundHandler = handler;
    }
    
    public void setPhotoSize(Size size) {
        this.photoSize = size;
    }
    
    public void setNightMode(boolean enabled) {
        this.nightMode = enabled;
    }
    
    /**
     * Cattura foto con auto-esposizione notturna
     */
    public void capturePhoto(String customPath, PhotoCallback callback) {
        if (cameraDevice == null) {
            if (callback != null) callback.onError("Camera not ready");
            return;
        }
        
        try {
            // Default resolution se non impostata
            if (photoSize == null) {
                photoSize = new Size(1920, 1080);
            }
            
            // Setup ImageReader
            imageReader = ImageReader.newInstance(
                photoSize.getWidth(),
                photoSize.getHeight(),
                ImageFormat.JPEG,
                1
            );
            
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    
                    // Salva file
                    File file;
                    if (customPath != null && !customPath.isEmpty()) {
                        file = new File(customPath);
                        File parentDir = file.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs();
                        }
                    } else {
                        file = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                            new SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg"
                        );
                    }
                    
                    FileOutputStream output = new FileOutputStream(file);
                    output.write(bytes);
                    output.close();
                    
                    Log.d(TAG, "Photo saved: " + file.getAbsolutePath());
                    
                    if (callback != null) {
                        callback.onPhotoSaved(file.getAbsolutePath());
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error saving photo", e);
                    if (callback != null) callback.onError(e.getMessage());
                } finally {
                    if (image != null) image.close();
                }
            }, backgroundHandler);
            
            // Create capture session
            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        takePicture(callback);
                    }
                    
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        if (callback != null) callback.onError("Session config failed");
                    }
                },
                backgroundHandler
            );
            
        } catch (Exception e) {
            Log.e(TAG, "Error capturing photo", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }
    
    private void takePicture(PhotoCallback callback) {
        try {
            CaptureRequest.Builder captureBuilder = 
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            
            captureBuilder.addTarget(imageReader.getSurface());
            
            // **NIGHT MODE MAGIC** - Auto exposure adjustment
            if (nightMode) {
                // Aumenta esposizione per foto notturna
                captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 2); // +2 EV
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                
                // ISO alto per bassa luce (se supportato)
                Range<Integer> isoRange = getSupportedISORange();
                if (isoRange != null) {
                    captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange.getUpper()); // Max ISO
                }
                
                Log.d(TAG, "Night mode enabled: High ISO + Exposure compensation");
            } else {
                // Auto exposure normale
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            
            // Altre impostazioni qualità
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            
            // Cattura!
            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    Log.d(TAG, "Capture completed");
                }
            }, backgroundHandler);
            
        } catch (Exception e) {
            Log.e(TAG, "Error taking picture", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }
    
    /**
     * Ottiene range ISO supportato dalla camera
     */
    private Range<Integer> getSupportedISORange() {
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = cameraDevice.getId();
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            return characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        } catch (Exception e) {
            return null;
        }
    }
    
    public void release() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
}