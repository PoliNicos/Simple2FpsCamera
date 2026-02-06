package com.simple2fps.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextureView textureView;
    private Button recordButton;
    private TextView statusText;
    private Spinner resolutionSpinner;
    
    // We only ask for Camera and Audio (Safe for Android 13+)
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Link Java variables to XML IDs
        textureView = findViewById(R.id.textureView);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);

        // Basic setup to prove app loaded
        statusText.setText("Ready");
        
        // Setup simple click listener
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkPermissions()) {
                    Toast.makeText(MainActivity.this, "Permissions OK - Starting...", Toast.LENGTH_SHORT).show();
                    // Actual recording logic would go here
                } else {
                    requestPermissions(REQUIRED_PERMISSIONS, 101);
                }
            }
        });
        
        // Populate spinner just so it's not empty
        String[] items = new String[]{"2 FPS", "5 FPS"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        resolutionSpinner.setAdapter(adapter);

        // Check permissions immediately on load
        if (!checkPermissions()) {
            requestPermissions(REQUIRED_PERMISSIONS, 101);
        }
    }

    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
