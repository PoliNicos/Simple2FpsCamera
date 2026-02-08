package com.simple2fps.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
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
    
    private Activity activity;
    private TextureView textureView;
    private TextView statusView;
    public CameraDevice cameraDevice;
    public Handler backgroundHandler;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private HandlerThread backgroundThread;
    private String cameraId;
    private int selectedFps = 2;
    private Size selectedVideoSize = null;

    public Camera2VideoRecorder(Activity activity, TextureView textureView, TextView statusView) {
        this.activity = activity;
        this.textureView = textureView;
        this.statusView = statusView;
    }

    public List<Size> getAvailableVideoSizes() {
        List<Size> sizes = new ArrayList<>();
        
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            
            String targetCameraId = cameraId;
            if (targetCameraId == null) {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                        targetCameraId = id;
                        break;
                    }
                }
                if (targetCameraId == null && manager.getCameraIdList().length > 0) {
                    targetCameraId = manager.getCameraIdList()[0];
                }
            }
            
            if (targetCameraId != null) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                
                if (map != null) {
                    Size[] supportedSizes = map.getOutputSizes(MediaRecorder.class);
                    if (supportedSizes != null) {
                        sizes.addAll(Arrays.asList(supportedSizes));
                        Collections.sort(sizes, new Comparator<Size>() {
                            @Override
                            public int compare(Size s1, Size s2) {
                                return Integer.compare(s2.getWidth() * s2.getHeight(), s1.getWidth() * s1.getHeight());
                            }
                        });
                        Log.d(TAG, "Found " + sizes.size() + " video resolutions");
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting video sizes", e);
        }
        
        if (sizes.isEmpty()) {
            sizes.add(new Size(1920, 1080));
            sizes.add(new Size(1280, 720));
            sizes.add(new Size(640, 480));
        }
        
        return sizes;
    }
    
    public void setVideoSize(Size size) {
        this.selectedVideoSize = size;
        Log.d(TAG, "Video size set to: " + size.getWidth() + "x" + size.getHeight());
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
            } catch (SecurityException e) { e.printStackTrace(); }
            
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
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
            
            int previewWidth = selectedVideoSize != null ? selectedVideoSize.getWidth() : 1920;
            int previewHeight = selectedVideoSize != null ? selectedVideoSize.getHeight() : 1080;
            
            texture.setDefaultBufferSize(previewWidth, previewHeight);
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));

            cameraDevice.createCaptureSession(Collections.singletonList(surface), 
                new CameraCaptureSession.StateCallback() {
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

    public void startRecording(int fps, String customPath) {
        this.selectedFps = fps;
        closePreviewSession();
        
        int videoWidth = selectedVideoSize != null ? selectedVideoSize.getWidth() : 1920;
        int videoHeight = selectedVideoSize != null ? selectedVideoSize.getHeight() : 1080;
        
        try {
            File file;
            if (customPath != null && !customPath.isEmpty()) {
                file = new File(customPath);
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
            } else {
                file = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), 
                    new SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4"
                );
            }
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(file.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(calculateBitrate(videoWidth, videoHeight, fps));
            mediaRecorder.setVideoFrameRate(fps);
            mediaRecorder.setVideoSize(videoWidth, videoHeight);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.prepare();

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoWidth, videoHeight);
            Surface previewSurface = new Surface(texture);
            Surface recordSurface = mediaRecorder.getSurface();

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.addTarget(recordSurface);
            
            Range<Integer> fpsRange = new Range<>(fps, fps);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface), 
                new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        
                        activity.runOnUiThread(() -> {
                            statusView.setText("REC: " + fps + " FPS @ " + videoWidth + "x" + videoHeight);
                            Toast.makeText(activity, "Recording " + fps + " FPS @ " + videoWidth + "x" + videoHeight, Toast.LENGTH_SHORT).show();
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
    
    private int calculateBitrate(int width, int height, int fps) {
        int pixels = width * height;
        double bitsPerPixel = fps <= 5 ? 0.25 : 0.15;
        int bitrate = (int) (pixels * fps * bitsPerPixel);
        return Math.max(500000, Math.min(bitrate, 20000000));
    }

    public void stopRecording() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
        } catch (Exception e) {}
        
        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
        } catch (Exception e) {}
        
        activity.runOnUiThread(() -> statusView.setText("Saved"));
        startPreview(); 
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