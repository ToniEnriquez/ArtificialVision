package com.example.vision;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.vision.BuildConfig;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextToSpeech tts;
    private String lastSpoken = "Nothing yet";
    private long lastSpokenTime = 0;
    private static final long COOLDOWN_MS = 1000;

    private Camera camera;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;

    private ObjectDetector detector;
    private static final int PERMISSION_REQUESTS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.cameraPreview);
        Button btnScan = findViewById(R.id.btnFlash);
        Button btnRepeat = findViewById(R.id.btnRepeat);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
                tts.setPitch(1.1f);
                speak("Camera ready. Let's see what's in front of you.");
            }
        });

        btnScan.setText("🔍 Scan");
        btnScan.setOnClickListener(v -> captureAndDescribeFrame());

        btnRepeat.setOnClickListener(v -> speak(lastSpoken));

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, PERMISSION_REQUESTS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build();

        detector = ObjectDetection.getClient(options);

        Log.d("DEBUG_API_KEY", "BuildConfig.OPENAI_API_KEY = " + BuildConfig.OPENAI_API_KEY);

    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

            } catch (Exception e) {
                Log.e("Camera", "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImageProxy(ImageProxy image) {
        try {
            if (image == null || image.getImage() == null) {
                image.close();
                return;
            }

            InputImage inputImage = InputImage.fromMediaImage(
                    image.getImage(), image.getImageInfo().getRotationDegrees());

            detector.process(inputImage)
                    .addOnSuccessListener(detectedObjects -> {
                        int screenCenterX = previewView.getWidth() / 2;
                        int screenCenterY = previewView.getHeight() / 2;
                        int toleranceX = previewView.getWidth() / 5;
                        int toleranceY = previewView.getHeight() / 5;

                        for (DetectedObject object : detectedObjects) {
                            Rect box = object.getBoundingBox();
                            int boxCenterX = box.centerX();
                            int boxCenterY = box.centerY();

                            boolean isCentered = Math.abs(boxCenterX - screenCenterX) < toleranceX &&
                                    Math.abs(boxCenterY - screenCenterY) < toleranceY;

                            List<DetectedObject.Label> labels = object.getLabels();
                            if (isCentered && !labels.isEmpty()) {
                                String labelText = labels.get(0).getText();
                                if (labelText != null && !labelText.isEmpty()) {
                                    long now = System.currentTimeMillis();
                                    if (!labelText.equalsIgnoreCase(lastSpoken) || now - lastSpokenTime > COOLDOWN_MS) {
                                        lastSpoken = getFriendlyLabel(labelText);
                                        lastSpokenTime = now;
                                        speak(lastSpoken);
                                    }
                                }
                            }
                        }
                    })
                    .addOnFailureListener(e -> Log.e("ObjectDetection", "Detection failed", e))
                    .addOnCompleteListener(task -> image.close());

        } catch (Exception e) {
            image.close();
        }
    }

    private void captureAndDescribeFrame() {
        speak("Analyzing image, please wait...");

        File photoFile = new File(getCacheDir(), "capture.jpg");
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                if (bitmap != null) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                    byte[] imageBytes = byteArrayOutputStream.toByteArray();
                    String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                    runOnUiThread(() -> sendImageToOpenAI(base64Image));
                } else {
                    runOnUiThread(() -> speak("Couldn't read captured image."));
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> speak("Capture failed."));
                Log.e("VisionAid", "Image capture failed", exception);
            }
        });
    }

    private void sendImageToOpenAI(String base64Image) {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.openai.com/v1/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY); //get API key from local.properties
                // use this if buildConfig not working: String apiKey = "sk-xxxxxx" // real API key here
                // use this too if buildConfig not working: conn.setRequestProperty("Authorization", "Bearer " + apiKey);


                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject message = new JSONObject();
                message.put("role", "user");
                message.put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "Describe the contents of this image."))
                        .put(new JSONObject().put("type", "image_url").put("image_url",
                                new JSONObject().put("url", "data:image/png;base64," + base64Image))));

                JSONObject payload = new JSONObject();
                payload.put("model", "gpt-4-turbo");

                payload.put("messages", new JSONArray().put(message));
                payload.put("max_tokens", 200);

                OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                writer.write(payload.toString());
                writer.flush();

                StringBuilder response = new StringBuilder();
                Scanner scanner;
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    scanner = new Scanner(conn.getInputStream());
                } else {
                    scanner = new Scanner(conn.getErrorStream()); // Read error body
                }
                while (scanner.hasNext()) {
                    response.append(scanner.nextLine());
                }
                Log.e("OpenAI Response", response.toString());



                JSONObject result = new JSONObject(response.toString());

                if (result.has("choices")) {
                    String reply = result.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
                    runOnUiThread(() -> speak(reply.trim()));
                } else if (result.has("error")) {
                    String errMsg = result.getJSONObject("error").optString("message", "Unknown error");
                    runOnUiThread(() -> speak("OpenAI error: " + errMsg));
                    Log.e("OpenAI Error", errMsg);
                }

            } catch (Exception e) {
                runOnUiThread(() -> speak("Failed to contact OpenAI."));
                Log.e("OpenAI", "Error", e);
            }
        }).start();
    }

    private String getFriendlyLabel(String raw) {
        if (raw == null) return "something";
        switch (raw.toLowerCase()) {
            case "handbag":
            case "fashion good":
                return "bag";
            case "home good":
                return "household item";
            case "unknown":
                return "something";
            default:
                return raw;
        }
    }

    private void speak(String message) {
        if (tts != null) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }


    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUESTS && allPermissionsGranted()) {
            startCamera();
        } else {
            Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
