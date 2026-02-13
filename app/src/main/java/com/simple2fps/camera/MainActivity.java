package com.simple2fps.camera;

import android.Manifest;
import android.graphics.SurfaceTexture;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Size;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private TextureView textureView;
    private Button recordButton;
    private Button modeButton;
    private boolean isPhotoMode = false;
    private TextView statusText;
    private Camera2PhotoCapture photoCapture;
    private Spinner fpsSpinner;
    private Spinner resolutionSpinner;
    private Camera2VideoRecorder recorder;
    
    // Flags di stato
    private boolean isRecording = false;
    private boolean isBackgroundRecording = false;
    private boolean isProcessingMacroDroid = false;
    private boolean isBackgroundPhoto = false;
    
    // WakeLock per registrazioni con schermo spento
    private PowerManager.WakeLock wakeLock;
    
    private List<Size> availableResolutions;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        boolean backgroundMode = intent.getBooleanExtra("background", false);
        boolean hidePreview = intent.getBooleanExtra("hide_preview", false);
        
        // Configurazione background mode
        if (backgroundMode) {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            );
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            moveTaskToBack(true);
        }
        
        setContentView(R.layout.activity_main);

        // Inizializzazione views
        textureView = findViewById(R.id.textureView);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        modeButton = findViewById(R.id.modeButton);

        // Nascondi preview se richiesto (risparmio batteria)
        if (hidePreview || backgroundMode) {
            textureView.setVisibility(android.view.View.INVISIBLE);
            textureView.getLayoutParams().width = 1;
            textureView.getLayoutParams().height = 1;
            
            recordButton.setVisibility(android.view.View.GONE);
            modeButton.setVisibility(android.view.View.GONE);
            fpsSpinner.setVisibility(android.view.View.GONE);
            resolutionSpinner.setVisibility(android.view.View.GONE);
            statusText.setVisibility(android.view.View.GONE);
        }

        recorder = new Camera2VideoRecorder(this, textureView, statusText);

        // Setup FPS Spinner
        String[] fpsItems = new String[]{"1 FPS", "2 FPS", "5 FPS", "10 FPS", "15 FPS", "24 FPS", "30 FPS"};
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsItems);
        fpsSpinner.setAdapter(fpsAdapter);
        fpsSpinner.setSelection(1); // Default 2 FPS

        setupResolutionSpinner();

        textureView.setSurfaceTextureListener(this);

        // Mode button (Photo/Video switch)
        modeButton.setOnClickListener(v -> {
            if (isRecording) return;

            isPhotoMode = !isPhotoMode;
            if (isPhotoMode) {
                modeButton.setText("MODE: PHOTO");
                recordButton.setText("Take Photo");
                recordButton.setBackgroundColor(0xFF0000FF);
                fpsSpinner.setVisibility(android.view.View.GONE);
            } else {
                modeButton.setText("MODE: VIDEO");
                recordButton.setText("Start Recording");
                recordButton.setBackgroundColor(0xFFFF0000);
                fpsSpinner.setVisibility(android.view.View.VISIBLE);
            }
        });

        // Record button
        recordButton.setOnClickListener(v -> {
            if (isPhotoMode) {
                capturePhotoManual();
            } else {
                if (!isRecording) {
                    startRecording();
                } else {
                    stopRecording();
                }
            }
        });

        if (!checkPermissions()) {
            requestPermissions(REQUIRED_PERMISSIONS, 101);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        String mode = intent.getStringExtra("mode");
        boolean autoStart = intent.getBooleanExtra("auto_start", false);
        
        if (mode != null || autoStart) {
            new Handler().postDelayed(() -> {
                processMacroDroidIntent(intent);
            }, 500);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (checkPermissions()) {
            recorder.openCamera(width, height);
            
            new Handler().postDelayed(() -> {
                processMacroDroidIntent(getIntent());
            }, 1500);
        }
    }

    private void processMacroDroidIntent(Intent intent) {
        if (isProcessingMacroDroid) {
            return; // Evita comandi multipli simultanei
        }
        
        isProcessingMacroDroid = true;
        
        String mode = intent.getStringExtra("mode");
        boolean autoStart = intent.getBooleanExtra("auto_start", false);
        boolean isVideoMode = autoStart && !"photo".equals(mode);
        
        if ("photo".equals(mode)) {
            isBackgroundPhoto = intent.getBooleanExtra("background", false) || 
                               intent.getBooleanExtra("hide_preview", false);
            
            if (isRecording) {
                stopRecording();
                new Handler().postDelayed(() -> {
                    capturePhotoFromIntent(intent);
                    isProcessingMacroDroid = false;
                }, 1000);
            } else {
                capturePhotoFromIntent(intent);
                isProcessingMacroDroid = false;
            }
        } else if (isVideoMode) {
            int fps = intent.getIntExtra("fps", 2);
            String quality = intent.getStringExtra("quality");
            int duration = intent.getIntExtra("duration", 30);
            String filepath = intent.getStringExtra("filepath");
            
            startMacroDroidRecording(fps, quality, duration, filepath);
            isProcessingMacroDroid = false;
        } else {
            isProcessingMacroDroid = false;
        }
    }

    private void capturePhotoManual() {
        Size photoSize = null;
        int selectedPos = resolutionSpinner.getSelectedItemPosition();
        if (availableResolutions != null && selectedPos < availableResolutions.size()) {
            photoSize = availableResolutions.get(selectedPos);
        } else {
            photoSize = new Size(1920, 1080);
        }

        photoCapture = new Camera2PhotoCapture(this, recorder.cameraDevice, recorder.backgroundHandler);
        photoCapture.setPhotoSize(photoSize);
        
        statusText.setText("Capturing photo...");
        photoCapture.capturePhoto(null, new Camera2PhotoCapture.PhotoCallback() {
            @Override
            public void onPhotoSaved(String filepath) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Photo Saved: " + filepath, Toast.LENGTH_SHORT).show();
                    statusText.setText("Photo saved!");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void capturePhotoFromIntent(Intent intent) {
        String quality = intent.getStringExtra("quality");
        String filepath = intent.getStringExtra("filepath");
        boolean nightMode = intent.getBooleanExtra("night_mode", false);
        boolean hdr = intent.getBooleanExtra("hdr_mode", false);

        Size photoSize = null;
        if (quality != null && availableResolutions != null) {
            for (Size size : availableResolutions) {
                if (quality.equalsIgnoreCase("fhd") && size.getWidth() == 1920) {
                    photoSize = size;
                    break;
                } else if (quality.equalsIgnoreCase("4k") && size.getWidth() == 3840) {
                    photoSize = size;
                    break;
                } else if (quality.equalsIgnoreCase("hd") && size.getWidth() == 1280) {
                    photoSize = size;
                    break;
                }
            }
        }
        
        if (photoSize == null && availableResolutions != null && !availableResolutions.isEmpty()) {
            int selectedPosition = resolutionSpinner.getSelectedItemPosition();
            if (selectedPosition >= 0 && selectedPosition < availableResolutions.size()) {
                photoSize = availableResolutions.get(selectedPosition);
            }
        }
        
        if (photoSize == null) {
            photoSize = new Size(1920, 1080);
        }
        
        photoCapture = new Camera2PhotoCapture(this, recorder.cameraDevice, recorder.backgroundHandler);
        photoCapture.setPhotoSize(photoSize);
        photoCapture.setNightMode(nightMode);
        photoCapture.setHdrMode(hdr);
        
        photoCapture.capturePhoto(filepath, new Camera2PhotoCapture.PhotoCallback() {
            @Override
            public void onPhotoSaved(String filepath) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Photo saved: " + filepath, Toast.LENGTH_SHORT).show();
                });
                
                // Auto-close sempre dopo foto
                new Handler().postDelayed(() -> {
                    finishAndRemoveTask();
                }, 300);
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                });
                
                // Chiudi anche in caso di errore
                new Handler().postDelayed(() -> {
                    finishAndRemoveTask();
                }, 300);
            }
        });
    }

    private void setupResolutionSpinner() {
        availableResolutions = recorder.getAvailableVideoSizes();
        
        List<String> resolutionStrings = new ArrayList<>();
        for (Size size : availableResolutions) {
            String label = size.getWidth() + " x " + size.getHeight();
            
            if (size.getWidth() == 3840 && size.getHeight() == 2160) {
                label += " (4K UHD)";
            } else if (size.getWidth() == 2560 && size.getHeight() == 1440) {
                label += " (QHD)";
            } else if (size.getWidth() == 1920 && size.getHeight() == 1080) {
                label += " (Full HD)";
            } else if (size.getWidth() == 1280 && size.getHeight() == 720) {
                label += " (HD)";
            } else if (size.getWidth() == 640 && size.getHeight() == 480) {
                label += " (VGA)";
            }
            
            resolutionStrings.add(label);
        }
        
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(
            this, 
            android.R.layout.simple_spinner_dropdown_item, 
            resolutionStrings
        );
        resolutionSpinner.setAdapter(resAdapter);
        
        int defaultIndex = 0;
        for (int i = 0; i < availableResolutions.size(); i++) {
            Size size = availableResolutions.get(i);
            if (size.getWidth() == 1920 && size.getHeight() == 1080) {
                defaultIndex = i;
                break;
            }
        }
        
        if (!availableResolutions.isEmpty()) {
            resolutionSpinner.setSelection(defaultIndex);
            recorder.setVideoSize(availableResolutions.get(defaultIndex));
        }
        
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (!isRecording && position < availableResolutions.size()) {
                    Size selected = availableResolutions.get(position);
                    recorder.setVideoSize(selected);
                    statusText.setText("Resolution: " + selected.getWidth() + "x" + selected.getHeight());
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void startRecording() {
        String fpsSelected = fpsSpinner.getSelectedItem().toString();
        int fps = Integer.parseInt(fpsSelected.split(" ")[0]);
        
        int resPosition = resolutionSpinner.getSelectedItemPosition();
        if (resPosition >= 0 && resPosition < availableResolutions.size()) {
            Size resolution = availableResolutions.get(resPosition);
            recorder.setVideoSize(resolution);
        }
        
        Intent intent = getIntent();
        String customPath = intent.getStringExtra("filepath");
        
        recorder.startRecording(fps, customPath);
        recordButton.setText("Stop Recording");
        recordButton.setBackgroundColor(0xFF00AA00);
        isRecording = true;
        
        fpsSpinner.setEnabled(false);
        resolutionSpinner.setEnabled(false);
    }

    private void stopRecording() {
        recorder.stopRecording();
        recordButton.setText("Start Recording");
        recordButton.setBackgroundColor(0xFFFF0000);
        isRecording = false;
        
        fpsSpinner.setEnabled(true);
        resolutionSpinner.setEnabled(true);
    }
    
    private void startMacroDroidRecording(int fps, String quality, int duration, String filepath) {
        isBackgroundRecording = true;
        
        // CRITICO: Acquire WakeLock per tenere CPU attiva anche con schermo spento
        acquireWakeLock();

        for (int i = 0; i < fpsSpinner.getCount(); i++) {
            String item = fpsSpinner.getItemAtPosition(i).toString();
            if (item.startsWith(fps + " ")) {
                fpsSpinner.setSelection(i);
                break;
            }
        }
        
        if (quality != null) {
            for (int i = 0; i < availableResolutions.size(); i++) {
                Size size = availableResolutions.get(i);
                String sizeStr = size.getWidth() + "x" + size.getHeight();
                
                if (quality.equals(sizeStr) || 
                    (quality.equalsIgnoreCase("fhd") && size.getWidth() == 1920) ||
                    (quality.equalsIgnoreCase("hd") && size.getWidth() == 1280) ||
                    (quality.equalsIgnoreCase("4k") && size.getWidth() == 3840)) {
                    resolutionSpinner.setSelection(i);
                    recorder.setVideoSize(size);
                    break;
                }
            }
        }
        
        Intent intent = getIntent();
        if (filepath != null && !filepath.isEmpty()) {
            intent.putExtra("filepath", filepath);
        }
        
        startRecording();
        
        if (duration > 0) {
            new Handler().postDelayed(() -> {
                if (isRecording) {
                    stopRecording();
                    isBackgroundRecording = false;
                    recorder.closeCamera();
                    
                    // Release WakeLock
                    releaseWakeLock();
                    
                    // Chiudi app
                    finishAndRemoveTask();
                }
            }, duration * 1000);
        }
    }
    
    // ========== WAKELOCK METHODS ==========
    
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Simple2FpsCamera::RecordingWakeLock"
            );
            wakeLock.setReferenceCounted(false);
        }
        
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            android.util.Log.d("WakeLock", "ACQUIRED - CPU stays awake during recording");
        }
    }
    
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            android.util.Log.d("WakeLock", "RELEASED - CPU can sleep now");
        }
    }

    // ========== LIFECYCLE METHODS ==========

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // Non chiudere camera se registrazione background
        if (!isBackgroundRecording && !isBackgroundPhoto) {
            recorder.closeCamera();
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        Intent intent = getIntent();
        String mode = intent.getStringExtra("mode");
        boolean autoStart = intent.getBooleanExtra("auto_start", false);
        
        if (textureView.isAvailable() && checkPermissions() && (mode != null || autoStart)) {
            new Handler().postDelayed(() -> {
                processMacroDroidIntent(intent);
            }, 500);
        } else if (textureView.isAvailable() && checkPermissions()) {
            recorder.openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(this);
        }
    }

    @Override
    protected void onPause() {
        // Non fermare registrazione se background mode
        if (isRecording && !isBackgroundRecording && !isBackgroundPhoto) {
            stopRecording();
        }
        
        // Non chiudere camera se background mode
        if (!isBackgroundRecording && !isBackgroundPhoto) {
            recorder.closeCamera();
        }
        
        super.onPause();
    }
    
    @Override
    protected void onDestroy() {
        // Cleanup: release WakeLock
        releaseWakeLock();
        super.onDestroy();
    }
}