package com.simple2fps.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
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

        // Resolution Spinner (popolato dopo apertura camera)
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
            
            // Aggiungi label per risoluzioni comuni
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
        
        // Seleziona Full HD (1920x1080) se disponibile
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

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (checkPermissions()) {
            recorder.openCamera(width, height);
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