package com.simple2fps.camera;

import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class Camera2VideoRecorder {
    private static final String TAG = "Camera2VideoRecorder";
    
    private Context context;
    private TextureView textureView;
    private TextView statusView;
    
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private MediaRecorder mediaRecorder;
    
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    
    private String cameraId;
    private Size selectedVideoSize;
    private int selectedFps = 2;
    
    

    public Camera2VideoRecorder(Context context, TextureView textureView, TextView statusView) {
        // Use Application Context to avoid memory leaks if Activity is destroyed
        this.context = context.getApplicationContext();
        this.textureView = textureView;
        this.statusView = statusView;
    }

    // Helper to run code on UI thread from background
    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }

    public void openCamera() {
        startBackgroundThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            // Find a back-facing camera
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }
            
            if (cameraId != null) {
                try {
                    manager.openCamera(cameraId, stateCallback, backgroundHandler);
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission missing", e);
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            int w = selectedVideoSize != null ? selectedVideoSize.getWidth() : 1920;
            int h = selectedVideoSize != null ? selectedVideoSize.getHeight() : 1080;
            texture.setDefaultBufferSize(w, h);
            
            Surface surface = new Surface(texture);
            
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            // Default FPS range for preview
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        } catch (CameraAccessException e) { e.printStackTrace(); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {}
                }, backgroundHandler);
                
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    public void startRecording(int fps, String customPath) {
        this.selectedFps = fps;
        
        closePreviewSession();
        
        int width = selectedVideoSize != null ? selectedVideoSize.getWidth() : 1920;
        int height = selectedVideoSize != null ? selectedVideoSize.getHeight() : 1080;

        try {
            File file;
            if (customPath != null && !customPath.isEmpty()) {
                file = new File(customPath);
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
            } else {
                file = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "REC_" + new SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4"
                );
            }

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(file.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(calculateBitrate(width, height, fps));
            mediaRecorder.setVideoFrameRate(fps);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.prepare();

            List<Surface> surfaces = new ArrayList<>();
            
            Surface recorderSurface = mediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            
            Surface previewSurface = null;
            if (textureView.isAvailable()) {
                SurfaceTexture texture = textureView.getSurfaceTexture();
                texture.setDefaultBufferSize(width, height);
                previewSurface = new Surface(texture);
                surfaces.add(previewSurface);
            }

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(recorderSurface);
            if (previewSurface != null) {
                previewRequestBuilder.addTarget(previewSurface);
            }
            
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        
                        runOnUiThread(() -> {
                            statusView.setText("REC: " + fps + " FPS");
                            Toast.makeText(context, "Recording Started", Toast.LENGTH_SHORT).show();
                        });
                        
                    } catch (Exception e) { 
                        Log.e(TAG, "Recording start failed", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    runOnUiThread(() -> Toast.makeText(context, "Configuration Failed", Toast.LENGTH_SHORT).show());
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "startRecording exception", e);
            runOnUiThread(() -> Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    public void stopRecording() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop capture session failed", e);
        }
        
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop mediaRecorder failed", e);
        }
        
        runOnUiThread(() -> statusView.setText("Saved"));
        startPreview();
    }

    private void closePreviewSession() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
    }
    
    // Cleanup method
    public void closeCamera() {
        closePreviewSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
        stopBackgroundThread();
    }
    
    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    // Helper: Bitrate calculation
    private int calculateBitrate(int width, int height, int fps) {
        int pixels = width * height;
        double bitsPerPixel = fps <= 5 ? 0.25 : 0.15;
        int bitrate = (int) (pixels * fps * bitsPerPixel);
        return Math.max(500000, Math.min(bitrate, 20000000));
    }
    
    // Helper: Get Sizes (simplified for brevity, logic same as before)
    public List<Size> getAvailableVideoSizes() {
        List<Size> sizes = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraId != null) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    sizes.addAll(Arrays.asList(map.getOutputSizes(MediaRecorder.class)));
                    Collections.sort(sizes, (s1, s2) -> Integer.compare(s2.getWidth()*s2.getHeight(), s1.getWidth()*s1.getHeight()));
                }
            }
        } catch (Exception e) {}
        if (sizes.isEmpty()) sizes.add(new Size(1920, 1080));
        return sizes;
    }
    
    public void setVideoSize(Size size) {
        this.selectedVideoSize = size;
    }
    public CameraDevice getCameraDevice() {
        return this.cameraDevice;
    }

    public Handler getBackgroundHandler() {
        return this.backgroundHandler;
    }
}