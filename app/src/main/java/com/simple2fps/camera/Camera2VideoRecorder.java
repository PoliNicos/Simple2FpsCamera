package com.simple2fps.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;

public class Camera2VideoRecorder {
    private static final String TAG = "Camera2VideoRecorder";
    
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
            
            try {
                if (cameraId != null) {
                    manager.openCamera(cameraId, stateCallback, backgroundHandler);
                }
            } catch (SecurityException e) { 
                e.printStackTrace(); 
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
            texture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // For preview, use a wider range so it's smooth
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));

            cameraDevice.createCaptureSession(Collections.singletonList(surface), 
                new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) { 
                        e.printStackTrace(); 
                    }
                }
                @Override 
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Preview session configuration failed");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) { 
            e.printStackTrace(); 
        }
    }

    public void startRecording(int fps) {
        this.selectedFps = fps;
        closePreviewSession();
        
        try {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), 
                "REC_" + fps + "fps_" + System.currentTimeMillis() + ".mp4");
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(file.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(1000000); 
            mediaRecorder.setVideoFrameRate(fps); // This sets playback FPS
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
            
            // ⭐ THE FIX: Set BOTH min and max to the SAME value!
            // This forces the camera to capture at exactly this FPS
            // From: https://stackoverflow.com/questions/53957348/how-to-modify-frame-rate-using-camera2
            Range<Integer> fpsRange = new Range<>(fps, fps); // BOTH values same!
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            
            Log.d(TAG, "Setting FPS range to: " + fpsRange + " (forces exactly " + fps + " FPS)");

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface), 
                new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        
                        activity.runOnUiThread(() -> {
                            statusView.setText("REC: " + fps + " FPS");
                            Toast.makeText(activity, "Recording at " + fps + " FPS", Toast.LENGTH_SHORT).show();
                        });
                        
                    } catch (Exception e) { 
                        e.printStackTrace(); 
                        activity.runOnUiThread(() -> 
                            Toast.makeText(activity, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }
                @Override 
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Recording session configuration failed");
                    activity.runOnUiThread(() -> 
                        Toast.makeText(activity, "Failed to configure camera for recording", Toast.LENGTH_LONG).show());
                }
            }, backgroundHandler);

        } catch (Exception e) { 
            e.printStackTrace(); 
            activity.runOnUiThread(() -> 
                Toast.makeText(activity, "Error starting recording: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    public void stopRecording() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                
                activity.runOnUiThread(() -> {
                    statusView.setText("Video Saved!");
                    Toast.makeText(activity, "Video saved to Movies folder", Toast.LENGTH_LONG).show();
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            activity.runOnUiThread(() -> 
                Toast.makeText(activity, "Error stopping recording", Toast.LENGTH_SHORT).show());
        }
        
        startPreview(); 
    }
    
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

    private void closePreviewSession() {
        if (captureSession != null) { 
            captureSession.close(); 
            captureSession = null; 
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { 
                backgroundThread.join(); 
                backgroundThread = null; 
                backgroundHandler = null; 
            } catch (InterruptedException e) { 
                e.printStackTrace(); 
            }
        }
    }
}