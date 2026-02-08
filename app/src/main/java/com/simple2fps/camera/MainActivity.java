package com.simple2fps.camera;

import android.Manifest;
import android.graphics.SurfaceTexture;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
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
    private boolean isRecording = false;
    private boolean isBackgroundRecording = false;
    private boolean isProcessingMacroDroid = false;
    private boolean isBackgroundPhoto = false;
    
    private List<Size> availableResolutions;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Vérifier si l'app est lancée en arrière-plan
        Intent intent = getIntent();
        boolean backgroundMode = intent.getBooleanExtra("background", false);
        
        if (backgroundMode) {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            );
            
            // Garder l'écran allumé
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            
            moveTaskToBack(true);
        }
        
        // Masquer la prévisualisation si demandé
        boolean hidePreview = intent.getBooleanExtra("hide_preview", false);
        if (hidePreview || backgroundMode) {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            );
        }
        
        setContentView(R.layout.activity_main);
        
        // Initialiser les vues
        textureView = findViewById(R.id.textureView);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        modeButton = findViewById(R.id.modeButton);

        // Masquer la prévisualisation si demandé
        if (hidePreview || backgroundMode) {
            textureView.setVisibility(android.view.View.GONE);
            recordButton.setVisibility(android.view.View.GONE);
            modeButton.setVisibility(android.view.View.GONE);
            fpsSpinner.setVisibility(android.view.View.GONE);
            resolutionSpinner.setVisibility(android.view.View.GONE);
            statusText.setVisibility(android.view.View.GONE);
        }

        recorder = new Camera2VideoRecorder(this, textureView, statusText);

        // FPS Spinner
        String[] fpsItems = new String[]{"1 FPS", "2 FPS", "5 FPS", "10 FPS", "15 FPS", "24 FPS", "30 FPS"};
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsItems);
        fpsSpinner.setAdapter(fpsAdapter);
        fpsSpinner.setSelection(1);

        setupResolutionSpinner();

        textureView.setSurfaceTextureListener(this);

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
        setIntent(intent); // Mettre à jour l'Intent avec le nouveau
        
        // Vérifier si nous devons traiter une commande MacroDroid
        String mode = intent.getStringExtra("mode");
        boolean autoStart = intent.getBooleanExtra("auto_start", false);
        
        if (mode != null || autoStart) {
            // Attendre que la caméra soit prête
            new Handler().postDelayed(() -> {
                processMacroDroidIntent(intent);
            }, 500);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (checkPermissions()) {
            recorder.openCamera(width, height);
            
            // Attendre que la caméra soit initialisée puis traiter l'Intent
            new Handler().postDelayed(() -> {
                processMacroDroidIntent(getIntent());
            }, 500);
        }
    }

    private void processMacroDroidIntent(Intent intent) {
        if (isProcessingMacroDroid) {
            return; // Éviter les commandes multiples simultanées
        }
        
        isProcessingMacroDroid = true;
        
        String mode = intent.getStringExtra("mode");
        boolean autoStart = intent.getBooleanExtra("auto_start", false);
        boolean isVideoMode = autoStart && !"photo".equals(mode);
        
        if ("photo".equals(mode)) {
            // Définir le flag pour la photo en arrière-plan
            isBackgroundPhoto = intent.getBooleanExtra("background", false) || 
                               intent.getBooleanExtra("hide_preview", false);
            
            // Arrêter l'enregistrement en cours si nécessaire
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
            // Arrêter la photo en cours si nécessaire
            if (photoCapture != null) {
                // On ne peut pas annuler facilement une capture photo, donc on attend
            }
            
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
        // 1. Get size from Spinner
        Size photoSize = null;
        int selectedPos = resolutionSpinner.getSelectedItemPosition();
        if (availableResolutions != null && selectedPos < availableResolutions.size()) {
            photoSize = availableResolutions.get(selectedPos);
        } else {
            photoSize = new Size(1920, 1080);
        }

        // 2. Initialize Capture
        photoCapture = new Camera2PhotoCapture(this, recorder.cameraDevice, recorder.backgroundHandler);
        photoCapture.setPhotoSize(photoSize);
        
        // 3. Capture
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
        boolean background = intent.getBooleanExtra("background", false);
        boolean finishAfter = intent.getBooleanExtra("finish_after", true);

        // Déterminer la résolution
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
        
        // Si aucune qualité n'est précisée, utiliser le SPINNER
        if (photoSize == null && availableResolutions != null && !availableResolutions.isEmpty()) {
            int selectedPosition = resolutionSpinner.getSelectedItemPosition();
            if (selectedPosition >= 0 && selectedPosition < availableResolutions.size()) {
                photoSize = availableResolutions.get(selectedPosition);
            }
        }
        if (photoSize == null) {
            photoSize = new Size(1920, 1080); // Default Full HD
        }
        
        // Créer PhotoCapture
        photoCapture = new Camera2PhotoCapture(
            this,
            recorder.cameraDevice, 
            recorder.backgroundHandler
        );
        
        photoCapture.setPhotoSize(photoSize);
        photoCapture.setNightMode(nightMode);
        photoCapture.setHdrMode(hdr);
        
        // Capturer!
        photoCapture.capturePhoto(filepath, new Camera2PhotoCapture.PhotoCallback() {
            @Override
            public void onPhotoSaved(String filepath) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Photo saved: " + filepath, Toast.LENGTH_LONG).show();
                    
                    // Fermer l'app si demandé
                    if (finishAfter) {
                        new Handler().postDelayed(() -> {
                            finishAndRemoveTask(); 
                        }, 300);
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                    
                    // Fermer même en cas d'erreur
                    if (finishAfter) {
                        finishAndRemoveTask();
                    }
                });
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
                    
                    // Fermer seulement si finish_after est true
                    if (intent.getBooleanExtra("finish_after", false)) {
                        finishAndRemoveTask();
                    }
                }
            }, duration * 1000);
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
        
        // Vérifier si un Intent MacroDroid est en attente
        Intent intent = getIntent();
        String mode = intent.getStringExtra("mode");
        boolean autoStart = intent.getBooleanExtra("auto_start", false);
        
        if (textureView.isAvailable() && checkPermissions() && (mode != null || autoStart)) {
            // Si la surface est prête, traiter l'Intent immédiatement
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
        // Ne pas fermer l'enregistrement si c'est un enregistrement en arrière-plan
        if (isRecording && !isBackgroundRecording && !isBackgroundPhoto) {
            stopRecording();
        }
        
        // Ne pas fermer la caméra si enregistrement ou photo en arrière-plan
        if (!isBackgroundRecording && !isBackgroundPhoto) {
            recorder.closeCamera();
        }
        
        super.onPause();
    }
}