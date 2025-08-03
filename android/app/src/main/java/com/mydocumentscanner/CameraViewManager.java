package com.mydocumentscanner;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opencv.core.Point;
import java.util.Map;
import com.facebook.react.common.MapBuilder;
import java.util.List;
import android.util.Log;
import android.widget.FrameLayout;

public class CameraViewManager extends SimpleViewManager<FrameLayout> {

    public static final String REACT_CLASS = "CameraView";
    public static final int COMMAND_PAUSE_SCANNING = 1;
    public static final int COMMAND_RESUME_SCANNING = 2;
    public static final int COMMAND_SET_EXPECTED_RATIO = 3;
    public static final String EVENT_ON_FEEDBACK = "onFeedback";
    public static final String EVENT_ON_OVERLAY_UPDATE = "onOverlayUpdate";
    
    // Static reference to current overlay view for real-time updates
    private static OverlayView currentOverlayView;

    @NonNull
    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    public Map<String, Integer> getCommandsMap() {
        return MapBuilder.of(
                "pauseScanning", COMMAND_PAUSE_SCANNING,
                "resumeScanning", COMMAND_RESUME_SCANNING,
                "setExpectedRatio", COMMAND_SET_EXPECTED_RATIO
        );
    }

    @NonNull
    @Override
    protected FrameLayout createViewInstance(ThemedReactContext reactContext) {
        // Create container to hold camera preview and overlay
        FrameLayout container = new FrameLayout(reactContext);
        
        // Create camera preview
        CameraPreview cameraPreview = new CameraPreview(reactContext);
        cameraPreview.setFrameListener(new CombinedListener(reactContext));
        
        // Create overlay view
        OverlayView overlayView = new OverlayView(reactContext);
        overlayView.setOverlayVisible(true);
        
        // Set static reference for real-time updates
        currentOverlayView = overlayView;
        
        // Connect camera preview with overlay
        cameraPreview.setOverlayView(overlayView);
        
        // Add views to container
        FrameLayout.LayoutParams cameraParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        
        container.addView(cameraPreview, cameraParams);
        container.addView(overlayView, overlayParams);
        
        // Store references as tags for command handling
        container.setTag(cameraPreview); // Store camera preview as the main tag
        
        Log.d("CameraViewManager", "Created camera container with overlay");
        
        return container;
    }

    @Override
    public void receiveCommand(@NonNull FrameLayout container, String commandId, @Nullable ReadableArray args) {
        Log.d("CameraViewManager", "Received command: " + commandId);
        if (args != null) {
            Log.d("CameraViewManager", "Command args size: " + args.size());
            for (int i = 0; i < args.size(); i++) {
                Log.d("CameraViewManager", "Arg " + i + ": " + args.getDynamic(i).toString());
            }
        } else {
            Log.d("CameraViewManager", "Command args are null");
        }
        
        // Get the camera preview from the container
        CameraPreview cameraPreview = (CameraPreview) container.getTag();
        if (cameraPreview == null) {
            Log.e("CameraViewManager", "Could not find CameraPreview in container");
            return;
        }
        
        switch (commandId) {
            case "pauseScanning":
                Log.d("CameraViewManager", "Executing pauseScanning");
                cameraPreview.pauseScanning();
                break;
            case "resumeScanning":
                Log.d("CameraViewManager", "Executing resumeScanning");
                cameraPreview.resumeScanning();
                break;
            case "setExpectedRatio":
                Log.d("CameraViewManager", "Executing setExpectedRatio");
                if (args != null && args.size() >= 2) {
                    double aspectRatio = args.getDouble(0);
                    String documentType = args.getString(1);
                    Log.d("CameraViewManager", "setExpectedRatio params: aspectRatio=" + aspectRatio + ", documentType=" + documentType);
                    cameraPreview.setExpectedDocumentRatio(aspectRatio, documentType);
                } else {
                    Log.e("CameraViewManager", "setExpectedRatio called with insufficient args. Args size: " + (args != null ? args.size() : "null"));
                }
                break;
            default:
                Log.w("CameraViewManager", "Unknown command: " + commandId);
                break;
        }
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put(EVENT_ON_FEEDBACK, MapBuilder.of("registrationName", EVENT_ON_FEEDBACK))
                .put(EVENT_ON_OVERLAY_UPDATE, MapBuilder.of("registrationName", EVENT_ON_OVERLAY_UPDATE))
                .build();
    }
    
