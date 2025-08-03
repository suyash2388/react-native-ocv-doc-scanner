package com.mydocumentscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

public class TextRecognitionModule extends ReactContextBaseJavaModule {
    private static final String TAG = "TextRecognitionModule";

    public TextRecognitionModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return "TextRecognitionModule";
    }

    private WritableMap convertRectToMap(Rect rect) {
        WritableMap rectMap = Arguments.createMap();
        rectMap.putInt("left", rect.left);
        rectMap.putInt("top", rect.top);
        rectMap.putInt("right", rect.right);
        rectMap.putInt("bottom", rect.bottom);
        return rectMap;
    }

    private WritableArray convertPointsToArray(Point[] points) {
        WritableArray pointsArray = Arguments.createArray();
        for (Point point : points) {
            WritableMap pointMap = Arguments.createMap();
            pointMap.putInt("x", point.x);
            pointMap.putInt("y", point.y);
            pointsArray.pushMap(pointMap);
        }
        return pointsArray;
    }

    @ReactMethod
    public void recognizeText(String imageBase64, final Callback successCallback, final Callback errorCallback) {
        try {
            // Convert base64 to bitmap
            byte[] decodedString = Base64.decode(imageBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

            // Create InputImage object
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            // Get an instance of TextRecognizer
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            // Process the image
            recognizer.process(image)
                    .addOnSuccessListener(new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text result) {
                            WritableMap response = Arguments.createMap();
                            response.putString("text", result.getText());

                            // Convert text blocks
                            WritableArray blocksArray = Arguments.createArray();
                            for (Text.TextBlock block : result.getTextBlocks()) {
                                WritableMap blockMap = Arguments.createMap();
                                blockMap.putString("text", block.getText());
                                blockMap.putMap("boundingBox", convertRectToMap(block.getBoundingBox()));
                                blockMap.putArray("cornerPoints", convertPointsToArray(block.getCornerPoints()));
                                
                                // Convert lines in this block
                                WritableArray linesArray = Arguments.createArray();
                                for (Text.Line line : block.getLines()) {
                                    WritableMap lineMap = Arguments.createMap();
                                    lineMap.putString("text", line.getText());
                                    lineMap.putMap("boundingBox", convertRectToMap(line.getBoundingBox()));
                                    lineMap.putArray("cornerPoints", convertPointsToArray(line.getCornerPoints()));
                                    
                                    // Convert elements in this line
                                    WritableArray elementsArray = Arguments.createArray();
                                    for (Text.Element element : line.getElements()) {
                                        WritableMap elementMap = Arguments.createMap();
                                        elementMap.putString("text", element.getText());
                                        elementMap.putMap("boundingBox", convertRectToMap(element.getBoundingBox()));
                                        elementMap.putArray("cornerPoints", convertPointsToArray(element.getCornerPoints()));
                                        elementsArray.pushMap(elementMap);
                                    }
                                    lineMap.putArray("elements", elementsArray);
                                    linesArray.pushMap(lineMap);
                                }
                                blockMap.putArray("lines", linesArray);
                                blocksArray.pushMap(blockMap);
                            }
                            response.putArray("blocks", blocksArray);
                            
                            successCallback.invoke(response);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.e(TAG, "Text recognition failed: " + e.getMessage());
                            errorCallback.invoke("Text recognition failed: " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error processing image: " + e.getMessage());
            errorCallback.invoke("Error processing image: " + e.getMessage());
        }
    }
} 