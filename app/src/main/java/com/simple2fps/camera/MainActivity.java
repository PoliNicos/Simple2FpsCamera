package com.simple2fps.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Size;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main Activity for 2 FPS Camera App
 * Simple UI with resolution selector and record button
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    };
    
    private TextureView textureView;
    private Button recordButton;
    private TextView statusText;
    private Spinner resolutionSpinner;
    
    private Camera2VideoRecorder recorder;
    private boolean isRecording = false;
    private Size selectedResolution;
    private List<Size> availableSizes;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize views
        textureView = findViewById(R.id.textureView);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        
        // Setup recorder
        recorder = new Camera2VideoRecorder(this, textureView);
        recorder.setCallback(recordingCallback);
        
        // Setup UI
        recordButton.setOnClickListener(v -> toggleRecording());
        
        // Check permissions
        if (allPermissionsGranted()) {
            setupCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }
    
    private void setupCamera() {
        // Get available resolutions
        try {
            availableSizes = recorder.getAvailableVideoSizes("0");
            
            if (availableSizes.isEmpty()) {
                showError("No video resolutions available");
                return;
            }
            
            // Setup resolution spinner
            List<String> resolutionStrings = new ArrayList<>();
            for (Size size : availableSizes) {
                resolutionStrings.add(size.getWidth() + " x " + size.getHeight());
            }
            
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                resolutionStrings
            );
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            resolutionSpinner.setAdapter(adapter);
            
            // Set default resolution (first one, usually highest)
            selectedResolution = availableSizes.get(0);
            
            resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!isRecording) {
                        selectedResolution = availableSizes.get(position);
                        restartCamera();
                    }
                }
                
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            
            // Start camera with default resolution
            recorder.startCamera(selectedResolution);
            
        } catch (Exception e) {
            showError("Failed to setup camera: " + e.getMessage());
        }
    }
    
    private void restartCamera() {
        recorder.release();
        recorder = new Camera2VideoRecorder(this, textureView);
        recorder.setCallback(recordingCallback);
        recorder.startCamera(selectedResolution);
    }
    
    private void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }
    
    private void startRecording() {
        // Generate output file path
        String fileName = "2FPS_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES), "2FpsCamera");
        
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        File outputFile = new File(outputDir, fileName);
        
        // Start recording
        recorder.startRecording(outputFile.getAbsolutePath());
    }
    
    private void stopRecording() {
        recorder.stopRecording();
    }
    
    private final Camera2VideoRecorder.RecordingCallback recordingCallback = 
        new Camera2VideoRecorder.RecordingCallback() {
        @Override
        public void onRecordingStarted() {
            runOnUiThread(() -> {
                isRecording = true;
                recordButton.setText(R.string.stop_recording);
                recordButton.setBackgroundColor(0xFF00AA00); // Green
                statusText.setText(R.string.recording);
                resolutionSpinner.setEnabled(false);
            });
        }
        
        @Override
        public void onRecordingStopped(String filePath) {
            runOnUiThread(() -> {
                isRecording = false;
                recordButton.setText(R.string.start_recording);
                recordButton.setBackgroundColor(0xFFFF0000); // Red
                statusText.setText("Ready");
                resolutionSpinner.setEnabled(true);
                
                Toast.makeText(MainActivity.this, 
                    getString(R.string.video_saved, filePath), 
                    Toast.LENGTH_LONG).show();
            });
        }
        
        @Override
        public void onError(String error) {
            runOnUiThread(() -> {
                showError(error);
                isRecording = false;
                recordButton.setText(R.string.start_recording);
                statusText.setText("Error: " + error);
            });
        }
    };
    
    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setupCamera();
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (isRecording) {
            stopRecording();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        recorder.release();
    }
}