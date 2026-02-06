package com.simple2fps.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Camera2 Video Recorder with 2 FPS support
 * Uses the technique from: https://stackoverflow.com/questions/53957348/how-to-modify-frame-rate-using-camera2
 * 
 * Key concept: Set Camera2 FPS range to minimum (e.g., 2-2 FPS) to control frame capture rate
 */
public class Camera2VideoRecorder {
    private static final String TAG = "Camera2VideoRecorder";
    private static final int TARGET_FPS = 2;
    
    private Context context;
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface encoderSurface;
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private boolean isRecording = false;
    
    private Size videoSize;
    private String outputPath;
    private long frameCount = 0;
    private long startTime = 0;
    
    private RecordingCallback callback;
    
    public interface RecordingCallback {
        void onRecordingStarted();
        void onRecordingStopped(String filePath);
        void onError(String error);
    }
    
    public Camera2VideoRecorder(Context context, TextureView textureView) {
        this.context = context;
        this.textureView = textureView;
    }
    
    public void setCallback(RecordingCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Get available video resolutions from camera
     */
    public List<Size> getAvailableVideoSizes(String cameraId) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            
            if (map != null) {
                Size[] sizes = map.getOutputSizes(MediaCodec.class);
                List<Size> sizeList = new ArrayList<>(Arrays.asList(sizes));
                
                // Sort by resolution (largest first)
                Collections.sort(sizeList, (s1, s2) -> 
                    Integer.compare(s2.getWidth() * s2.getHeight(), s1.getWidth() * s1.getHeight()));
                
                return sizeList;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting video sizes", e);
        }
        return new ArrayList<>();
    }
    
    /**
     * Start camera and preview
     */
    public void startCamera(Size videoSize) {
        this.videoSize = videoSize;
        startBackgroundThread();
        
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }
    
    private final TextureView.SurfaceTextureListener surfaceTextureListener = 
        new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }
        
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    
    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = manager.getCameraIdList()[0]; // Use back camera
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                if (callback != null) callback.onError("Camera permission not granted");
                return;
            }
            
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
            if (callback != null) callback.onError("Failed to open camera");
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
            if (callback != null) callback.onError("Camera error: " + error);
        }
    };
    
    private void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface surface = new Surface(texture);
            
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            
            cameraDevice.createCaptureSession(Arrays.asList(surface), 
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Error starting preview", e);
                        }
                    }
                    
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        if (callback != null) callback.onError("Preview configuration failed");
                    }
                }, backgroundHandler);
                
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating preview session", e);
        }
    }
    
    /**
     * Start recording at 2 FPS
     */
    public void startRecording(String outputPath) {
        this.outputPath = outputPath;
        this.frameCount = 0;
        this.startTime = System.nanoTime();
        
        try {
            setupEncoder();
            startRecordingSession();
            isRecording = true;
            if (callback != null) callback.onRecordingStarted();
            
        } catch (IOException e) {
            Log.e(TAG, "Error starting recording", e);
            if (callback != null) callback.onError("Failed to start recording");
        }
    }
    
    private void setupEncoder() throws IOException {
        // Create MediaCodec encoder
        MediaFormat format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            videoSize.getWidth(),
            videoSize.getHeight()
        );
        
        // IMPORTANT: Set encoder to higher frame rate (30 FPS)
        // We'll control actual frame rate via Camera2 FPS range
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
        
        // Create MediaMuxer
        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        
        Log.d(TAG, "Encoder setup complete");
    }
    
    private void startRecordingSession() {
        try {
            // KEY TECHNIQUE: Set Camera2 FPS range to 2-2 FPS
            // This controls how often camera captures frames
            // From: https://stackoverflow.com/questions/53957348/how-to-modify-frame-rate-using-camera2
            
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(encoderSurface);
            
            // THE MAGIC: Set FPS range to 2-2 to capture at 2 FPS
            Range<Integer> fpsRange = new Range<>(TARGET_FPS, TARGET_FPS);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            
            Log.d(TAG, "Setting FPS range to: " + fpsRange);
            
            cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, encoderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            // Start repeating capture at 2 FPS
                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler);
                            Log.d(TAG, "Recording session started at 2 FPS");
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Error starting recording session", e);
                        }
                    }
                    
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        if (callback != null) callback.onError("Recording session configuration failed");
                    }
                }, backgroundHandler);
                
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating recording session", e);
        }
    }
    
    private final CameraCaptureSession.CaptureCallback captureCallback = 
        new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, 
                                      TotalCaptureResult result) {
            // Frame captured - encoder will handle it via encoderSurface
            drainEncoder(false);
        }
    };
    
    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) return;
        
        if (endOfStream) {
            encoder.signalEndOfInputStream();
        }
        
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (true) {
            int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000);
            
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new RuntimeException("Format changed twice");
                }
                MediaFormat newFormat = encoder.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
                Log.d(TAG, "Muxer started");
            } else if (outputBufferId >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferId);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                
                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw new RuntimeException("Muxer hasn't started");
                    }
                    
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    frameCount++;
                }
                
                encoder.releaseOutputBuffer(outputBufferId, false);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }
    
    /**
     * Stop recording
     */
    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        
        try {
            // Stop capture session
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
            
            // Drain encoder
            drainEncoder(true);
            
            // Release encoder and muxer
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
            
            if (muxer != null) {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
                muxer = null;
            }
            
            if (encoderSurface != null) {
                encoderSurface.release();
                encoderSurface = null;
            }
            
            muxerStarted = false;
            trackIndex = -1;
            
            Log.d(TAG, "Recording stopped. Frames recorded: " + frameCount);
            
            // Restart preview
            startPreview();
            
            if (callback != null) {
                callback.onRecordingStopped(outputPath);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
            if (callback != null) callback.onError("Error stopping recording");
        }
    }
    
    /**
     * Release camera and resources
     */
    public void release() {
        if (isRecording) {
            stopRecording();
        }
        
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        stopBackgroundThread();
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
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }
}