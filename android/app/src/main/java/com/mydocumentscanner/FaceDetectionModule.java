package com.mydocumentscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class FaceDetectionModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;

    public FaceDetectionModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "FaceDetectionModule";
    }

    @ReactMethod
    public void detectFace(String imageBase64, final Callback successCallback, final Callback errorCallback) {
        try {
            // Decode base64 to bitmap
            byte[] decodedString = Base64.decode(imageBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

            // Create input image
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            // Configure face detector options
            FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build();

            // Get face detector instance
            FaceDetector detector = FaceDetection.getClient(options);

            // Process the image
            detector.process(image)
                    .addOnSuccessListener(faces -> {
                        if (faces.size() > 0) {
                            // Get the first detected face
                            Face face = faces.get(0);
                            Rect bounds = face.getBoundingBox();

                            // Crop the face from the original bitmap
                            Bitmap faceBitmap = Bitmap.createBitmap(
                                bitmap,
                                Math.max(0, bounds.left),
                                Math.max(0, bounds.top),
                                Math.min(bounds.width(), bitmap.getWidth() - bounds.left),
                                Math.min(bounds.height(), bitmap.getHeight() - bounds.top)
                            );

                            // Convert the cropped face to base64
                            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                            faceBitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
                            byte[] byteArray = byteArrayOutputStream.toByteArray();
                            String faceBase64 = Base64.encodeToString(byteArray, Base64.DEFAULT);

                            // Create response object
                            WritableMap response = Arguments.createMap();
                            response.putString("faceImage", faceBase64);
                            response.putDouble("smileProb", face.getSmilingProbability() != null ? face.getSmilingProbability() : 0);
                            response.putDouble("rightEyeOpenProb", face.getRightEyeOpenProbability() != null ? face.getRightEyeOpenProbability() : 0);
                            response.putDouble("leftEyeOpenProb", face.getLeftEyeOpenProbability() != null ? face.getLeftEyeOpenProbability() : 0);

                            // Clean up bitmaps
                            faceBitmap.recycle();
                            bitmap.recycle();

                            successCallback.invoke(response);
                        } else {
                            bitmap.recycle();
                            errorCallback.invoke("No face detected");
                        }
                    })
                    .addOnFailureListener(e -> {
                        bitmap.recycle();
                        errorCallback.invoke("Error detecting face: " + e.getMessage());
                    });

        } catch (Exception e) {
            errorCallback.invoke("Error processing image: " + e.getMessage());
        }
    }
} 