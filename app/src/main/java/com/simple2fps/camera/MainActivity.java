package com.simple2fps.camera;

import android.view.WindowManager;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.util.Size;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private TextureView textureView;
    private Button recordButton;
    private TextView statusText;
    private Spinner fpsSpinner;
    private Spinner resolutionSpinner;
    private Camera2VideoRecorder recorder;
    private boolean isRecording = false;
    
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
        
        if (backgroundMode) {
            // Nascondi completamente lo schermo
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            );
            
            // Minimizza immediatamente
            moveTaskToBack(true);
        }
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);

        recorder = new Camera2VideoRecorder(this, textureView, statusText);

        // FPS Spinner
        String[] fpsItems = new String[]{"1 FPS", "2 FPS", "5 FPS", "10 FPS", "15 FPS", "24 FPS", "30 FPS"};
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsItems);
        fpsSpinner.setAdapter(fpsAdapter);
        fpsSpinner.setSelection(1); // Default 2 FPS

        // Resolution Spinner
        setupResolutionSpinner();

        textureView.setSurfaceTextureListener(this);

        recordButton.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        if (!checkPermissions()) {
            requestPermissions(REQUIRED_PERMISSIONS, 101);
        }
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
        
        // Seleziona Full HD se disponibile
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
        // Leggi custom path dall'Intent (se presente)
        Intent intent = getIntent();
        String customPath = intent.getStringExtra("filepath");

        int resPosition = resolutionSpinner.getSelectedItemPosition();
        if (resPosition >= 0 && resPosition < availableResolutions.size()) {
            Size resolution = availableResolutions.get(resPosition);
            recorder.setVideoSize(resolution);
        }
        
        recorder.startRecording(fps);
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
    
    // MacroDroid support
    private void startMacroDroidRecording(int fps, String quality, int duration) {
        // Imposta FPS
        // Se c'è filepath custom, passalo
        Intent intent = getIntent();
        if (filepath != null && !filepath.isEmpty()) {
            intent.putExtra("filepath", filepath);
        }
        for (int i = 0; i < fpsSpinner.getCount(); i++) {
            String item = fpsSpinner.getItemAtPosition(i).toString();
            if (item.startsWith(fps + " ")) {
                fpsSpinner.setSelection(i);
                break;
            }
        }
        
        // Imposta risoluzione
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
        
        // Inizia registrazione
        startRecording();
        
        // Auto-stop dopo duration
        if (duration > 0) {
            new Handler().postDelayed(() -> {
                if (isRecording) {
                    stopRecording();
                    finish();
                }
            }, duration * 1000);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (checkPermissions()) {
            recorder.openCamera(width, height);
            
            // Auto-start se richiesto da MacroDroid
            Intent intent = getIntent();
            if (intent.getBooleanExtra("auto_start", false)) {
                new Handler().postDelayed(() -> {
                    int fps = intent.getIntExtra("fps", 2);
                    String quality = intent.getStringExtra("quality");
                    int duration = intent.getIntExtra("duration", 30);
                    String filepath = intent.getStringExtra("filepath");
                    startMacroDroidRecording(fps, quality, duration, filepath);
                }, 1500);
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        recorder.closeCamera();
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
        if (textureView.isAvailable() && checkPermissions()) {
            recorder.openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(this);
        }
    }

    @Override
    protected void onPause() {
        if (isRecording) {
            stopRecording();
        }
        recorder.closeCamera();
        super.onPause();
    }
}