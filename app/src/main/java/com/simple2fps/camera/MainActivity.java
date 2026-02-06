package com.simple2fps.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private TextureView textureView;
    private Button recordButton;
    private TextView statusText;
    private Spinner resolutionSpinner;
    private Camera2VideoRecorder recorder;
    private boolean isRecording = false;

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
        resolutionSpinner = findViewById(R.id.resolutionSpinner);

        // Populate Spinner
        String[] items = new String[]{"2 FPS", "5 FPS", "10 FPS"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        resolutionSpinner.setAdapter(adapter);

        // Initialize Recorder
        recorder = new Camera2VideoRecorder(this, textureView, statusText);

        textureView.setSurfaceTextureListener(this);

        recordButton.setOnClickListener(v -> {
            if (!isRecording) {
                // Get FPS from spinner (e.g., "2 FPS" -> 2)
                String selected = resolutionSpinner.getSelectedItem().toString();
                int fps = Integer.parseInt(selected.split(" ")[0]);
                
                recorder.startRecording(fps);
                recordButton.setText("Stop Recording");
                isRecording = true;
            } else {
                recorder.stopRecording();
                recordButton.setText("Start Recording");
                isRecording = false;
            }
        });

        if (!checkPermissions()) {
            requestPermissions(REQUIRED_PERMISSIONS, 101);
        }
    }

    // --- TextureView Listener (This starts the preview!) ---
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
    
    // Resume camera if we come back to the app
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
        recorder.closeCamera();
        super.onPause();
    }
}