    /**
     * Combined listener that implements FrameListener with overlay support
     */
    private static class CombinedListener implements CameraPreview.FrameListener {
        private final ThemedReactContext reactContext;
        
        public CombinedListener(ThemedReactContext reactContext) {
            this.reactContext = reactContext;
        }
        
        @Override
        public void onDocumentDetected(@Nullable List<Point> corners, int frameWidth, int frameHeight, @Nullable String croppedImageBase64) {
            WritableMap event = Arguments.createMap();
            if (corners != null && corners.size() == 4) {
                WritableArray cornersArray = Arguments.createArray();
                for (Point corner : corners) {
                    WritableMap pointMap = Arguments.createMap();
                    pointMap.putDouble("x", corner.x);
                    pointMap.putDouble("y", corner.y);
                    cornersArray.pushMap(pointMap);
                }
                event.putArray("corners", cornersArray);
                event.putString("croppedImage", croppedImageBase64);
            } else {
                event.putNull("corners");
                event.putNull("croppedImage");
            }
            event.putInt("frameWidth", frameWidth);
            event.putInt("frameHeight", frameHeight);

            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("DocumentDetected", event);
        }

        @Override
        public void onImageCaptured(String imagePath) {
            WritableMap event = Arguments.createMap();
            event.putString("imagePath", imagePath);
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("ImageCaptured", event);
        }

        @Override
        public void onFeedback(String feedbackMessage) {
            WritableMap event = Arguments.createMap();
            event.putString("feedbackMessage", feedbackMessage);

            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("onFeedback", event);
        }
        
        @Override
        public void onOverlayUpdate(double x, double y, double width, double height) {
            WritableMap event = Arguments.createMap();
            event.putDouble("x", x);
            event.putDouble("y", y);
            event.putDouble("width", width);
            event.putDouble("height", height);

            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("onOverlayUpdate", event);
        }
        
        @Override
        public void onDocumentContoursDetected(@Nullable List<Point> bestContour, 
                @Nullable List<List<Point>> allValidContours, int frameWidth, int frameHeight) {
            
            // Update overlay with document contours for real-time visual feedback
            updateOverlayWithContours(bestContour, frameWidth, frameHeight);
            
            WritableMap event = Arguments.createMap();
            
            // Add best contour
            if (bestContour != null) {
                WritableArray bestContourArray = Arguments.createArray();
                for (Point point : bestContour) {
                    WritableMap pointMap = Arguments.createMap();
                    pointMap.putDouble("x", point.x);
                    pointMap.putDouble("y", point.y);
                    bestContourArray.pushMap(pointMap);
                }
                event.putArray("bestContour", bestContourArray);
            }
            
            // Add all valid contours (limit to 3 for performance)
            if (allValidContours != null && !allValidContours.isEmpty()) {
                WritableArray allContoursArray = Arguments.createArray();
                int maxContours = Math.min(3, allValidContours.size());
                for (int i = 0; i < maxContours; i++) {
                    List<Point> contour = allValidContours.get(i);
                    WritableArray contourArray = Arguments.createArray();
                    for (Point point : contour) {
                        WritableMap pointMap = Arguments.createMap();
                        pointMap.putDouble("x", point.x);
                        pointMap.putDouble("y", point.y);
                        contourArray.pushMap(pointMap);
                    }
                    allContoursArray.pushArray(contourArray);
                }
                event.putArray("allContours", allContoursArray);
            }
            
            event.putInt("frameWidth", frameWidth);
            event.putInt("frameHeight", frameHeight);
            
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit("onDocumentContoursDetected", event);
        }

        /**
         * Update overlay view with detected document contours
         */
        private void updateOverlayWithContours(@Nullable List<Point> contours, int frameWidth, int frameHeight) {
            // Find the overlay view in the container
            if (reactContext.getCurrentActivity() != null) {
                reactContext.getCurrentActivity().runOnUiThread(() -> {
                    try {
                        // Get the container from the view manager
                        // This will be called from the camera preview, so we need to find the overlay
                        // We'll use a static reference approach
                        if (currentOverlayView != null) {
                            if (contours != null && contours.size() == 4) {
                                currentOverlayView.updateDocumentContours(contours, frameWidth, frameHeight);
                            } else {
                                currentOverlayView.clearDocumentContours();
                            }
                        }
                    } catch (Exception e) {
                        // Ignore overlay update errors
                    }
                });
            }
        }
    }
}
