package com.simple2fps.camera;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;

public class Camera2VideoRecorder {
    private Activity activity;
    private TextureView textureView;
    private TextView statusView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private String cameraId;
    private int selectedFps = 2;

    public Camera2VideoRecorder(Activity activity, TextureView textureView, TextView statusView) {
        this.activity = activity;
        this.textureView = textureView;
        this.statusView = statusView;
    }

    public void openCamera(int width, int height) {
        startBackgroundThread();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            // Find a back-facing camera
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0]; // Fallback
            
            // Open it (Permissions already checked in MainActivity)
            try {
                manager.openCamera(cameraId, stateCallback, backgroundHandler);
            } catch (SecurityException e) { e.printStackTrace(); }
            
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            startPreview(); // Start showing the video feed immediately
        }
        @Override
        public void onDisconnected(CameraDevice camera) { camera.close(); cameraDevice = null; }
        @Override
        public void onError(CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
    };

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(640, 480); // Low res for vintage feel
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // FORCE FPS RANGE (This is the magic part for 2 FPS)
            // We ask the camera for available ranges and pick the lowest
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(selectedFps, 30));

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    public void startRecording(int fps) {
        this.selectedFps = fps;
        closePreviewSession(); // Stop preview to switch to record mode
        
        try {
            File file = new File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "REC_" + System.currentTimeMillis() + ".mp4");
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(file.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(1000000); // 1Mbps (Low quality)
            mediaRecorder.setVideoFrameRate(fps); // SET THE VIDEO FILE FPS
            mediaRecorder.setVideoSize(640, 480);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.prepare();

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(640, 480);
            Surface previewSurface = new Surface(texture);
            Surface recordSurface = mediaRecorder.getSurface();

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.addTarget(recordSurface);
            
            // FORCE FPS ON SENSOR AGAIN
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        
                        activity.runOnUiThread(() -> {
                            statusView.setText("REC: " + fps + " FPS");
                            Toast.makeText(activity, "Recording Started!", Toast.LENGTH_SHORT).show();
                        });
                        
                    } catch (Exception e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {}
            }, backgroundHandler);

        } catch (Exception e) { 
            e.printStackTrace(); 
            activity.runOnUiThread(() -> Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    public void stopRecording() {
        try {
            captureSession.stop();
            captureSession.abortCaptures();
        } catch (Exception e) {}
        
        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
        } catch (Exception e) {}
        
        activity.runOnUiThread(() -> statusView.setText("Saved"));
        startPreview(); // Restart preview
    }
    
    public void closeCamera() {
        closePreviewSession();
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (mediaRecorder != null) { mediaRecorder.release(); mediaRecorder = null; }
        stopBackgroundThread();
    }

    private void closePreviewSession() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); backgroundThread = null; backgroundHandler = null; } 
            catch (InterruptedException e) { e.printStackTrace(); }
        }
    }
}
