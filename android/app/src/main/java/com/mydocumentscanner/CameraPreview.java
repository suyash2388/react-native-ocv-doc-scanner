// CameraPreview.java

package com.mydocumentscanner;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.os.Environment;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;

public class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "CameraPreview";
    private Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private ImageReader imageReader;
    private int sensorOrientation;

    private int imageWidth = 1920; // or the highest supported width
    private int imageHeight = 1080; // or the corresponding height
    private int lastProcessedRotation = -1; // Track last rotation to avoid dimension swapping every frame

    private List<Point> docCorners = null;

    private long lastProcessedTime = 0;
    private static final long PROCESSING_INTERVAL_MS = 100; // Process every 100ms (10 FPS) for faster response
    private static final long MIN_DETECTION_INTERVAL_MS = 200; // Minimum time between successful detections

    private boolean isScanningPaused = false;
    private boolean isCurrentlyProcessing = false; // Prevent processing queue buildup

    // Auto-resume scanning after successful capture
    // private static final long AUTO_RESUME_DELAY_MS = 3000; // 3 seconds
    // private Runnable autoResumeRunnable = null;

    // Scan region variables (in screen coordinates)
    private double scanRegionX = 0;
    private double scanRegionY = 0;
    private double scanRegionWidth = 0;
    private double scanRegionHeight = 0;
    private boolean hasScanRegion = false;

    // Expected document aspect ratio for validation (ignoring for now)
    private double expectedAspectRatio = 0.0;
    private String documentType = "Unknown";

    // Rectangle overlay settings
    private boolean showRectangleOverlay = true;
    private int overlayColor = 0xFF00FF00; // Green color (ARGB format)
    
    // Document detection configuration - optimized for speed
    private int numOfRectangles = 5; // Reduced from 10 to 5 for faster capture
    private volatile int numOfSquares = 0; // Current detection count
    private boolean autoCapture = true; // Enable auto-capture when document is detected

    // // Mat object pool to reduce allocation overhead
    // private Mat pooledFrame = null;
    // private Mat pooledGray = null;
    // private Mat pooledResized = null;

    // Pipeline state tracking
    // private boolean lastFrameHadDocument = false;
    // private long lastDocumentTime = 0;
    // private static final long DOCUMENT_CONFIDENCE_WINDOW_MS = 1000; // 1 second confidence window

    // Feedback tolerance tracking
    // private int consecutiveDetectionFailures = 0;
    // private static final int DETECTION_FAILURE_THRESHOLD = 3; // Show positioning message after 3 consecutive failures

    // // Pipeline failure tracking for specific feedback
    // private boolean blurDetected = false;
    // private boolean glareDetected = false;

    // Feedback message tracking to prevent repeated messages
    private String lastFeedbackMessage = "";
    private long lastFeedbackTime = 0;

    // Overlay view for scan region visualization
    private OverlayView overlayView;
    
    // Camera characteristics
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK; // Default to back camera
    private boolean enableImageFlipCorrection = true; // Flag to enable/disable flip correction
    
    // Blur detection settings
    private boolean enableBlurDetection = true; // Enable blur detection
    private double blurThreshold = 100.0; // Laplacian variance threshold for blur detection
    private int blurDetectionCount = 0; // Count of consecutive blur detections
    private static final int MAX_BLUR_COUNT = 3; // Consecutive blur detections before filtering
    
    // Manual cropping settings
    private int detectionFailureCount = 0; // Count of consecutive detection failures
    private static final int MAX_DETECTION_FAILURES = 15; // Max failures before offering manual crop (increased from 5)
    private boolean manualCropMode = false; // Whether in manual cropping mode
    private Mat lastProcessedFrame = null; // Store the last processed frame for manual cropping



    public interface FrameListener {
        void onDocumentDetected(@Nullable List<Point> corners, int frameWidth, int frameHeight,
                @Nullable String croppedImageBase64);

        void onImageCaptured(String imagePath);

        void onFeedback(String feedbackMessage);

        void onOverlayUpdate(double x, double y, double width, double height);
        
        void onDocumentContoursDetected(@Nullable List<Point> bestContour, @Nullable List<List<Point>> allValidContours, 
                int frameWidth, int frameHeight);
                
        void onManualCropNeeded(int frameWidth, int frameHeight, String frameImageBase64);
    }

    private FrameListener frameListener;

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    public CameraPreview(Context context) {
        super(context);
        init(context);
    }

    public CameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CameraPreview(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context ctx) {
        context = ctx;
        setSurfaceTextureListener(this);
        // Initialize template matching module
        try {
            ReactApplicationContext reactContext = new ReactApplicationContext(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize template matching module: " + e.getMessage());
        }
    }

    public void pauseScanning() {
        isScanningPaused = true;
    }

    public void resumeScanning() {
        isScanningPaused = false;
    }

    public void setExpectedDocumentRatio(double aspectRatio, String docType) {
        this.expectedAspectRatio = aspectRatio;
        this.documentType = docType;
        
        // Calculate optimal scan region based on aspect ratio
        calculateOptimalScanRegion(aspectRatio);
        
        Log.d(TAG, "Expected document ratio set: " + aspectRatio + " for " + docType);
    }
    private void calculateOptimalScanRegion(double aspectRatio) {
        // Get the actual frame dimensions after rotation
        int rotation = getImageRotation();
        double frameWidth, frameHeight;
        
        // Account for rotation when determining frame dimensions
        if (rotation == 90 || rotation == 270) {
            // Portrait mode - swap dimensions
            frameWidth = imageHeight;
            frameHeight = imageWidth;
        } else {
            // Landscape mode - keep original dimensions  
            frameWidth = imageWidth;
            frameHeight = imageHeight;
        }

        // Calculate optimal scan region size that fits within the frame
        double maxWidth = frameWidth * 0.85; // Use 85% of frame width for better margins
        double maxHeight = frameHeight * 0.85; // Use 85% of frame height for better margins
        
        double regionWidth, regionHeight;
        
        // Calculate dimensions based on aspect ratio, ensuring they fit in frame
        double widthBasedHeight = maxWidth / aspectRatio;
        double heightBasedWidth = maxHeight * aspectRatio;
        
        if (widthBasedHeight <= maxHeight) {
            // Width-constrained: use max width, calculate height
            regionWidth = maxWidth;
            regionHeight = widthBasedHeight;
        } else {
            // Height-constrained: use max height, calculate width
            regionWidth = heightBasedWidth;
            regionHeight = maxHeight;
        }
        
        // Ensure minimum size requirements
        regionWidth = Math.max(regionWidth, 200);
        regionHeight = Math.max(regionHeight, 200);

        // Center the scan region in the frame
        scanRegionWidth = regionWidth;
        scanRegionHeight = regionHeight;
        scanRegionX = (frameWidth - scanRegionWidth) / 2.0;
        scanRegionY = (frameHeight - scanRegionHeight) / 2.0;

        hasScanRegion = true;
        Log.d(TAG, String.format("Scan region calculated (rotation=%d°): x=%.1f, y=%.1f, w=%.1f, h=%.1f (aspectRatio=%.3f)", 
               rotation, scanRegionX, scanRegionY, scanRegionWidth, scanRegionHeight, aspectRatio));
        Log.d(TAG, String.format("Frame size: %.0fx%.0f, Region size: %.1fx%.1f, Actual aspect: %.3f", 
               frameWidth, frameHeight, scanRegionWidth, scanRegionHeight, scanRegionWidth/scanRegionHeight));
        
        // Update overlay coordinates immediately
        updateOverlayCoordinates();
    }
    /**
     * Update overlay coordinates for external overlay view - matches actual processing region
     */
    private void updateOverlayCoordinates() {
        if (overlayView != null && hasScanRegion && expectedAspectRatio > 0) {
            // Get view dimensions
            int viewWidth = getWidth();
            int viewHeight = getHeight();
    
            if (viewWidth > 0 && viewHeight > 0) {
                // Get actual frame dimensions accounting for rotation
                int rotation = getImageRotation();
                double frameWidth, frameHeight;
                if (rotation == 90 || rotation == 270) {
                    frameWidth = imageHeight;
                    frameHeight = imageWidth;
                } else {
                    frameWidth = imageWidth;
                    frameHeight = imageHeight;
                }
                
                // Calculate scale factors from frame to view
                double scaleX = (double) viewWidth / frameWidth;
                double scaleY = (double) viewHeight / frameHeight;
                
                // Transform the actual scan region coordinates to view coordinates
                double overlayLeft = scanRegionX * scaleX;
                double overlayTop = scanRegionY * scaleY;
                double overlayWidth = scanRegionWidth * scaleX;
                double overlayHeight = scanRegionHeight * scaleY;
                
                Log.d(TAG, String.format("Frame: %.0fx%.0f, View: %dx%d, Scale: %.3fx%.3f", 
                    frameWidth, frameHeight, viewWidth, viewHeight, scaleX, scaleY));
                Log.d(TAG, String.format("Scan region in frame: (%.1f,%.1f,%.1fx%.1f)", 
                    scanRegionX, scanRegionY, scanRegionWidth, scanRegionHeight));
                Log.d(TAG, String.format("Overlay in view: (%.1f,%.1f,%.1fx%.1f) aspect=%.3f", 
                    overlayLeft, overlayTop, overlayWidth, overlayHeight, expectedAspectRatio));
    
                overlayView.updateScanRegion(overlayLeft, overlayTop, overlayWidth, overlayHeight);
            }
        }
    }

    public void setOverlayView(OverlayView overlay) {
        this.overlayView = overlay;
    }
    /**
     * Send feedback only if message is different or enough time has passed
     * This prevents instruction text from persisting too long
     */
    private void sendFeedbackIfNeeded(String message) {
        long currentTime = System.currentTimeMillis();
        boolean isDifferentMessage = !message.equals(lastFeedbackMessage);
        boolean timeElapsed = (currentTime - lastFeedbackTime) > 2000; // 2 seconds

        if (isDifferentMessage || timeElapsed) {
            if (frameListener != null) {
                frameListener.onFeedback(message);
                lastFeedbackMessage = message;
                lastFeedbackTime = currentTime;
                Log.d(TAG, "Feedback sent: " + message);
            }
        }
    }

    public void openCamera() {
        Log.d(TAG, "Opening camera - Starting initialization");
        startBackgroundThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    // Permission is not granted
                    return;
                }
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException", e);
        }
    }

    private int getDeviceRotation() {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        return rotation;
    }

    private int getImageRotation() {
        int deviceRotation = deviceRotationDegrees(getDeviceRotation());
        int rotationCompensation = (sensorOrientation + deviceRotation + 360) % 360;
        return rotationCompensation;
    }

    private int deviceRotationDegrees(int deviceRotation) {
        switch (deviceRotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 90;
            default:
                return 0;
        }
    }

    public void closeCamera() {
        // Cancel any pending auto-resume
        // if (autoResumeRunnable != null && backgroundHandler != null) {
        //     backgroundHandler.removeCallbacks(autoResumeRunnable);
        //     autoResumeRunnable = null;
        // }

        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        // Clean up pooled Mat objects
        // if (pooledFrame != null) {
        //     pooledFrame.release();
        //     pooledFrame = null;
        // }
        // if (pooledGray != null) {
        //     pooledGray.release();
        //     pooledGray = null;
        // }
        // if (pooledResized != null) {
        //     pooledResized.release();
        //     pooledResized = null;
        // }

        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera opened successfully - Creating preview session");
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera disconnected");
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            closeCamera();
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(imageWidth, imageHeight);
            Surface surface = new Surface(texture);

            // Set up ImageReader to receive camera frames - use smaller buffer for better
            // performance
            imageReader = ImageReader.newInstance(imageWidth, imageHeight, ImageFormat.YUV_420_888, 1);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            final CaptureRequest.Builder previewRequestBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null)
                                return;

                            Log.d(TAG, "Camera capture session configured - Starting preview");
                            captureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                CaptureRequest previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "CameraAccessException in createCameraPreviewSession", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure camera preview session");
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException in createCameraPreviewSession", e);
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        long currentTime = System.currentTimeMillis();

        // Skip if we're currently processing or if we're within the interval
        if (isCurrentlyProcessing || currentTime - lastProcessedTime < PROCESSING_INTERVAL_MS) {
            // Always clear the queue to prevent buildup - get latest frame and discard
            Image image = reader.acquireLatestImage();
            if (image != null) {
                image.close();
            }
            return;
        } else {
            Log.d(TAG, "Proceeding to process frame: processing=" + isCurrentlyProcessing + ", interval="
                    + (currentTime - lastProcessedTime) + "ms");
        }

        lastProcessedTime = currentTime;
        // isCurrentlyProcessing flag will be set in processDocumentDetectionAsync

        Image image = null;
        try {
            // Get the latest available image (ImageReader maxImages=1, so only one at a
            // time)
            image = reader.acquireLatestImage();

            // Process the frame if we got one
            if (image != null) {
                Log.d(TAG, "Processing image: " + image.getWidth() + "x" + image.getHeight());
                processImage(image);
            } else {
                Log.d(TAG, "No image available to process");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        } finally {
            if (image != null) {
                image.close();
                Log.d(TAG, "Image closed successfully");
            }
            isCurrentlyProcessing = false;
        }
    };

    private int getOpenCvRotationCode(int rotationDegrees) {
        switch (rotationDegrees) {
            case 90:
                return Core.ROTATE_90_CLOCKWISE;
            case 180:
                return Core.ROTATE_180;
            case 270:
                return Core.ROTATE_90_COUNTERCLOCKWISE;
            default:
                return -1; // No rotation
        }
    }

    private void processImage(Image image) {
        if (isScanningPaused) {
            Log.d(TAG, "Scanning is paused, skipping frame");
            return;
        }

        // Check if rotation has changed and recalculate scan region if needed
        int currentRotation = getImageRotation();
        if (currentRotation != lastProcessedRotation && expectedAspectRatio > 0) {
            Log.d(TAG, "Rotation changed from " + lastProcessedRotation + "° to " + currentRotation + "°, recalculating scan region");
            calculateOptimalScanRegion(expectedAspectRatio);
            lastProcessedRotation = currentRotation;
        }

        // Update overlay coordinates on every frame
        updateOverlayCoordinates();

        // Convert image to Mat for processing only when we're going to use it
        Mat frame = imageToMat(image);
        
        if (frame == null || frame.empty()) {
            Log.e(TAG, "Failed to convert image to Mat");
            return;
        }

        // Apply rotation to match display orientation
        Mat rotatedFrame = applyDisplayRotation(frame);
        frame.release();

        Log.d(TAG, "Starting async document detection for frame: " + rotatedFrame.width() + "x" + rotatedFrame.height());

        // Process document detection asynchronously
        processDocumentDetectionAsync(rotatedFrame);
    }

    /**
     * Apply rotation to match the display orientation
     */
    private Mat applyDisplayRotation(Mat frame) {
        int rotation = getImageRotation();
        int rotationCode = getOpenCvRotationCode(rotation);
        
        if (rotationCode == -1) {
            Log.d(TAG, "No rotation needed, rotation: " + rotation);
            return frame;
        }
        
        Mat rotated = new Mat();
        Core.rotate(frame, rotated, rotationCode);
        Log.d(TAG, "Applied rotation: " + rotation + "° (code: " + rotationCode + "), frame: " + 
              frame.width() + "x" + frame.height() + " -> " + rotated.width() + "x" + rotated.height());
        
        return rotated;
    }

    /**
     * Process document detection asynchronously to avoid blocking camera thread
     */
    private void processDocumentDetectionAsync(Mat frame) {
        // Skip frames if processing is already in progress
        if (isCurrentlyProcessing) {
            Log.d(TAG, "⏭️ Skipping frame - processing already in progress");
            frame.release();
            return;
        }
        
        if (backgroundHandler != null) {
            isCurrentlyProcessing = true;
            Log.d(TAG, "📋 Set processing flag to true, starting background task");
            backgroundHandler.post(() -> {
                try {
                    Log.d(TAG, "🚀 Background task started, calling fast document detection");
                    detectDocumentWithGrabCut(frame); // This will internally use fast segmentation
                    Log.d(TAG, "✅ Document detection completed successfully");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error in document detection", e);
                } finally {
                    frame.release();
                    isCurrentlyProcessing = false; // Reset flag when done
                    Log.d(TAG, "🏁 Background task finished, processing flag reset to false");
                }
            });
        } else {
            frame.release();
        }
    }

    /**
     * Enterprise-grade robust document detection for complex backgrounds
     */
    private void detectDocumentWithGrabCut(Mat originalFrame) {
        Log.d(TAG, "🔍 Starting enterprise-grade document detection");
        
        if (originalFrame == null || originalFrame.empty()) {
            Log.e(TAG, "❌ Original frame is null or empty");
            return;
        }

        Log.d(TAG, "📏 Original frame size: " + originalFrame.width() + "x" + originalFrame.height());

        Mat frame = null;
        Mat gray = null;
        Mat enhanced = null;
        Mat edges = null;
        
        try {
            // 1. Crop to scan region if available for more focused detection
            Mat croppedFrame = null;
            double cropOffsetX = 0, cropOffsetY = 0;
            
            if (hasScanRegion && scanRegionWidth > 50 && scanRegionHeight > 50) {
                // Calculate crop region with some padding
                int padding = 100; // Increased padding for better context
                int cropX = Math.max(0, (int)(scanRegionX - padding));
                int cropY = Math.max(0, (int)(scanRegionY - padding));
                int cropW = Math.min(originalFrame.width() - cropX, (int)(scanRegionWidth + 2*padding));
                int cropH = Math.min(originalFrame.height() - cropY, (int)(scanRegionHeight + 2*padding));
                
                if (cropW > 200 && cropH > 200) {
                    org.opencv.core.Rect cropRect = new org.opencv.core.Rect(cropX, cropY, cropW, cropH);
                    croppedFrame = new Mat(originalFrame, cropRect);
                    cropOffsetX = cropX;
                    cropOffsetY = cropY;
                    Log.d(TAG, "🔍 Cropped to scan region: " + cropX + "," + cropY + " " + cropW + "x" + cropH);
                } else {
                    Log.d(TAG, "📐 Scan region too small, using full frame");
                    croppedFrame = originalFrame.clone();
                }
            } else {
                Log.d(TAG, "📐 No scan region defined, using full frame");
                croppedFrame = originalFrame.clone();
            }

            // 2. Ultra-fast processing - minimal resolution for speed
            int targetHeight = Math.min(200, croppedFrame.rows()); // Even lower resolution for speed
            double ratio = (double) targetHeight / croppedFrame.rows();
            
            frame = new Mat();
            int newWidth = (int) (croppedFrame.cols() * ratio);
            
            if (newWidth < 150 || targetHeight < 150) {
                Log.w(TAG, "Using original cropped frame size for processing");
                croppedFrame.copyTo(frame);
                ratio = 1.0;
            } else {
                Imgproc.resize(croppedFrame, frame, new Size(newWidth, targetHeight), 0, 0, Imgproc.INTER_LINEAR); // Faster interpolation
            }
            
            // Skip debug saves for performance
            // saveCroppedMat(originalFrame, "01_originalFrame");
            // saveCroppedMat(frame, "02_resizedFrame");
            croppedFrame.release();
            
            Log.d(TAG, "📐 Processing frame: " + frame.width() + "x" + frame.height() + " (ratio: " + ratio + ")");

            // Store frame for potential manual cropping
            if (lastProcessedFrame != null) {
                lastProcessedFrame.release();
            }
            lastProcessedFrame = originalFrame.clone();

            // 3. Ultra-fast document detection
            Log.d(TAG, "⚡ Using ultra-fast document detection");
            
            // Simple document detection without aspect ratio constraints
            Point[] documentCorners = detectDocumentRealTime(frame, frame.width(), frame.height());
            
            if (documentCorners != null && documentCorners.length == 4) {
                // Transform coordinates back to original frame space
                Point[] originalCorners = transformCornersToOriginalFrame(documentCorners, ratio, 
                    (int)cropOffsetX, (int)cropOffsetY);
                
                // Simple validation: check if it's a reasonable quadrilateral
                if (isValidQuadrilateral(originalCorners)) {
                    // Check for blur before proceeding with detection
                    if (isImageBlurry(originalFrame)) {
                        Log.w(TAG, "⚠️ Blurry image detected, skipping detection");
                        numOfSquares = Math.max(0, numOfSquares - 1); // Decrement count for blur
                        sendFeedbackIfNeeded("Image is blurry. Please hold the camera steady and ensure good lighting.");
                        return; // Skip processing blurry images
                    }
                    
                    // Reset blur counter for sharp images
                    blurDetectionCount = 0;
                    
                    // Reset detection failure counter on successful detection
                    detectionFailureCount = 0;
                    
                    numOfSquares++; // Increment detection count
                    Log.d(TAG, "✅ Document detected! Count: " + numOfSquares + "/" + numOfRectangles);
                    
                    // Check if we have enough consistent detections
                    if (numOfSquares >= numOfRectangles) {
                        Log.d(TAG, "🎯 Stable document detection achieved!");
                        
                        // Final blur check before capture
                        if (isImageBlurry(originalFrame)) {
                            Log.w(TAG, "⚠️ Final blur check failed, skipping capture");
                            numOfSquares = Math.max(0, numOfSquares - 2); // Decrement more for blur
                            sendFeedbackIfNeeded("Image is blurry. Please hold the camera steady for a clear capture.");
                            return;
                        }
                        
                        // Enhanced perspective transformation
                        Mat croppedDocument = performSimplePerspectiveTransform(originalFrame, originalCorners);
                        String base64Image = null;
                        
                        if (croppedDocument != null) {
                            base64Image = matToBase64(croppedDocument);
                            croppedDocument.release();
                        }
                        
                        // Notify listener with results
                        List<Point> cornersList = Arrays.asList(originalCorners);
                        if (frameListener != null) {
                            frameListener.onDocumentDetected(cornersList, originalFrame.width(), 
                                originalFrame.height(), base64Image);
                        }
                        
                        // Reset counter after successful detection for next capture
                        if (autoCapture) {
                            numOfSquares = 0; // Reset for next detection
                        } else {
                            // Keep some detections to maintain overlay visibility
                            numOfSquares = Math.max(1, numOfSquares - 2);
                        }
                    }
                    
                    // Send real-time contour visualization
                    List<Point> cornersList = Arrays.asList(originalCorners);
                    if (frameListener != null) {
                        frameListener.onDocumentContoursDetected(cornersList, null, 
                            originalFrame.width(), originalFrame.height());
                    }
                } else {
                    // Only decrement if we've had several consecutive invalid detections
                    numOfSquares = Math.max(0, numOfSquares - 1);
                    Log.w(TAG, "⚠️ Invalid quadrilateral detected, count: " + numOfSquares);
                    sendFeedbackIfNeeded("Document shape not recognized. Please ensure the document is fully visible and has clear edges.");
                }
            } else {
                // Only decrement every few frames to maintain stability
                if (numOfSquares > 0) {
                    numOfSquares = Math.max(0, numOfSquares - 1);
                    Log.d(TAG, "📉 No document found, count: " + numOfSquares);
                }
                
                // Increment detection failure counter
                detectionFailureCount++;
                Log.d(TAG, "🔄 Detection failure count: " + detectionFailureCount + "/" + MAX_DETECTION_FAILURES);
                
                // Offer manual cropping after repeated failures
                if (detectionFailureCount >= MAX_DETECTION_FAILURES && !manualCropMode) {
                    Log.i(TAG, "🔧 Offering manual cropping after " + detectionFailureCount + " failures");
                    offerManualCropping(originalFrame);
                }
                
                // Clear overlay only if no detections for a while
                if (numOfSquares == 0 && frameListener != null) {
                    frameListener.onDocumentContoursDetected(null, null, 
                        originalFrame.width(), originalFrame.height());
                }
            }

            // Clean up handled in individual methods
            
        } catch (Exception e) {
            Log.e(TAG, "Error in document detection", e);
            sendFeedbackIfNeeded("Processing error. Please try again.");
        } finally {
            // Clean up all Mat objects
            if (frame != null) frame.release();
            if (gray != null) gray.release();
            if (enhanced != null) enhanced.release();
            if (edges != null) edges.release();
        }
    }

    /**
     * Advanced preprocessing pipeline for robust document detection on complex backgrounds
     */
    private Mat performAdvancedPreprocessing(Mat inputFrame) {
        Log.d(TAG, "🎨 Starting advanced preprocessing pipeline");
        
        Mat gray = null;
        Mat shadowCompensated = null;
        Mat contrastEnhanced = null;
        Mat denoised = null;
        Mat enhanced = null;
        
        try {
            // 1. Convert to grayscale if needed
            if (inputFrame.channels() == 3) {
                gray = new Mat();
                Imgproc.cvtColor(inputFrame, gray, Imgproc.COLOR_RGB2GRAY);
            } else {
                gray = inputFrame.clone();
            }
            saveCroppedMat(gray, "05_gray");
            
            // 2. Shadow and lighting compensation
            shadowCompensated = compensateShadowsAndLighting(gray);
            saveCroppedMat(shadowCompensated, "06_shadowCompensated");
            
            // 3. Adaptive histogram equalization for better contrast
            contrastEnhanced = new Mat();
            CLAHE clahe = Imgproc.createCLAHE(3.0, new Size(8, 8));
            clahe.apply(shadowCompensated, contrastEnhanced);
            saveCroppedMat(contrastEnhanced, "07_contrastEnhanced");
            
            // 4. Advanced denoising while preserving edges
            denoised = new Mat();
            Imgproc.bilateralFilter(contrastEnhanced, denoised, 9, 75, 75);
            saveCroppedMat(denoised, "08_denoised");
            
            // 5. Combine original and enhanced for better document detection
            enhanced = new Mat();
            Core.addWeighted(denoised, 0.7, shadowCompensated, 0.3, 0, enhanced);
            saveCroppedMat(enhanced, "09_finalEnhanced");
            
            Log.d(TAG, "✅ Advanced preprocessing complete");
            return enhanced.clone();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in advanced preprocessing", e);
            // Fallback to basic processing
            if (gray != null) {
                return gray.clone();
            }
            return inputFrame.clone();
        } finally {
            if (gray != null) gray.release();
            if (shadowCompensated != null) shadowCompensated.release();
            if (contrastEnhanced != null) contrastEnhanced.release();
            if (denoised != null) denoised.release();
            if (enhanced != null) enhanced.release();
        }
    }
    
    /**
     * Compensate for shadows and uneven lighting using advanced techniques
     */
    private Mat compensateShadowsAndLighting(Mat grayImage) {
        Log.d(TAG, "💡 Compensating shadows and lighting");
        
        Mat background = null;
        Mat normalized = null;
        Mat result = null;
        
        try {
            // 1. Estimate background using morphological operations
            background = new Mat();
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(19, 19));
            Imgproc.morphologyEx(grayImage, background, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();
            
            // 2. Subtract background to remove lighting variations using division method
            normalized = new Mat();
            Mat backgroundFloat = new Mat();
            Mat grayFloat = new Mat();
            
            // Convert to float for division
            background.convertTo(backgroundFloat, CvType.CV_32F, 1.0/255.0);
            grayImage.convertTo(grayFloat, CvType.CV_32F, 1.0/255.0);
            
            // Divide original by background to normalize lighting
            Core.divide(grayFloat, backgroundFloat, normalized, 255.0);
            
            // Convert back to 8-bit
            result = new Mat();
            normalized.convertTo(result, CvType.CV_8U);
            
            // Clean up intermediate matrices
            backgroundFloat.release();
            grayFloat.release();
            
            Log.d(TAG, "✅ Shadow compensation complete");
            return result.clone();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in shadow compensation", e);
            return grayImage.clone();
        } finally {
            if (background != null) background.release();
            if (normalized != null) normalized.release();
            if (result != null) result.release();
        }
    }
    
    /**
     * Multi-scale edge detection for robust contour finding
     */
    private Mat performMultiScaleEdgeDetection(Mat enhancedImage) {
        Log.d(TAG, "🎯 Performing multi-scale edge detection");
        
        Mat edges1 = null;
        Mat edges2 = null;
        Mat edges3 = null;
        Mat combinedEdges = null;
        Mat finalEdges = null;
        Mat kernel = null;
        
        try {
            // 1. Fine-scale edge detection (small features)
            edges1 = new Mat();
            Imgproc.Canny(enhancedImage, edges1, 50, 100, 3, false);
            saveCroppedMat(edges1, "10_edges_fine");
            
            // 2. Medium-scale edge detection
            edges2 = new Mat();
            Mat blurred = new Mat();
            Imgproc.GaussianBlur(enhancedImage, blurred, new Size(3, 3), 1.0);
            Imgproc.Canny(blurred, edges2, 30, 80, 3, false);
            blurred.release();
            saveCroppedMat(edges2, "11_edges_medium");
            
            // 3. Coarse-scale edge detection (document boundaries)
            edges3 = new Mat();
            Mat blurred2 = new Mat();
            Imgproc.GaussianBlur(enhancedImage, blurred2, new Size(5, 5), 2.0);
            Imgproc.Canny(blurred2, edges3, 20, 60, 3, false);
            blurred2.release();
            saveCroppedMat(edges3, "12_edges_coarse");
            
            // 4. Combine all scales with weighted approach
            combinedEdges = new Mat();
            Core.addWeighted(edges1, 0.3, edges2, 0.4, 0, combinedEdges);
            Core.addWeighted(combinedEdges, 1.0, edges3, 0.3, 0, combinedEdges);
            saveCroppedMat(combinedEdges, "13_edges_combined");
            
            // 5. Morphological closing to connect broken edges
            finalEdges = new Mat();
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(combinedEdges, finalEdges, Imgproc.MORPH_CLOSE, kernel);
            saveCroppedMat(finalEdges, "14_edges_final");
            
            Log.d(TAG, "✅ Multi-scale edge detection complete");
            return finalEdges.clone();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in multi-scale edge detection", e);
            // Fallback to simple Canny
            Mat fallback = new Mat();
            Imgproc.Canny(enhancedImage, fallback, 50, 150);
            return fallback;
        } finally {
            if (edges1 != null) edges1.release();
            if (edges2 != null) edges2.release();
            if (edges3 != null) edges3.release();
            if (combinedEdges != null) combinedEdges.release();
            if (finalEdges != null) finalEdges.release();
            if (kernel != null) kernel.release();
        }
    }

    /**
     * Perform simplified watershed-like segmentation optimized for document detection
     */
    private void performSimplifiedWatershedSegmentation(Mat dilated, Mat watershedResult) {
        try {
            Log.d(TAG, "🔄 Starting simplified watershed segmentation");
            
            // 1. Apply adaptive threshold to handle varying lighting conditions
            Mat adaptive = new Mat();
            Imgproc.adaptiveThreshold(dilated, adaptive, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, 
                Imgproc.THRESH_BINARY, 11, 2);
            Log.d(TAG, "📊 Applied adaptive threshold");

            // 2. Combine with global Otsu threshold for better results
            Mat otsu = new Mat();
            Imgproc.threshold(dilated, otsu, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            Log.d(TAG, "📊 Applied Otsu threshold");

            // 3. Combine both thresholds using bitwise AND
            Mat combined = new Mat();
            Core.bitwise_and(adaptive, otsu, combined);
            Log.d(TAG, "🔗 Combined adaptive and Otsu thresholds");

            // 4. Morphological operations to clean up noise and fill gaps
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            
            // Opening to remove noise
            Mat opened = new Mat();
            Imgproc.morphologyEx(combined, opened, Imgproc.MORPH_OPEN, kernel);
            
            // Closing to fill small gaps
            Mat closed = new Mat();
            Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, kernel);
            Log.d(TAG, "🧽 Applied morphological cleaning");

            // 5. Distance transform to find document centers
            Mat distTransform = new Mat();
            Imgproc.distanceTransform(closed, distTransform, Imgproc.DIST_L2, 3);
            
            // 6. Apply threshold on distance transform to get probable document regions
            Mat distThresh = new Mat();
            Core.MinMaxLocResult minMaxLoc = Core.minMaxLoc(distTransform);
            double threshold = minMaxLoc.maxVal * 0.4; // More lenient threshold for documents
            Imgproc.threshold(distTransform, distThresh, threshold, 255, Imgproc.THRESH_BINARY);
            
            // Convert to 8-bit
            Mat distThresh8U = new Mat();
            distThresh.convertTo(distThresh8U, CvType.CV_8UC1);
            Log.d(TAG, "📏 Applied distance transform segmentation");

            // 7. Final enhancement: combine original dilated with distance-based segmentation
            Mat enhanced = new Mat();
            Core.bitwise_and(dilated, distThresh8U, enhanced);
            
            // If the enhanced result is too dark, fall back to the cleaned binary
            Scalar meanValue = Core.mean(enhanced);
            if (meanValue.val[0] < 50) { // Too dark, use cleaned binary instead
                Log.d(TAG, "⚠️ Enhanced result too dark, using cleaned binary");
                closed.copyTo(watershedResult);
            } else {
                enhanced.copyTo(watershedResult);
            }
            
            Log.d(TAG, "✅ Simplified watershed segmentation complete");

            // Clean up
            adaptive.release();
            otsu.release();
            combined.release();
            kernel.release();
            opened.release();
            closed.release();
            distTransform.release();
            distThresh.release();
            distThresh8U.release();
            enhanced.release();

        } catch (Exception e) {
            Log.e(TAG, "Error in simplified watershed segmentation", e);
            // Fallback: just copy the dilated image
            dilated.copyTo(watershedResult);
        }
    }

    /**
     * GrabCut-inspired document detection approach based on Python implementation
     */
    private MatOfPoint detectDocumentWithGrabCutApproach(Mat inputImage, int frameWidth, int frameHeight) {
        Log.d(TAG, "🎯 Starting GrabCut-inspired document detection");
        
        Mat mask = null;
        Mat bgdModel = null;
        Mat fgdModel = null;
        Mat grabcutResult = null;
        Mat grabcutGray = null;
        Mat grabcutBin = null;
        Mat cleanedBin = null;
        Mat edges = null;
        Mat kernel = null;
        
        Mat rgbImage = null;
        
        try {
            // Convert to 3-channel RGB if needed for GrabCut
            if (inputImage.channels() == 1) {
                Log.d(TAG, "🔄 Converting grayscale to RGB for GrabCut");
                rgbImage = new Mat();
                Imgproc.cvtColor(inputImage, rgbImage, Imgproc.COLOR_GRAY2RGB);
            } else if (inputImage.channels() == 3) {
                rgbImage = inputImage.clone();
            } else {
                Log.w(TAG, "⚠️ Unsupported image format: " + inputImage.channels() + " channels");
                return detectDocumentWithSimpleThresholding(inputImage, frameWidth, frameHeight);
            }
            
            if (rgbImage.type() != CvType.CV_8UC3) {
                Log.d(TAG, "🔄 Converting to CV_8UC3 for GrabCut");
                Mat converted = new Mat();
                rgbImage.convertTo(converted, CvType.CV_8UC3);
                rgbImage.release();
                rgbImage = converted;
            }
            
            // 1. Initialize GrabCut parameters (equivalent to Python lines 119-124)
            mask = Mat.zeros(rgbImage.size(), CvType.CV_8UC1);
            bgdModel = Mat.zeros(1, 65, CvType.CV_64FC1);
            fgdModel = Mat.zeros(1, 65, CvType.CV_64FC1);
            
            // Define rectangle for GrabCut (10px margin like Python)
            int margin = Math.max(10, Math.min(rgbImage.width(), rgbImage.height()) / 50); // Smaller adaptive margin
            org.opencv.core.Rect rect = new org.opencv.core.Rect(
                margin, margin, 
                Math.max(1, rgbImage.width() - 2*margin), 
                Math.max(1, rgbImage.height() - 2*margin)
            );
            
            Log.d(TAG, String.format("🔧 GrabCut input: %dx%d, type=%d, channels=%d", 
                rgbImage.width(), rgbImage.height(), rgbImage.type(), rgbImage.channels()));
            Log.d(TAG, String.format("🔧 GrabCut rect: %dx%d at (%d,%d)", 
                rect.width, rect.height, rect.x, rect.y));
            
            // 2. Apply GrabCut algorithm with timeout (equivalent to Python line 124)
            long grabCutStart = System.currentTimeMillis();
            boolean grabCutSuccess = false;
            try {
                Log.d(TAG, "🚀 Starting GrabCut algorithm...");
                
                // Use only 1 iteration for maximum speed
                Imgproc.grabCut(rgbImage, mask, rect, bgdModel, fgdModel, 1, Imgproc.GC_INIT_WITH_RECT);
                
                long grabCutTime = System.currentTimeMillis() - grabCutStart;
                Log.d(TAG, String.format("✅ GrabCut completed in %dms", grabCutTime));
                
                // Check if it took too long and might be stuck
                if (grabCutTime > 5000) { // 5 seconds timeout
                    Log.w(TAG, "⚠️ GrabCut took too long (" + grabCutTime + "ms), using fallback");
                    return detectDocumentWithSimpleThresholding(inputImage, frameWidth, frameHeight);
                }
                
                grabCutSuccess = true;
            } catch (Exception e) {
                Log.e(TAG, "❌ GrabCut algorithm failed: " + e.getMessage());
                // Fallback: use simple thresholding instead of GrabCut
                return detectDocumentWithSimpleThresholding(inputImage, frameWidth, frameHeight);
            }
            
            // 3. Create foreground mask (equivalent to Python line 125)
            Mat grabcutMask = new Mat();
            try {
                // Create mask: foreground = GC_FGD (1) + GC_PR_FGD (3)
                Core.inRange(mask, new Scalar(1), new Scalar(1), grabcutMask);
                Mat probableFg = new Mat();
                Core.inRange(mask, new Scalar(3), new Scalar(3), probableFg);
                Core.bitwise_or(grabcutMask, probableFg, grabcutMask);
                probableFg.release();
                
                Log.d(TAG, "✅ GrabCut mask created successfully");
            } catch (Exception e) {
                Log.e(TAG, "❌ GrabCut mask creation failed: " + e.getMessage());
                grabcutMask.release();
                return detectDocumentWithSimpleThresholding(inputImage, frameWidth, frameHeight);
            }
            
            // 4. Apply mask to get segmented image (equivalent to Python line 126)
            grabcutResult = new Mat();
            try {
                rgbImage.copyTo(grabcutResult, grabcutMask);
                Log.d(TAG, "✅ Applied GrabCut mask successfully");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to apply GrabCut mask: " + e.getMessage());
                grabcutMask.release();
                return detectDocumentWithSimpleThresholding(inputImage, frameWidth, frameHeight);
            }
            grabcutMask.release();
            
            saveCroppedMat(grabcutResult, "15_grabcut_result");
            
            // 5. Convert to grayscale and threshold (equivalent to Python lines 132-133)
            grabcutGray = new Mat();
            Imgproc.cvtColor(grabcutResult, grabcutGray, Imgproc.COLOR_RGB2GRAY);
            
            grabcutBin = new Mat();
            Imgproc.threshold(grabcutGray, grabcutBin, 10, 255, Imgproc.THRESH_BINARY);
            saveCroppedMat(grabcutBin, "16_grabcut_binary");
            
            // 6. Morphological operations to clean up (equivalent to Python lines 136-138)
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            cleanedBin = new Mat();
            Imgproc.morphologyEx(grabcutBin, cleanedBin, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(cleanedBin, cleanedBin, Imgproc.MORPH_OPEN, kernel);
            saveCroppedMat(cleanedBin, "17_cleaned_binary");
            
            // 7. Edge detection (equivalent to Python line 140)
            edges = new Mat();
            Imgproc.Canny(cleanedBin, edges, 75, 200);
            saveCroppedMat(edges, "18_grabcut_edges");
            
            // 8. Find contours (equivalent to Python line 146)
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            
            Log.d(TAG, "🔍 GrabCut found " + contours.size() + " contours");
            hierarchy.release();
            
            if (contours.isEmpty()) {
                Log.w(TAG, "❌ No contours found after GrabCut");
                return null;
            }
            
            // 9. Process top 5 largest contours (equivalent to Python line 156)
            contours.sort((c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));
            int maxContours = Math.min(5, contours.size());
            
            double frameArea = frameWidth * frameHeight;
            double minArea = frameArea * 0.01; // 1% minimum area
            
            // 10. Find best document contour (equivalent to Python lines 158-238)
            for (int i = 0; i < maxContours; i++) {
                MatOfPoint contour = contours.get(i);
                double area = Imgproc.contourArea(contour);
                
                if (area < minArea) {
                    Log.d(TAG, String.format("⚠️ Contour %d too small: %.0f < %.0f", i, area, minArea));
                    continue;
                }
                
                Log.d(TAG, String.format("🔍 Processing contour %d: area=%.0f (%.1f%%)", 
                    i, area, (area/frameArea)*100));
                
                // Try to find 4-point contour first (equivalent to Python lines 160-197)
                MatOfPoint documentContour = tryFourPointContour(contour, expectedAspectRatio);
                if (documentContour != null) {
                    Log.d(TAG, "✅ Found 4-point document contour!");
                    return documentContour;
                }
                
                // Fallback to minAreaRect (equivalent to Python lines 210-237)
                MatOfPoint rectContour = tryMinAreaRectFallback(contour, expectedAspectRatio);
                if (rectContour != null) {
                    Log.d(TAG, "✅ Found document using minAreaRect fallback!");
                    return rectContour;
                }
            }
            
            Log.w(TAG, "❌ No suitable document contour found with GrabCut approach");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in GrabCut document detection", e);
            return null;
        } finally {
            // Clean up all Mat objects
            if (rgbImage != null) rgbImage.release();
            if (mask != null) mask.release();
            if (bgdModel != null) bgdModel.release();
            if (fgdModel != null) fgdModel.release();
            if (grabcutResult != null) grabcutResult.release();
            if (grabcutGray != null) grabcutGray.release();
            if (grabcutBin != null) grabcutBin.release();
            if (cleanedBin != null) cleanedBin.release();
            if (edges != null) edges.release();
            if (kernel != null) kernel.release();
        }
    }
    
    /**
     * Try to find a 4-point document contour using convex hull
     */
    private MatOfPoint tryFourPointContour(MatOfPoint contour, double expectedAspectRatio) {
        try {
            // Get convex hull (equivalent to Python line 160)
            MatOfInt hullIndices = new MatOfInt();
            Imgproc.convexHull(contour, hullIndices);
            
            // Convert hull indices to points
            Point[] contourPoints = contour.toArray();
            int[] hullIndicesArray = hullIndices.toArray();
            List<Point> hullPointsList = new ArrayList<>();
            
            for (int index : hullIndicesArray) {
                if (index < contourPoints.length) {
                    hullPointsList.add(contourPoints[index]);
                }
            }
            
            MatOfPoint hull = new MatOfPoint();
            hull.fromList(hullPointsList);
            
            // Approximate contour (equivalent to Python lines 167-169)
            MatOfPoint2f hull2f = new MatOfPoint2f();
            hull.convertTo(hull2f, CvType.CV_32FC2);
            
            double perimeter = Imgproc.arcLength(hull2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(hull2f, approx, 0.02 * perimeter, true);
            
            Point[] approxPoints = approx.toArray();
            Log.d(TAG, String.format("📐 Hull perimeter: %.1f, approx points: %d", perimeter, approxPoints.length));
            
            // Check if we have 4 points (equivalent to Python line 172)
            if (approxPoints.length == 4) {
                // Validate aspect ratio if provided (equivalent to Python lines 174-197)
                if (validateQuadrilateralAspectRatio(approxPoints, expectedAspectRatio)) {
                    MatOfPoint result = new MatOfPoint();
                    result.fromArray(approxPoints);
                    
                    // Clean up
                    hullIndices.release();
                    hull.release();
                    hull2f.release();
                    approx.release();
                    
                    return result;
                }
            }
            
            // Clean up
            hullIndices.release();
            hull.release();
            hull2f.release();
            approx.release();
            
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in 4-point contour detection", e);
            return null;
        }
    }
    
    /**
     * Fallback using minAreaRect for non-4-point contours
     */
    private MatOfPoint tryMinAreaRectFallback(MatOfPoint contour, double expectedAspectRatio) {
        try {
            // Get minimum area rectangle (equivalent to Python line 210)
            RotatedRect rotatedRect = Imgproc.minAreaRect(new MatOfPoint2f(contour.toArray()));
            Point[] rectPoints = new Point[4];
            rotatedRect.points(rectPoints);
            
            Log.d(TAG, String.format("📦 minAreaRect: center=(%.1f,%.1f), size=%.1fx%.1f, angle=%.1f", 
                rotatedRect.center.x, rotatedRect.center.y, 
                rotatedRect.size.width, rotatedRect.size.height, rotatedRect.angle));
            
            // Validate aspect ratio (equivalent to Python lines 220-237)
            if (validateQuadrilateralAspectRatio(rectPoints, expectedAspectRatio)) {
                MatOfPoint result = new MatOfPoint();
                result.fromArray(rectPoints);
                return result;
            }
            
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in minAreaRect fallback", e);
            return null;
        }
    }
    
    /**
     * Lightweight custom GrabCut-like algorithm for fast document segmentation
     * Uses simplified color-based segmentation instead of full GMM
     */
    private MatOfPoint detectDocumentWithLightweightGrabCut(Mat inputImage, int frameWidth, int frameHeight) {
        Log.d(TAG, "⚡ Starting lightweight GrabCut-like segmentation");
        long startTime = System.currentTimeMillis();
        
        Mat gray = null;
        Mat blurred = null;
        Mat foregroundMask = null;
        Mat backgroundMask = null;
        Mat combinedMask = null;
        Mat edges = null;
        
        try {
            // 1. Convert to grayscale for analysis
            gray = new Mat();
            if (inputImage.channels() == 3) {
                Imgproc.cvtColor(inputImage, gray, Imgproc.COLOR_RGB2GRAY);
            } else {
                gray = inputImage.clone();
            }
            
            // 2. Light blur to reduce noise
            blurred = new Mat();
            Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);
            
            // 3. Fast foreground/background separation using color analysis
            foregroundMask = new Mat();
            backgroundMask = new Mat();
            performFastColorSegmentation(inputImage, foregroundMask, backgroundMask);
            
            // 4. Combine masks using simple logic instead of complex graph cuts
            combinedMask = new Mat();
            performSimplifiedGraphCut(inputImage, foregroundMask, backgroundMask, combinedMask);
            
            // 5. Clean up the mask with light morphological operations
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Mat cleanedMask = new Mat();
            Imgproc.morphologyEx(combinedMask, cleanedMask, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(cleanedMask, cleanedMask, Imgproc.MORPH_OPEN, kernel);
            kernel.release();
            
            // 6. Find edges of the segmented region
            edges = new Mat();
            Imgproc.Canny(cleanedMask, edges, 50, 150);
            
            // Save debug images
            saveCroppedMat(gray, "60_lightweight_gray");
            saveCroppedMat(foregroundMask, "61_lightweight_fg_mask");
            saveCroppedMat(backgroundMask, "62_lightweight_bg_mask");
            saveCroppedMat(cleanedMask, "63_lightweight_combined");
            saveCroppedMat(edges, "64_lightweight_edges");
            
            // 7. Find contours from the segmented edges
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();
            
            long segmentationTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, String.format("⚡ Lightweight GrabCut completed in %dms, found %d contours", 
                segmentationTime, contours.size()));
            
            if (!contours.isEmpty()) {
                // Sort by area (largest first)
                contours.sort((c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));
                
                double frameArea = frameWidth * frameHeight;
                double minArea = frameArea * 0.08; // 8% minimum area
                double maxArea = frameArea * 0.85; // 85% maximum area
                
                // Process top 3 largest contours
                for (int i = 0; i < Math.min(3, contours.size()); i++) {
                    MatOfPoint contour = contours.get(i);
                    double area = Imgproc.contourArea(contour);
                    
                    Log.d(TAG, String.format("⚡ Lightweight contour %d: area=%.0f (%.2f%% of frame)", 
                        i + 1, area, (area / frameArea) * 100));
                    
                    if (area >= minArea && area <= maxArea) {
                        // Try to approximate to a 4-point polygon
                        MatOfPoint2f contour2f = new MatOfPoint2f();
                        contour.convertTo(contour2f, CvType.CV_32FC2);
                        
                        double epsilon = 0.02 * Imgproc.arcLength(contour2f, true);
                        MatOfPoint2f approx2f = new MatOfPoint2f();
                        Imgproc.approxPolyDP(contour2f, approx2f, epsilon, true);
                        
                        MatOfPoint approx = new MatOfPoint();
                        approx2f.convertTo(approx, CvType.CV_32S);
                        
                        Point[] points = approx.toArray();
                        
                        contour2f.release();
                        approx2f.release();
                        
                        if (points.length == 4) {
                            Log.d(TAG, String.format("✅ Found 4-point document with lightweight GrabCut (area=%.0f)", area));
                            cleanedMask.release();
                            return approx;
                        } else {
                            Log.d(TAG, String.format("🔧 %d-point contour, trying convex hull", points.length));
                            
                            // Release approx since we won't use it
                            approx.release();
                            
                            // Use convex hull and minimum area rectangle
                            MatOfInt hull = new MatOfInt();
                            Imgproc.convexHull(contour, hull);
                            
                            Point[] contourPoints = contour.toArray();
                            int[] hullIndices = hull.toArray();
                            Point[] hullPoints = new Point[hullIndices.length];
                            for (int j = 0; j < hullIndices.length; j++) {
                                hullPoints[j] = contourPoints[hullIndices[j]];
                            }
                            hull.release();
                            
                            MatOfPoint2f hullPoints2f = new MatOfPoint2f(hullPoints);
                            RotatedRect minRect = Imgproc.minAreaRect(hullPoints2f);
                            hullPoints2f.release();
                            
                            Point[] rectPoints = new Point[4];
                            minRect.points(rectPoints);
                            
                            MatOfPoint rectContour = new MatOfPoint(rectPoints);
                            Log.d(TAG, String.format("✅ Using minimum area rectangle (area=%.0f)", 
                                Imgproc.contourArea(rectContour)));
                            
                            cleanedMask.release();
                            return rectContour;
                        }
                    }
                }
                
                Log.w(TAG, "❌ No suitable document contour found with lightweight GrabCut");
            } else {
                Log.w(TAG, "❌ No contours found with lightweight GrabCut");
            }
            
            cleanedMask.release();
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Lightweight GrabCut failed: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            if (gray != null) gray.release();
            if (blurred != null) blurred.release();
            if (foregroundMask != null) foregroundMask.release();
            if (backgroundMask != null) backgroundMask.release();
            if (combinedMask != null) combinedMask.release();
            if (edges != null) edges.release();
        }
        
        return null;
    }

    /**
     * Fast color-based foreground/background segmentation
     * Replaces complex GMM with simple color analysis
     */
    private void performFastColorSegmentation(Mat inputImage, Mat foregroundMask, Mat backgroundMask) {
        Log.d(TAG, "⚡ Performing fast color segmentation");
        
        // Initialize masks
        foregroundMask.create(inputImage.size(), CvType.CV_8UC1);
        backgroundMask.create(inputImage.size(), CvType.CV_8UC1);
        foregroundMask.setTo(new Scalar(0));
        backgroundMask.setTo(new Scalar(0));
        
        // Define central region as likely foreground (document)
        int margin = Math.min(inputImage.width(), inputImage.height()) / 8;
        org.opencv.core.Rect centerRect = new org.opencv.core.Rect(
            margin, margin,
            inputImage.width() - 2 * margin,
            inputImage.height() - 2 * margin
        );
        
        // Sample colors from center (foreground) and edges (background)
        Scalar centerColor = Core.mean(new Mat(inputImage, centerRect));
        
        // Sample background from image edges
        List<org.opencv.core.Rect> edgeRegions = Arrays.asList(
            new org.opencv.core.Rect(0, 0, inputImage.width(), margin), // top
            new org.opencv.core.Rect(0, inputImage.height() - margin, inputImage.width(), margin), // bottom
            new org.opencv.core.Rect(0, 0, margin, inputImage.height()), // left
            new org.opencv.core.Rect(inputImage.width() - margin, 0, margin, inputImage.height()) // right
        );
        
        Scalar avgBackgroundColor = new Scalar(0, 0, 0);
        int validRegions = 0;
        
        for (org.opencv.core.Rect region : edgeRegions) {
            if (region.width > 0 && region.height > 0) {
                Scalar regionColor = Core.mean(new Mat(inputImage, region));
                avgBackgroundColor.val[0] += regionColor.val[0];
                avgBackgroundColor.val[1] += regionColor.val[1];
                avgBackgroundColor.val[2] += regionColor.val[2];
                validRegions++;
            }
        }
        
        if (validRegions > 0) {
            avgBackgroundColor.val[0] /= validRegions;
            avgBackgroundColor.val[1] /= validRegions;
            avgBackgroundColor.val[2] /= validRegions;
        }
        
        Log.d(TAG, String.format("⚡ Center color: (%.0f,%.0f,%.0f), Background: (%.0f,%.0f,%.0f)",
            centerColor.val[0], centerColor.val[1], centerColor.val[2],
            avgBackgroundColor.val[0], avgBackgroundColor.val[1], avgBackgroundColor.val[2]));
        
        // Create masks based on color similarity
        double colorThreshold = 40.0; // Adjust based on sensitivity needed
        
        for (int y = 0; y < inputImage.height(); y++) {
            for (int x = 0; x < inputImage.width(); x++) {
                double[] pixel = inputImage.get(y, x);
                
                // Calculate distance to center color (foreground)
                double fgDistance = Math.sqrt(
                    Math.pow(pixel[0] - centerColor.val[0], 2) +
                    Math.pow(pixel[1] - centerColor.val[1], 2) +
                    Math.pow(pixel[2] - centerColor.val[2], 2)
                );
                
                // Calculate distance to background color
                double bgDistance = Math.sqrt(
                    Math.pow(pixel[0] - avgBackgroundColor.val[0], 2) +
                    Math.pow(pixel[1] - avgBackgroundColor.val[1], 2) +
                    Math.pow(pixel[2] - avgBackgroundColor.val[2], 2)
                );
                
                // Assign to foreground or background based on closer color
                if (fgDistance < bgDistance && fgDistance < colorThreshold) {
                    foregroundMask.put(y, x, 255);
                } else if (bgDistance < colorThreshold) {
                    backgroundMask.put(y, x, 255);
                }
                // Pixels that don't match either remain uncertain (0 in both masks)
            }
        }
        
        Log.d(TAG, "⚡ Fast color segmentation completed");
    }
    
    /**
     * Simplified graph cut using flood fill instead of complex energy minimization
     * Much faster than full graph cut algorithms
     */
    private void performSimplifiedGraphCut(Mat inputImage, Mat foregroundMask, Mat backgroundMask, Mat outputMask) {
        Log.d(TAG, "⚡ Performing simplified graph cut");
        
        // Initialize output mask
        outputMask.create(inputImage.size(), CvType.CV_8UC1);
        outputMask.setTo(new Scalar(0));
        
        // Start with definite foreground pixels
        Mat definitelyForeground = new Mat();
        foregroundMask.copyTo(definitelyForeground);
        
        // Use flood fill to expand foreground regions based on color similarity
        Mat visited = Mat.zeros(inputImage.size(), CvType.CV_8UC1);
        
        // Find all foreground seed points
        for (int y = 0; y < foregroundMask.height(); y++) {
            for (int x = 0; x < foregroundMask.width(); x++) {
                double[] fgValue = foregroundMask.get(y, x);
                double[] visitedValue = visited.get(y, x);
                
                if (fgValue[0] > 0 && visitedValue[0] == 0) {
                    // This is a foreground seed, do flood fill
                    floodFillForeground(inputImage, outputMask, visited, new Point(x, y), 30.0);
                }
            }
        }
        
        // Apply definite background constraints
        for (int y = 0; y < backgroundMask.height(); y++) {
            for (int x = 0; x < backgroundMask.width(); x++) {
                double[] bgValue = backgroundMask.get(y, x);
                if (bgValue[0] > 0) {
                    outputMask.put(y, x, 0); // Force to background
                }
            }
        }
        
        definitelyForeground.release();
        visited.release();
        
        Log.d(TAG, "⚡ Simplified graph cut completed");
    }
    
    /**
     * Flood fill helper for foreground expansion using iterative approach
     */
    private void floodFillForeground(Mat image, Mat mask, Mat visited, Point seed, double threshold) {
        java.util.Stack<Point> stack = new java.util.Stack<>();
        stack.push(seed);
        
        while (!stack.isEmpty()) {
            Point current = stack.pop();
            
            if (current.x < 0 || current.x >= image.width() || current.y < 0 || current.y >= image.height()) {
                continue;
            }
            
            double[] visitedValue = visited.get((int)current.y, (int)current.x);
            if (visitedValue[0] > 0) {
                continue; // Already visited
            }
            
            visited.put((int)current.y, (int)current.x, 255);
            mask.put((int)current.y, (int)current.x, 255);
            
            double[] currentColor = image.get((int)current.y, (int)current.x);
            
            // Check 4-connected neighbors
            Point[] neighbors = {
                new Point(current.x + 1, current.y),
                new Point(current.x - 1, current.y),
                new Point(current.x, current.y + 1),
                new Point(current.x, current.y - 1)
            };
            
            for (Point neighbor : neighbors) {
                if (neighbor.x >= 0 && neighbor.x < image.width() && 
                    neighbor.y >= 0 && neighbor.y < image.height()) {
                    
                    double[] neighborVisited = visited.get((int)neighbor.y, (int)neighbor.x);
                    if (neighborVisited[0] == 0) { // Not visited
                        
                        double[] neighborColor = image.get((int)neighbor.y, (int)neighbor.x);
                        double colorDistance = Math.sqrt(
                            Math.pow(currentColor[0] - neighborColor[0], 2) +
                            Math.pow(currentColor[1] - neighborColor[1], 2) +
                            Math.pow(currentColor[2] - neighborColor[2], 2)
                        );
                        
                        if (colorDistance < threshold) {
                            stack.push(neighbor);
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper class to represent a line detected by Hough transform
     */
    private static class Line {
        public final double rho;
        public final double theta;
        public final double angleDegrees;
        
        public Line(double rho, double theta, double angleDegrees) {
            this.rho = rho;
            this.theta = theta;
            this.angleDegrees = angleDegrees;
        }
    }
    
    /**
     * Select best lines for document edges by clustering and removing outliers
     */
    private List<Line> selectBestLines(List<Line> lines, int frameSize, boolean isHorizontal) {
        if (lines.size() < 2) return lines;
        
        // Sort lines by rho (distance from origin)
        lines.sort((a, b) -> Double.compare(Math.abs(a.rho), Math.abs(b.rho)));
        
        List<Line> selectedLines = new ArrayList<>();
        
        // For document edges, we typically want the two most extreme lines
        // (one near each edge of the document)
        if (lines.size() >= 2) {
            // Take lines that are reasonably far apart
            Line firstLine = lines.get(0);
            selectedLines.add(firstLine);
            
            // Find a line that's sufficiently far from the first one
            for (int i = 1; i < lines.size(); i++) {
                Line candidate = lines.get(i);
                double distance = Math.abs(candidate.rho - firstLine.rho);
                
                // Minimum distance threshold (adjust based on frame size)
                double minDistance = frameSize * 0.2; // 20% of frame size
                
                if (distance > minDistance) {
                    selectedLines.add(candidate);
                    break;
                }
            }
        }
        
        Log.d(TAG, String.format("📏 Selected %d %s lines from %d candidates", 
            selectedLines.size(), isHorizontal ? "horizontal" : "vertical", lines.size()));
        
        return selectedLines;
    }
    
    /**
     * Find intersections between horizontal and vertical lines
     */
    private List<Point> findLineIntersections(List<Line> horizontalLines, List<Line> verticalLines) {
        List<Point> intersections = new ArrayList<>();
        
        for (Line hLine : horizontalLines) {
            for (Line vLine : verticalLines) {
                Point intersection = findLineIntersection(hLine, vLine);
                if (intersection != null) {
                    intersections.add(intersection);
                }
            }
        }
        
        Log.d(TAG, String.format("📏 Found %d line intersections", intersections.size()));
        return intersections;
    }
    
    /**
     * Find intersection point of two lines in Hough space (rho, theta)
     */
    private Point findLineIntersection(Line line1, Line line2) {
        double rho1 = line1.rho, theta1 = line1.theta;
        double rho2 = line2.rho, theta2 = line2.theta;
        
        double cos1 = Math.cos(theta1), sin1 = Math.sin(theta1);
        double cos2 = Math.cos(theta2), sin2 = Math.sin(theta2);
        
        double denominator = cos1 * sin2 - cos2 * sin1;
        
        if (Math.abs(denominator) < 0.001) {
            // Lines are parallel
            return null;
        }
        
        double x = (rho1 * sin2 - rho2 * sin1) / denominator;
        double y = (rho2 * cos1 - rho1 * cos2) / denominator;
        
        return new Point(x, y);
    }
    
    /**
     * Order rectangle corners in consistent order: top-left, top-right, bottom-right, bottom-left
     */
    private Point[] orderRectangleCorners(List<Point> corners) {
        if (corners.size() != 4) return null;
        
        Point[] ordered = new Point[4];
        
        // Sort by y coordinate to get top and bottom pairs
        corners.sort((a, b) -> Double.compare(a.y, b.y));
        
        // Top two points
        Point[] topPoints = { corners.get(0), corners.get(1) };
        Point[] bottomPoints = { corners.get(2), corners.get(3) };
        
        // Sort top points by x coordinate (left to right)
        if (topPoints[0].x > topPoints[1].x) {
            Point temp = topPoints[0];
            topPoints[0] = topPoints[1];
            topPoints[1] = temp;
        }
        
        // Sort bottom points by x coordinate (left to right)
        if (bottomPoints[0].x > bottomPoints[1].x) {
            Point temp = bottomPoints[0];
            bottomPoints[0] = bottomPoints[1];
            bottomPoints[1] = temp;
        }
        
        // Assign in order: top-left, top-right, bottom-right, bottom-left
        ordered[0] = topPoints[0];  // top-left
        ordered[1] = topPoints[1];  // top-right
        ordered[2] = bottomPoints[1]; // bottom-right
        ordered[3] = bottomPoints[0]; // bottom-left
        
        return ordered;
    }
    
    /**
     * Validate that the detected rectangle is reasonable for a document
     */
    private boolean validateDocumentRectangle(Point[] corners, int frameWidth, int frameHeight) {
        if (corners == null || corners.length != 4) return false;
        
        // Check if corners are within frame bounds
        for (Point corner : corners) {
            if (corner.x < 0 || corner.x > frameWidth || corner.y < 0 || corner.y > frameHeight) {
                Log.w(TAG, String.format("📏 Corner out of bounds: (%.1f, %.1f)", corner.x, corner.y));
                return false;
            }
        }
        
        // Calculate area
        double area = Imgproc.contourArea(new MatOfPoint(corners));
        double frameArea = frameWidth * frameHeight;
        double areaRatio = area / frameArea;
        
        if (areaRatio < 0.1 || areaRatio > 0.9) {
            Log.w(TAG, String.format("📏 Invalid area ratio: %.3f", areaRatio));
            return false;
        }
        
        // Check aspect ratio (should be reasonable for documents)
        double width = Math.max(
            distance(corners[0], corners[1]),
            distance(corners[2], corners[3])
        );
        double height = Math.max(
            distance(corners[1], corners[2]),
            distance(corners[3], corners[0])
        );
        
        double aspectRatio = width / height;
        if (aspectRatio < 0.5 || aspectRatio > 3.0) {
            Log.w(TAG, String.format("📏 Invalid aspect ratio: %.3f", aspectRatio));
            return false;
        }
        
        Log.d(TAG, String.format("📏 Valid rectangle: area=%.0f (%.1f%%), aspect=%.2f", 
            area, areaRatio * 100, aspectRatio));
        
        return true;
    }
    
    /**
     * Calculate distance between two points
     */
    private double distance(Point p1, Point p2) {
        double dx = p1.x - p2.x;
        double dy = p1.y - p2.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Visualize Hough line detection for debugging
     */
    private void visualizeHoughDetection(Mat image, List<Line> horizontalLines, 
                                       List<Line> verticalLines, Point[] corners) {
        Scalar lineColor = new Scalar(0, 255, 0); // Green for lines
        Scalar cornerColor = new Scalar(255, 0, 0); // Red for corners
        
        // Draw horizontal lines
        for (Line line : horizontalLines) {
            drawHoughLine(image, line, lineColor);
        }
        
        // Draw vertical lines  
        for (Line line : verticalLines) {
            drawHoughLine(image, line, lineColor);
        }
        
        // Draw corners
        for (Point corner : corners) {
            Imgproc.circle(image, corner, 8, cornerColor, -1);
        }
        
        // Draw rectangle outline
        for (int i = 0; i < 4; i++) {
            Point start = corners[i];
            Point end = corners[(i + 1) % 4];
            Imgproc.line(image, start, end, new Scalar(255, 255, 0), 3);
        }
    }
    
    /**
     * Draw a Hough line on the image
     */
    private void drawHoughLine(Mat image, Line line, Scalar color) {
        double rho = line.rho;
        double theta = line.theta;
        
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        
        double x0 = cos * rho;
        double y0 = sin * rho;
        
        int len = Math.max(image.width(), image.height());
        
        Point pt1 = new Point(x0 - len * sin, y0 + len * cos);
        Point pt2 = new Point(x0 + len * sin, y0 - len * cos);
        
        Imgproc.line(image, pt1, pt2, color, 2);
    }

    /**
     * Fast adaptive background/foreground segmentation for real-time document detection
     * Alternative to slow GrabCut algorithm
     */
    private MatOfPoint detectDocumentWithFastSegmentation(Mat inputImage, int frameWidth, int frameHeight) {
        Log.d(TAG, "🚀 Starting fast adaptive segmentation");
        long startTime = System.currentTimeMillis();
        
        Mat gray = null;
        Mat blurred = null;
        Mat adaptiveThresh = null;
        Mat morphCleaned = null;
        Mat edges = null;
        Mat kernel = null;
        
        try {
            // 1. Convert to grayscale
            gray = new Mat();
            if (inputImage.channels() == 3) {
                Imgproc.cvtColor(inputImage, gray, Imgproc.COLOR_RGB2GRAY);
            } else {
                gray = inputImage.clone();
            }
            
            // 2. Gaussian blur to reduce noise and smooth the image
            blurred = new Mat();
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            
            // 3. Adaptive thresholding for varying lighting conditions
            // This creates a binary mask separating foreground (document) from background
            adaptiveThresh = new Mat();
            Imgproc.adaptiveThreshold(blurred, adaptiveThresh, 255, 
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10);
            
            // 4. Morphological operations to clean up the binary mask
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            morphCleaned = new Mat();
            
            // Close operation to fill small gaps in the document
            Imgproc.morphologyEx(adaptiveThresh, morphCleaned, Imgproc.MORPH_CLOSE, kernel);
            
            // Open operation to remove small noise
            Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.morphologyEx(morphCleaned, morphCleaned, Imgproc.MORPH_OPEN, openKernel);
            openKernel.release();
            
            // 5. Edge detection on the cleaned binary image
            edges = new Mat();
            Imgproc.Canny(morphCleaned, edges, 50, 150);
            
            // Save debug images
            saveCroppedMat(gray, "30_fast_gray");
            saveCroppedMat(blurred, "31_fast_blurred");
            saveCroppedMat(adaptiveThresh, "32_fast_adaptive");
            saveCroppedMat(morphCleaned, "33_fast_cleaned");
            saveCroppedMat(edges, "34_fast_edges");
            
            // 6. Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();
            
            long segmentationTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, String.format("✅ Fast segmentation completed in %dms, found %d contours", 
                segmentationTime, contours.size()));
            
            if (!contours.isEmpty()) {
                // Sort by area (largest first)
                contours.sort((c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));
                
                double frameArea = frameWidth * frameHeight;
                double minArea = frameArea * 0.08; // 8% minimum area
                double maxArea = frameArea * 0.8;  // 80% maximum area
                
                // Process top 3 largest contours
                for (int i = 0; i < Math.min(3, contours.size()); i++) {
                    MatOfPoint contour = contours.get(i);
                    double area = Imgproc.contourArea(contour);
                    
                    Log.d(TAG, String.format("📐 Contour %d: area=%.0f (%.2f%% of frame)", 
                        i + 1, area, (area / frameArea) * 100));
                    
                    if (area >= minArea && area <= maxArea) {
                        // Try to approximate to a 4-point polygon
                        MatOfPoint2f contour2f = new MatOfPoint2f();
                        contour.convertTo(contour2f, CvType.CV_32FC2);
                        
                        double epsilon = 0.02 * Imgproc.arcLength(contour2f, true);
                        MatOfPoint2f approx2f = new MatOfPoint2f();
                        Imgproc.approxPolyDP(contour2f, approx2f, epsilon, true);
                        
                        MatOfPoint approx = new MatOfPoint();
                        approx2f.convertTo(approx, CvType.CV_32S);
                        
                        Point[] points = approx.toArray();
                        
                        contour2f.release();
                        approx2f.release();
                        
                        if (points.length == 4) {
                            Log.d(TAG, String.format("✅ Found 4-point document contour (area=%.0f)", area));
                            return approx;
                        } else {
                            Log.d(TAG, String.format("⚠️ Contour has %d points, trying convex hull", points.length));
                            
                            // Release approx since we won't use it
                            approx.release();
                            
                            // Fallback: use convex hull and minimum area rectangle
                            MatOfInt hull = new MatOfInt();
                            Imgproc.convexHull(contour, hull);
                            
                            Point[] contourPoints = contour.toArray();
                            int[] hullIndices = hull.toArray();
                            Point[] hullPoints = new Point[hullIndices.length];
                            for (int j = 0; j < hullIndices.length; j++) {
                                hullPoints[j] = contourPoints[hullIndices[j]];
                            }
                            hull.release();
                            
                            MatOfPoint2f hullPoints2f = new MatOfPoint2f(hullPoints);
                            RotatedRect minRect = Imgproc.minAreaRect(hullPoints2f);
                            hullPoints2f.release();
                            
                            Point[] rectPoints = new Point[4];
                            minRect.points(rectPoints);
                            
                            MatOfPoint rectContour = new MatOfPoint(rectPoints);
                            Log.d(TAG, String.format("✅ Using minimum area rectangle fallback (area=%.0f)", 
                                Imgproc.contourArea(rectContour)));
                            
                            return rectContour;
                        }
                    }
                }
                
                Log.w(TAG, "❌ No suitable document contour found in fast segmentation");
            } else {
                Log.w(TAG, "❌ No contours found in fast segmentation");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Fast segmentation failed: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            if (gray != null) gray.release();
            if (blurred != null) blurred.release();
            if (adaptiveThresh != null) adaptiveThresh.release();
            if (morphCleaned != null) morphCleaned.release();
            if (edges != null) edges.release();
            if (kernel != null) kernel.release();
        }
        
        return null;
    }

    /**
     * Simple thresholding fallback when GrabCut fails
     */
    private MatOfPoint detectDocumentWithSimpleThresholding(Mat inputImage, int frameWidth, int frameHeight) {
        Log.d(TAG, "🔄 Using simple thresholding fallback");
        
        Mat gray = null;
        Mat thresh = null;
        Mat edges = null;
        Mat kernel = null;
        
        try {
            // Convert to grayscale
            gray = new Mat();
            if (inputImage.channels() == 3) {
                Imgproc.cvtColor(inputImage, gray, Imgproc.COLOR_RGB2GRAY);
            } else {
                gray = inputImage.clone();
            }
            
            // Apply adaptive threshold
            thresh = new Mat();
            Imgproc.adaptiveThreshold(gray, thresh, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, 
                Imgproc.THRESH_BINARY, 11, 2);
            
            // Morphological operations
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Mat cleaned = new Mat();
            Imgproc.morphologyEx(thresh, cleaned, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(cleaned, cleaned, Imgproc.MORPH_OPEN, kernel);
            
            // Edge detection
            edges = new Mat();
            Imgproc.Canny(cleaned, edges, 50, 150);
            
            saveCroppedMat(thresh, "19_fallback_thresh");
            saveCroppedMat(cleaned, "20_fallback_cleaned");
            saveCroppedMat(edges, "21_fallback_edges");
            
            // Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            
            Log.d(TAG, "🔍 Fallback found " + contours.size() + " contours");
            
            if (!contours.isEmpty()) {
                // Sort by area and take the largest
                contours.sort((c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));
                
                double frameArea = frameWidth * frameHeight;
                double minArea = frameArea * 0.05; // 5% minimum
                
                for (MatOfPoint contour : contours) {
                    double area = Imgproc.contourArea(contour);
                    if (area > minArea) {
                        // Try to get a 4-point approximation
                        MatOfPoint2f contour2f = new MatOfPoint2f();
                        contour.convertTo(contour2f, CvType.CV_32FC2);
                        
                        MatOfPoint2f approx = new MatOfPoint2f();
                        double epsilon = 0.02 * Imgproc.arcLength(contour2f, true);
                        Imgproc.approxPolyDP(contour2f, approx, epsilon, true);
                        
                        Point[] points = approx.toArray();
                        if (points.length >= 3 && points.length <= 8) {
                            MatOfPoint result = new MatOfPoint();
                            result.fromArray(points);
                            
                            contour2f.release();
                            approx.release();
                            cleaned.release();
                            hierarchy.release();
                            
                            Log.d(TAG, "✅ Fallback found valid contour");
                            return result;
                        }
                        
                        contour2f.release();
                        approx.release();
                    }
                }
            }
            
            cleaned.release();
            hierarchy.release();
            
            Log.w(TAG, "❌ Fallback method failed");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in simple thresholding fallback", e);
            return null;
        } finally {
            if (gray != null) gray.release();
            if (thresh != null) thresh.release();
            if (edges != null) edges.release();
            if (kernel != null) kernel.release();
        }
    }

    /**
     * Validate quadrilateral aspect ratio
     */
    private boolean validateQuadrilateralAspectRatio(Point[] quad, double expectedAspectRatio) {
        if (expectedAspectRatio <= 0) {
            return true; // No validation needed
        }
        
        try {
            // Calculate average width and height
            Point[] orderedQuad = orderPoints(quad);
            
            double w1 = Math.hypot(orderedQuad[1].x - orderedQuad[0].x, orderedQuad[1].y - orderedQuad[0].y);
            double w2 = Math.hypot(orderedQuad[2].x - orderedQuad[3].x, orderedQuad[2].y - orderedQuad[3].y);
            double h1 = Math.hypot(orderedQuad[3].x - orderedQuad[0].x, orderedQuad[3].y - orderedQuad[0].y);
            double h2 = Math.hypot(orderedQuad[2].x - orderedQuad[1].x, orderedQuad[2].y - orderedQuad[1].y);
            
            double avgWidth = (w1 + w2) / 2.0;
            double avgHeight = (h1 + h2) / 2.0;
            
            if (avgHeight == 0) return false;
            
            double detectedAspectRatio = avgWidth / avgHeight;
            double tolerance = 0.15; // 15% tolerance like Python
            
            boolean isValid = Math.abs(detectedAspectRatio - expectedAspectRatio) <= tolerance;
            
            Log.d(TAG, String.format("📏 Aspect ratio check: detected=%.3f, expected=%.3f, tolerance=%.3f, valid=%s",
                detectedAspectRatio, expectedAspectRatio, tolerance, isValid));
            
            return isValid;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating aspect ratio", e);
            return false;
        }
    }

    /**
     * Send contours for real-time visualization
     */
    private void sendContoursForVisualization(MatOfPoint bestContour, List<MatOfPoint> validContours, 
            int originalFrameWidth, int originalFrameHeight, double ratio, int cropOffsetX, int cropOffsetY) {
        
        try {
            List<Point> bestContourPoints = null;
            List<List<Point>> allValidContourPoints = new ArrayList<>();
            
            // Transform best contour to original frame coordinates
            if (bestContour != null) {
                Point[] points = bestContour.toArray();
                bestContourPoints = new ArrayList<>();
                for (Point p : points) {
                    Point originalPoint = new Point(
                        (p.x / ratio) + cropOffsetX,
                        (p.y / ratio) + cropOffsetY
                    );
                    bestContourPoints.add(originalPoint);
                }
            }
            
            // Transform all valid contours to original frame coordinates (limit to top 5 for performance)
            int maxContours = Math.min(5, validContours.size());
            for (int i = 0; i < maxContours; i++) {
                MatOfPoint contour = validContours.get(i);
                Point[] points = contour.toArray();
                List<Point> contourPoints = new ArrayList<>();
                
                // Simplify contour to reduce points (every 5th point for performance)
                for (int j = 0; j < points.length; j += 5) {
                    Point originalPoint = new Point(
                        (points[j].x / ratio) + cropOffsetX,
                        (points[j].y / ratio) + cropOffsetY
                    );
                    contourPoints.add(originalPoint);
                }
                
                if (!contourPoints.isEmpty()) {
                    allValidContourPoints.add(contourPoints);
                }
            }
            
            // Send to React Native for visualization
            if (frameListener != null) {
                frameListener.onDocumentContoursDetected(bestContourPoints, allValidContourPoints, 
                    originalFrameWidth, originalFrameHeight);
            }
            
            Log.d(TAG, String.format("📤 Sent contours for visualization: best=%s, valid=%d", 
                bestContour != null ? "yes" : "no", allValidContourPoints.size()));
                
        } catch (Exception e) {
            Log.e(TAG, "Error sending contours for visualization", e);
        }
    }
    
    /**  
     * Filter contours to find potential document candidates
     */
    private List<MatOfPoint> filterValidDocumentContours(List<MatOfPoint> contours, int frameWidth, int frameHeight) {
        Log.d(TAG, "📋 Filtering " + contours.size() + " contours for document candidates");
        
        List<MatOfPoint> validContours = new ArrayList<>();
        double frameArea = frameWidth * frameHeight;
        
        // More lenient filtering to catch more potential documents
        double minArea = frameArea * 0.05; // Reduced from 0.15 to 0.05 (5% of frame)
        double maxArea = frameArea * 0.90; // Allow up to 90% of frame
        
        Log.d(TAG, String.format("📊 Filter criteria: area %.0f-%.0f (%.1f%%-%.1f%%)", 
            minArea, maxArea, (minArea/frameArea)*100, (maxArea/frameArea)*100));

        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint contour = contours.get(i);
            
            try {
                double area = Imgproc.contourArea(contour);
                
                // Basic area filter
                if (area < minArea || area > maxArea) {
                    continue;
                }
                
                // Basic perimeter filter
                double perimeter = Imgproc.arcLength(new MatOfPoint2f(contour.toArray()), true);
                if (perimeter < 100) { // Minimum perimeter
                    continue;
                }
                
                // Basic rectangularity check
                org.opencv.core.Rect boundingRect = Imgproc.boundingRect(contour);
                double rectangularity = area / (boundingRect.width * boundingRect.height);
                
                if (rectangularity < 0.3) { // Very lenient rectangularity
                    continue;
                }
                
                // Check if contour can be approximated to a reasonable polygon
                MatOfPoint2f contour2f = new MatOfPoint2f();
                contour.convertTo(contour2f, CvType.CV_32FC2);
                
                MatOfPoint2f approx = new MatOfPoint2f();
                double epsilon = 0.05 * Imgproc.arcLength(contour2f, true); // More lenient approximation
                Imgproc.approxPolyDP(contour2f, approx, epsilon, true);
                
                Point[] approxPoints = approx.toArray();
                
                // Accept polygons with 3-8 vertices (documents can appear as triangles due to perspective)
                if (approxPoints.length >= 3 && approxPoints.length <= 8) {
                    validContours.add(contour);
                    Log.d(TAG, String.format("✅ Valid contour %d: area=%.0f(%.1f%%), perim=%.0f, rect=%.3f, vertices=%d", 
                        validContours.size(), area, (area/frameArea)*100, perimeter, rectangularity, approxPoints.length));
                }
                
                contour2f.release();
                approx.release();
                
            } catch (Exception e) {
                Log.w(TAG, "Error filtering contour " + i, e);
            }
        }
        
        Log.d(TAG, String.format("📋 Filtered to %d valid document candidates", validContours.size()));
        return validContours;
    }
    
    /**
     * Select the best document contour from valid candidates
     */
    private MatOfPoint selectBestDocumentContour(List<MatOfPoint> validContours, int frameWidth, int frameHeight) {
        if (validContours.isEmpty()) {
            return null;
        }
        
        Log.d(TAG, "🎯 Selecting best contour from " + validContours.size() + " candidates");
        
        MatOfPoint bestContour = null;
        double maxScore = 0;
        double frameArea = frameWidth * frameHeight;
        
        for (int i = 0; i < validContours.size(); i++) {
            MatOfPoint contour = validContours.get(i);
            
            try {
                double area = Imgproc.contourArea(contour);
                double perimeter = Imgproc.arcLength(new MatOfPoint2f(contour.toArray()), true);
                
                // Calculate position score (prefer center)
                org.opencv.core.Rect boundingRect = Imgproc.boundingRect(contour);
                double centerX = boundingRect.x + boundingRect.width / 2.0;
                double centerY = boundingRect.y + boundingRect.height / 2.0;
                double distFromCenter = Math.sqrt(Math.pow(centerX - frameWidth/2.0, 2) + Math.pow(centerY - frameHeight/2.0, 2));
                double maxDistFromCenter = Math.sqrt(Math.pow(frameWidth/2.0, 2) + Math.pow(frameHeight/2.0, 2));
                double positionScore = 1.0 - (distFromCenter / maxDistFromCenter);
                
                // Calculate solidity
                MatOfInt hull = new MatOfInt();
                Imgproc.convexHull(contour, hull);
                
                MatOfPoint hullPoints = new MatOfPoint();
                Point[] contourArray = contour.toArray();
                List<Point> hullPointsList = new ArrayList<>();
                int[] hullIndices = hull.toArray();
                for (int index : hullIndices) {
                    if (index < contourArray.length) {
                        hullPointsList.add(contourArray[index]);
                    }
                }
                hullPoints.fromList(hullPointsList);
                
                double hullArea = Imgproc.contourArea(hullPoints);
                double solidity = (hullArea > 0) ? area / hullArea : 0;
                
                // Simple scoring: prioritize larger, more centered, more solid contours
                double areaScore = Math.min(1.0, area / (frameArea * 0.3)); // Normalize to 30% of frame
                double solidityScore = solidity;
                
                double totalScore = (areaScore * 0.5) + (positionScore * 0.3) + (solidityScore * 0.2);
                
                Log.d(TAG, String.format("Contour %d: area=%.0f(%.1f%%), pos=%.3f, solid=%.3f, score=%.3f", 
                    i, area, (area/frameArea)*100, positionScore, solidity, totalScore));
                
                if (totalScore > maxScore) {
                    maxScore = totalScore;
                    bestContour = contour;
                }
                
                hull.release();
                hullPoints.release();
                
            } catch (Exception e) {
                Log.w(TAG, "Error scoring contour " + i, e);
            }
        }
        
        if (bestContour != null) {
            Log.d(TAG, "✅ Selected best contour with score: " + maxScore);
        } else {
            Log.d(TAG, "❌ No suitable best contour found");
        }
        
        return bestContour;
    }

    /**
     * Advanced contour filtering with geometric validation for document detection
     */
    private MatOfPoint findBestDocumentContour(List<MatOfPoint> contours, int frameWidth, int frameHeight) {
        Log.d(TAG, "🔍 Finding best document contour from " + contours.size() + " candidates");
        
        if (contours.isEmpty()) {
            return null;
        }

        MatOfPoint bestContour = null;
        double maxScore = 0;
        double frameArea = frameWidth * frameHeight;
        
        // Minimum area should be at least 15% of frame area for better filtering
        double minArea = frameArea * 0.15;
        double maxArea = frameArea * 0.95; // Exclude full-frame contours
        
        Log.d(TAG, String.format("📊 Frame area: %.0f, Min area: %.0f, Max area: %.0f", 
            frameArea, minArea, maxArea));

        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint contour = contours.get(i);
            
            try {
                double area = Imgproc.contourArea(contour);
                
                // Skip contours outside area range
                if (area < minArea || area > maxArea) {
                    continue;
                }
                
                // Calculate perimeter
                double perimeter = Imgproc.arcLength(new MatOfPoint2f(contour.toArray()), true);
                if (perimeter < 200) { // Too small perimeter for documents
                    continue;
                }
                
                // Calculate aspect ratio and rectangularity
                org.opencv.core.Rect boundingRect = Imgproc.boundingRect(contour);
                double aspectRatio = (double) boundingRect.width / boundingRect.height;
                double rectangularity = area / (boundingRect.width * boundingRect.height);
                
                // Skip contours with extreme aspect ratios or poor rectangularity
                if (aspectRatio < 0.3 || aspectRatio > 3.5 || rectangularity < 0.4) {
                    continue;
                }
                
                // Calculate solidity (convexity measure)
                MatOfInt hull = new MatOfInt();
                Imgproc.convexHull(contour, hull);
                
                MatOfPoint hullPoints = new MatOfPoint();
                Point[] contourArray = contour.toArray();
                List<Point> hullPointsList = new ArrayList<>();
                int[] hullIndices = hull.toArray();
                for (int index : hullIndices) {
                    if (index < contourArray.length) {
                        hullPointsList.add(contourArray[index]);
                    }
                }
                hullPoints.fromList(hullPointsList);
                
                double hullArea = Imgproc.contourArea(hullPoints);
                double solidity = (hullArea > 0) ? area / hullArea : 0;
                
                // Calculate corner detection score
                double cornerScore = calculateCornerScore(contour);
                
                // Calculate position score (prefer center of frame)
                double centerX = boundingRect.x + boundingRect.width / 2.0;
                double centerY = boundingRect.y + boundingRect.height / 2.0;
                double distFromCenter = Math.sqrt(Math.pow(centerX - frameWidth/2.0, 2) + Math.pow(centerY - frameHeight/2.0, 2));
                double maxDistFromCenter = Math.sqrt(Math.pow(frameWidth/2.0, 2) + Math.pow(frameHeight/2.0, 2));
                double positionScore = 1.0 - (distFromCenter / maxDistFromCenter);
                
                // Composite scoring
                double areaScore = Math.min(1.0, area / (frameArea * 0.5)); // Normalize to 0.5 of frame
                double solidityScore = solidity;
                double rectangularityScore = rectangularity;
                double aspectScore = Math.min(aspectRatio, 1.0/aspectRatio); // Prefer square-ish shapes
                
                double totalScore = (areaScore * 0.3) + 
                                  (solidityScore * 0.25) + 
                                  (rectangularityScore * 0.2) + 
                                  (cornerScore * 0.15) + 
                                  (positionScore * 0.1);
                
                Log.d(TAG, String.format("Contour %d: area=%.0f(%.1f%%), perim=%.0f, solidity=%.3f, rect=%.3f, corners=%.3f, pos=%.3f, score=%.3f", 
                    i, area, (area/frameArea)*100, perimeter, solidity, rectangularity, cornerScore, positionScore, totalScore));
                
                // Select best contour based on composite score
                if (totalScore > maxScore && solidity > 0.75 && rectangularity > 0.5) {
                    maxScore = totalScore;
                    bestContour = contour;
                }
                
                // Clean up
                hull.release();
                hullPoints.release();
                
            } catch (Exception e) {
                Log.w(TAG, "Error evaluating contour " + i, e);
            }
        }
        
        if (bestContour != null) {
            Log.d(TAG, "✅ Selected best document contour with score: " + maxScore);
        } else {
            Log.d(TAG, "❌ No suitable document contour found");
        }
        
        return bestContour;
    }
    
    /**
     * Calculate corner detection score for contour quality
     */
    private double calculateCornerScore(MatOfPoint contour) {
        try {
            // Approximate contour to polygon
            MatOfPoint2f contour2f = new MatOfPoint2f();
            contour.convertTo(contour2f, CvType.CV_32FC2);
            
            MatOfPoint2f approx = new MatOfPoint2f();
            double epsilon = 0.02 * Imgproc.arcLength(contour2f, true);
            Imgproc.approxPolyDP(contour2f, approx, epsilon, true);
            
            Point[] points = approx.toArray();
            int numCorners = points.length;
            
            // Score based on how close to 4 corners (ideal for documents)
            double cornerScore;
            if (numCorners == 4) {
                cornerScore = 1.0; // Perfect rectangle
            } else if (numCorners >= 3 && numCorners <= 6) {
                cornerScore = 0.8 - Math.abs(numCorners - 4) * 0.1; // Close to rectangle
            } else {
                cornerScore = 0.3; // Not rectangular
            }
            
            contour2f.release();
            approx.release();
            
            return cornerScore;
            
        } catch (Exception e) {
            Log.w(TAG, "Error calculating corner score", e);
            return 0.5; // Default score
        }
    }

    /**
     * Find the largest contour that could be a document
     */
    private MatOfPoint findLargestValidContour(List<MatOfPoint> contours, int frameWidth, int frameHeight) {
        if (contours.isEmpty()) {
            return null;
        }

        MatOfPoint bestContour = null;
        double maxScore = 0;
        double frameArea = frameWidth * frameHeight;
        
        // Minimum area should be at least 10% of frame area for processed frame
        double minArea = frameArea * 0.1;
        
        Log.d(TAG, "🔍 Evaluating " + contours.size() + " contours, frame area: " + frameArea + ", min area: " + minArea);

        for (MatOfPoint contour : contours) {
            try {
                double area = Imgproc.contourArea(contour);
                
                // Skip very small contours
                if (area < minArea) {
                    continue;
                }
                
                // Calculate perimeter and check if it's reasonable
                double perimeter = Imgproc.arcLength(new MatOfPoint2f(contour.toArray()), true);
                if (perimeter < 100) { // Too small perimeter
                    continue;
                }
                
                // Calculate contour complexity score (lower is better for documents)
                double complexity = perimeter * perimeter / area; // Isoperimetric ratio
                
                // Calculate solidity (area/convex hull area) - documents should be relatively solid
                MatOfInt hull = new MatOfInt();
                Imgproc.convexHull(contour, hull);
                
                MatOfPoint hullPoints = new MatOfPoint();
                Point[] contourArray = contour.toArray();
                List<Point> hullPointsList = new ArrayList<>();
                int[] hullIndices = hull.toArray();
                for (int index : hullIndices) {
                    hullPointsList.add(contourArray[index]);
                }
                hullPoints.fromList(hullPointsList);
                
                double hullArea = Imgproc.contourArea(hullPoints);
                double solidity = (hullArea > 0) ? area / hullArea : 0;
                
                // Score based on area (higher is better) and shape characteristics
                double areaScore = area / frameArea; // Normalized area score
                double solidityScore = solidity; // Solidity score (0-1)
                double complexityScore = Math.max(0, 1.0 - (complexity - 16) / 100); // Penalize overly complex shapes
                
                double totalScore = areaScore * 0.5 + solidityScore * 0.3 + complexityScore * 0.2;
                
                Log.d(TAG, String.format("Contour: area=%.0f (%.1f%%), perimeter=%.0f, solidity=%.3f, complexity=%.2f, score=%.3f", 
                    area, areaScore * 100, perimeter, solidity, complexity, totalScore));
                
                // Check if this is the best contour so far
                if (totalScore > maxScore && solidity > 0.7 && areaScore > 0.1) {
                    maxScore = totalScore;
                    bestContour = contour;
                }
                
                // Clean up
                hull.release();
                hullPoints.release();
                
            } catch (Exception e) {
                Log.w(TAG, "Error evaluating contour", e);
            }
        }
        
        if (bestContour != null) {
            Log.d(TAG, "✅ Selected best contour with score: " + maxScore);
        } else {
            Log.d(TAG, "❌ No suitable contour found");
        }
        
        return bestContour;
    }

    /**
     * Approximate contour to a quadrilateral
     */
    private Point[] approximateToQuadrilateral(MatOfPoint contour) {
        try {
            // Convert MatOfPoint to MatOfPoint2f for approximation
            MatOfPoint2f contour2f = new MatOfPoint2f();
            contour.convertTo(contour2f, CvType.CV_32FC2);
            
            // Approximate contour
            MatOfPoint2f approxCurve = new MatOfPoint2f();
            double epsilon = 0.02 * Imgproc.arcLength(contour2f, true);
            Imgproc.approxPolyDP(contour2f, approxCurve, epsilon, true);
            
            // Check if we got 4 points (quadrilateral)
            Point[] points = approxCurve.toArray();
            if (points.length == 4) {
                // Clean up
                contour2f.release();
                approxCurve.release();
                return points;
            }
            
            // Clean up
            contour2f.release();
            approxCurve.release();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error approximating contour to quadrilateral", e);
            return null;
        }
    }

    /**
     * Transform coordinates from processed frame space back to original frame space
     */
    private Point[] transformCornersToOriginalFrame(Point[] corners, double ratio, int originalX, int originalY) {
        Point[] originalCorners = new Point[4];
        for (int i = 0; i < 4; i++) {
            originalCorners[i] = new Point(
                (corners[i].x * ratio) + originalX,
                (corners[i].y * ratio) + originalY
            );
        }
        return originalCorners;
    }

    /**
     * Refined corner detection with sub-pixel accuracy and geometric validation
     */
    private Point[] refineDocumentCorners(MatOfPoint contour, Mat enhancedImage) {
        Log.d(TAG, "🎯 Refining document corners with sub-pixel accuracy");
        
        try {
            // 1. First approximation to quadrilateral
            Point[] initialCorners = approximateToQuadrilateral(contour);
            if (initialCorners == null || initialCorners.length != 4) {
                Log.w(TAG, "Initial corner approximation failed");
                return null;
            }
            
            // 2. Refine corners using Harris corner detection in local regions
            Point[] refinedCorners = new Point[4];
            Mat cornerMask = Mat.zeros(enhancedImage.size(), CvType.CV_8UC1);
            
            for (int i = 0; i < 4; i++) {
                Point corner = initialCorners[i];
                
                // Define search region around each corner
                int searchRadius = 20;
                int x1 = Math.max(0, (int)(corner.x - searchRadius));
                int y1 = Math.max(0, (int)(corner.y - searchRadius));
                int x2 = Math.min(enhancedImage.width() - 1, (int)(corner.x + searchRadius));
                int y2 = Math.min(enhancedImage.height() - 1, (int)(corner.y + searchRadius));
                
                org.opencv.core.Rect searchRect = new org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1);
                Mat searchRegion = new Mat(enhancedImage, searchRect);
                
                // Apply Harris corner detection
                Mat corners = new Mat();
                Imgproc.cornerHarris(searchRegion, corners, 2, 3, 0.04);
                
                // Find the strongest corner in the search region
                Core.MinMaxLocResult mmr = Core.minMaxLoc(corners);
                if (mmr.maxVal > 0.01) { // Threshold for corner strength
                    refinedCorners[i] = new Point(
                        x1 + mmr.maxLoc.x,
                        y1 + mmr.maxLoc.y
                    );
                } else {
                    // Fallback to original corner if Harris detection fails
                    refinedCorners[i] = corner;
                }
                
                searchRegion.release();
                corners.release();
                
                Log.d(TAG, String.format("Corner %d: (%.1f,%.1f) -> (%.1f,%.1f)", 
                    i, corner.x, corner.y, refinedCorners[i].x, refinedCorners[i].y));
            }
            
            cornerMask.release();
            
            // 3. Geometric validation of refined corners
            if (validateCornerGeometry(refinedCorners)) {
                Log.d(TAG, "✅ Corner refinement successful");
                return refinedCorners;
            } else {
                Log.w(TAG, "⚠️ Refined corners failed geometry validation, using initial corners");
                return initialCorners;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in corner refinement", e);
            // Fallback to basic approximation
            return approximateToQuadrilateral(contour);
        }
    }
    
    /**
     * Validate geometric properties of detected corners
     */
    private boolean validateCornerGeometry(Point[] corners) {
        try {
            if (corners == null || corners.length != 4) {
                return false;
            }
            
            // 1. Check if corners form a valid quadrilateral (no self-intersections)
            for (int i = 0; i < 4; i++) {
                Point p1 = corners[i];
                Point p2 = corners[(i + 1) % 4];
                Point p3 = corners[(i + 2) % 4];
                
                // Check if points are not collinear
                double crossProduct = (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x);
                if (Math.abs(crossProduct) < 100) { // Too small means nearly collinear
                    Log.w(TAG, "Corners are nearly collinear");
                    return false;
                }
            }
            
            // 2. Check minimum distances between corners
            double minDistance = Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    double dist = Math.hypot(corners[i].x - corners[j].x, corners[i].y - corners[j].y);
                    minDistance = Math.min(minDistance, dist);
                }
            }
            
            if (minDistance < 50) { // Corners too close together
                Log.w(TAG, "Corners too close together: " + minDistance);
                return false;
            }
            
            // 3. Check if quadrilateral is roughly convex
            boolean isConvex = true;
            for (int i = 0; i < 4; i++) {
                Point p1 = corners[i];
                Point p2 = corners[(i + 1) % 4];
                Point p3 = corners[(i + 2) % 4];
                
                double cross = (p2.x - p1.x) * (p3.y - p2.y) - (p2.y - p1.y) * (p3.x - p2.x);
                if (i == 0) {
                    isConvex = cross > 0;
                } else if ((cross > 0) != isConvex) {
                    Log.w(TAG, "Quadrilateral is not convex");
                    return false;
                }
            }
            
            Log.d(TAG, "✅ Corner geometry validation passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in corner geometry validation", e);
            return false;
        }
    }
    
    /**
     * Advanced geometric validation with aspect ratio and shape analysis
     */
    private boolean validateDocumentGeometry(Point[] corners, double expectedAspectRatio) {
        Log.d(TAG, "🔍 Validating document geometry");
        
        try {
            if (corners == null || corners.length != 4) {
                Log.w(TAG, "Invalid corners for geometry validation");
                return false;
            }
            
            // 1. Order corners properly
            Point[] orderedCorners = orderPoints(corners);
            
            // 2. Calculate side lengths
            double[] sideLengths = new double[4];
            for (int i = 0; i < 4; i++) {
                Point p1 = orderedCorners[i];
                Point p2 = orderedCorners[(i + 1) % 4];
                sideLengths[i] = Math.hypot(p2.x - p1.x, p2.y - p1.y);
            }
            
            // 3. Check if opposite sides are reasonably similar (parallelogram test)
            double side1Ratio = Math.min(sideLengths[0], sideLengths[2]) / Math.max(sideLengths[0], sideLengths[2]);
            double side2Ratio = Math.min(sideLengths[1], sideLengths[3]) / Math.max(sideLengths[1], sideLengths[3]);
            
            if (side1Ratio < 0.7 || side2Ratio < 0.7) {
                Log.w(TAG, String.format("Sides not parallel enough: %.3f, %.3f", side1Ratio, side2Ratio));
                return false;
            }
            
            // 4. Calculate average width and height
            double avgWidth = (sideLengths[0] + sideLengths[2]) / 2.0;
            double avgHeight = (sideLengths[1] + sideLengths[3]) / 2.0;
            
            if (avgHeight == 0) {
                Log.w(TAG, "Invalid height calculation");
                return false;
            }
            
            double detectedAspectRatio = avgWidth / avgHeight;
            
            // 5. Validate aspect ratio if expected ratio is provided
            if (expectedAspectRatio > 0) {
                double tolerance = 0.35 * expectedAspectRatio; // 35% tolerance for real-world conditions
                boolean aspectValid = Math.abs(detectedAspectRatio - expectedAspectRatio) < tolerance;
                
                Log.d(TAG, String.format("Aspect ratio: detected=%.3f, expected=%.3f, tolerance=%.3f, valid=%s",
                        detectedAspectRatio, expectedAspectRatio, tolerance, aspectValid));
                
                if (!aspectValid) {
                    return false;
                }
            }
            
            // 6. Calculate area and perimeter ratios for shape validation
            double area = Imgproc.contourArea(new MatOfPoint(orderedCorners));
            double perimeter = sideLengths[0] + sideLengths[1] + sideLengths[2] + sideLengths[3];
            double rectangleArea = avgWidth * avgHeight;
            double rectanglePerimeter = 2 * (avgWidth + avgHeight);
            
            double areaRatio = area / rectangleArea;
            double perimeterRatio = perimeter / rectanglePerimeter;
            
            // Document should be reasonably rectangular
            if (areaRatio < 0.8 || areaRatio > 1.2 || perimeterRatio < 0.9 || perimeterRatio > 1.1) {
                Log.w(TAG, String.format("Shape not rectangular enough: area ratio=%.3f, perimeter ratio=%.3f", 
                    areaRatio, perimeterRatio));
                return false;
            }
            
            // 7. Check corner angles (should be roughly 90 degrees for documents)
            for (int i = 0; i < 4; i++) {
                Point p1 = orderedCorners[i];
                Point p2 = orderedCorners[(i + 1) % 4];
                Point p3 = orderedCorners[(i + 2) % 4];
                
                // Calculate vectors
                double v1x = p1.x - p2.x;
                double v1y = p1.y - p2.y;
                double v2x = p3.x - p2.x;
                double v2y = p3.y - p2.y;
                
                // Calculate angle
                double dot = v1x * v2x + v1y * v2y;
                double mag1 = Math.sqrt(v1x * v1x + v1y * v1y);
                double mag2 = Math.sqrt(v2x * v2x + v2y * v2y);
                
                if (mag1 > 0 && mag2 > 0) {
                    double angle = Math.acos(Math.max(-1, Math.min(1, dot / (mag1 * mag2))));
                    double angleDegrees = Math.toDegrees(angle);
                    
                    // Allow 45-135 degree range for perspective distortion
                    if (angleDegrees < 45 || angleDegrees > 135) {
                        Log.w(TAG, String.format("Corner %d angle too extreme: %.1f degrees", i, angleDegrees));
                        return false;
                    }
                }
            }
            
            Log.d(TAG, "✅ Document geometry validation passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in document geometry validation", e);
            return false;
        }
    }
    
    /**
     * Enhanced perspective transformation with quality improvements
     */
    private Mat performEnhancedPerspectiveTransform(Mat originalFrame, Point[] corners) {
        Log.d(TAG, "🔄 Performing enhanced perspective transformation");
        
        if (corners == null || corners.length != 4) {
            Log.e(TAG, "Invalid corners for perspective transformation");
            return null;
        }

        try {
            // Order points: top-left, top-right, bottom-right, bottom-left
            Point[] orderedCorners = orderPoints(corners);
            
            // Calculate output dimensions with better accuracy
            double w1 = Math.hypot(orderedCorners[1].x - orderedCorners[0].x, orderedCorners[1].y - orderedCorners[0].y);
            double w2 = Math.hypot(orderedCorners[2].x - orderedCorners[3].x, orderedCorners[2].y - orderedCorners[3].y);
            double h1 = Math.hypot(orderedCorners[3].x - orderedCorners[0].x, orderedCorners[3].y - orderedCorners[0].y);
            double h2 = Math.hypot(orderedCorners[2].x - orderedCorners[1].x, orderedCorners[2].y - orderedCorners[1].y);
            
            double maxWidth = Math.max(w1, w2);
            double maxHeight = Math.max(h1, h2);
            
            // Apply minimum and maximum size constraints
            maxWidth = Math.max(maxWidth, 200);
            maxHeight = Math.max(maxHeight, 200);
            maxWidth = Math.min(maxWidth, originalFrame.width() * 2); // Allow some upscaling
            maxHeight = Math.min(maxHeight, originalFrame.height() * 2);
            
            // If expected aspect ratio is available, use it to improve dimensions
            if (expectedAspectRatio > 0) {
                double currentAspectRatio = maxWidth / maxHeight;
                double aspectDifference = Math.abs(currentAspectRatio - expectedAspectRatio);
                
                if (aspectDifference > 0.1) { // Significant difference
                    if (currentAspectRatio > expectedAspectRatio) {
                        // Too wide, adjust width
                        maxWidth = maxHeight * expectedAspectRatio;
                    } else {
                        // Too tall, adjust height  
                        maxHeight = maxWidth / expectedAspectRatio;
                    }
                }
            }
            
            Log.d(TAG, String.format("Transform dimensions: %.0fx%.0f", maxWidth, maxHeight));
            
            // Source points (actual corners)
            Mat srcPoints = new Mat(4, 1, CvType.CV_32FC2);
            for (int i = 0; i < 4; i++) {
                srcPoints.put(i, 0, orderedCorners[i].x, orderedCorners[i].y);
            }
            
            // Destination points (perfect rectangle)
            Mat dstPoints = new Mat(4, 1, CvType.CV_32FC2);
            dstPoints.put(0, 0, 0, 0);
            dstPoints.put(1, 0, maxWidth - 1, 0);
            dstPoints.put(2, 0, maxWidth - 1, maxHeight - 1);
            dstPoints.put(3, 0, 0, maxHeight - 1);
            
            // Get perspective transform matrix
            Mat transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
            
            // Apply transformation with high-quality interpolation
            Mat warped = new Mat();
            Imgproc.warpPerspective(originalFrame, warped, transformMatrix, new Size(maxWidth, maxHeight), 
                Imgproc.INTER_LANCZOS4, Core.BORDER_CONSTANT, new Scalar(255, 255, 255));
            
            // Clean up
            srcPoints.release();
            dstPoints.release();
            transformMatrix.release();
            
            // Validate output
            if (warped.width() < 100 || warped.height() < 100) {
                Log.w(TAG, "Perspective transformation resulted in too small image: " + warped.width() + "x" + warped.height());
                warped.release();
                return null;
            }
            
            Log.d(TAG, "✅ Enhanced perspective transformation complete");
            return warped;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in enhanced perspective transformation", e);
            return null;
        }
    }

    /**
     * Validate aspect ratio of detected quadrilateral
     */
    private boolean validateAspectRatio(Point[] quad, double expectedAspectRatio) {
        if (expectedAspectRatio <= 0 || quad.length != 4) {
            return true; // No validation if no expected ratio
        }

        try {
            // Calculate average width and height of the quadrilateral
            double w1 = Math.hypot(quad[1].x - quad[0].x, quad[1].y - quad[0].y);
            double w2 = Math.hypot(quad[2].x - quad[3].x, quad[2].y - quad[3].y);
            double h1 = Math.hypot(quad[3].x - quad[0].x, quad[3].y - quad[0].y);
            double h2 = Math.hypot(quad[2].x - quad[1].x, quad[2].y - quad[1].y);
            
            double avgWidth = (w1 + w2) / 2.0;
            double avgHeight = (h1 + h2) / 2.0;
            
            if (avgHeight == 0) return false;
            
            double detectedAspectRatio = avgWidth / avgHeight;
            double tolerance = 0.25 * expectedAspectRatio; // 25% tolerance for better real-world performance
            
            boolean isValid = Math.abs(detectedAspectRatio - expectedAspectRatio) < tolerance;
            
            Log.d(TAG, String.format("Aspect ratio validation: detected=%.3f, expected=%.3f, tolerance=%.3f, valid=%s",
                    detectedAspectRatio, expectedAspectRatio, tolerance, isValid));
            
            return isValid;
        } catch (Exception e) {
            Log.e(TAG, "Error validating aspect ratio", e);
            return false;
        }
    }

    /**
     * Perform perspective transformation on detected document
     */
    private Mat performPerspectiveTransform(Mat originalFrame, Point[] quad) {
        if (quad == null || quad.length != 4) {
            Log.e(TAG, "Invalid quad for perspective transformation");
            return null;
        }

        try {
            // Order points: top-left, top-right, bottom-right, bottom-left
            Point[] orderedQuad = orderPoints(quad);
            
            // Calculate output dimensions
            double w1 = Math.hypot(orderedQuad[1].x - orderedQuad[0].x, orderedQuad[1].y - orderedQuad[0].y);
            double w2 = Math.hypot(orderedQuad[2].x - orderedQuad[3].x, orderedQuad[2].y - orderedQuad[3].y);
            double h1 = Math.hypot(orderedQuad[3].x - orderedQuad[0].x, orderedQuad[3].y - orderedQuad[0].y);
            double h2 = Math.hypot(orderedQuad[2].x - orderedQuad[1].x, orderedQuad[2].y - orderedQuad[1].y);
            
            double maxWidth = Math.max(w1, w2);
            double maxHeight = Math.max(h1, h2);
            
            // Ensure minimum dimensions and reasonable maximum
            maxWidth = Math.max(maxWidth, 100);
            maxHeight = Math.max(maxHeight, 100);
            maxWidth = Math.min(maxWidth, originalFrame.width());
            maxHeight = Math.min(maxHeight, originalFrame.height());
            
            // If expected aspect ratio is available, use it to correct dimensions
            if (expectedAspectRatio > 0) {
                double currentAspectRatio = maxWidth / maxHeight;
                double tolerance = 0.1; // 10% tolerance for dimension correction
                
                if (Math.abs(currentAspectRatio - expectedAspectRatio) > tolerance) {
                    if (currentAspectRatio > expectedAspectRatio) {
                        // Too wide, adjust width
                        maxWidth = maxHeight * expectedAspectRatio;
                    } else {
                        // Too tall, adjust height
                        maxHeight = maxWidth / expectedAspectRatio;
                    }
                }
            }
            
            Log.d(TAG, String.format("Perspective transform dimensions: %.0fx%.0f", maxWidth, maxHeight));
            
            // Source points
            Mat srcPoints = new Mat(4, 1, CvType.CV_32FC2);
            for (int i = 0; i < 4; i++) {
                srcPoints.put(i, 0, orderedQuad[i].x, orderedQuad[i].y);
            }
            
            // Destination points (rectangle)
            Mat dstPoints = new Mat(4, 1, CvType.CV_32FC2);
            dstPoints.put(0, 0, 0, 0);
            dstPoints.put(1, 0, maxWidth - 1, 0);
            dstPoints.put(2, 0, maxWidth - 1, maxHeight - 1);
            dstPoints.put(3, 0, 0, maxHeight - 1);
            
            // Get perspective transform matrix
            Mat transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
            
            // Apply transformation
            Mat warped = new Mat();
            Imgproc.warpPerspective(originalFrame, warped, transformMatrix, new Size(maxWidth, maxHeight));
            
            // Clean up
            srcPoints.release();
            dstPoints.release();
            transformMatrix.release();
            
            // Validate output
            if (warped.width() < 50 || warped.height() < 50) {
                Log.w(TAG, "Perspective transformation resulted in too small image: " + warped.width() + "x" + warped.height());
                warped.release();
                return null;
            }
            
            return warped;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in perspective transformation", e);
            return null;
        }
    }

    /**
     * Order points as: top-left, top-right, bottom-right, bottom-left
     */
    private Point[] orderPoints(Point[] pts) {
        Point[] rect = new Point[4];
        
        // Sum of coordinates
        double[] sums = new double[4];
        double[] diffs = new double[4];
        
        for (int i = 0; i < 4; i++) {
            sums[i] = pts[i].x + pts[i].y;
            diffs[i] = pts[i].x - pts[i].y;
        }
        
        // Top-left has smallest sum
        int topLeftIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (sums[i] < sums[topLeftIdx]) {
                topLeftIdx = i;
            }
        }
        rect[0] = pts[topLeftIdx];
        
        // Bottom-right has largest sum
        int bottomRightIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (sums[i] > sums[bottomRightIdx]) {
                bottomRightIdx = i;
            }
        }
        rect[2] = pts[bottomRightIdx];
        
        // Top-right has smallest difference
        int topRightIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (diffs[i] < diffs[topRightIdx]) {
                topRightIdx = i;
            }
        }
        rect[1] = pts[topRightIdx];
        
        // Bottom-left has largest difference
        int bottomLeftIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (diffs[i] > diffs[bottomLeftIdx]) {
                bottomLeftIdx = i;
            }
        }
        rect[3] = pts[bottomLeftIdx];
        
        return rect;
    }

    private void saveCroppedMat(Mat processFrame, String name) {
        try {
            if (processFrame == null || processFrame.empty()) {
                Log.w(TAG, "Cannot save null or empty Mat: " + name);
                return;
            }
            
            File outputDir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (outputDir != null && !outputDir.exists()) {
                outputDir.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
            File imageFile = new File(outputDir, "debug_" + name + "_" + timeStamp + ".jpg");

            boolean saved = Imgcodecs.imwrite(imageFile.getAbsolutePath(), processFrame);
            if (saved) {
                Log.d(TAG, "📸 Saved debug image: " + imageFile.getName() + 
                    " (" + processFrame.width() + "x" + processFrame.height() + ")");
            } else {
                Log.e(TAG, "❌ Failed to save debug image: " + name);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving debug image '" + name + "': " + e.getMessage());
        }
    }

    private String matToBase64(Mat mat) {
        try {
            if (mat == null || mat.empty()) {
                Log.e(TAG, "Cannot encode null or empty Mat to base64");
                return null;
            }

            Log.d(TAG, String.format("Encoding Mat to base64: %dx%d, type: %d",
                    mat.width(), mat.height(), mat.type()));

            // The mat should already be in RGB format
            MatOfByte buffer = new MatOfByte();

            // Use JPEG with quality setting for smaller file size and better memory
            // management
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85); // 85% quality
            boolean success = Imgcodecs.imencode(".jpg", mat, buffer, params);

            if (!success) {
                Log.e(TAG, "Failed to encode Mat to JPEG format");
                buffer.release();
                params.release();
                return null;
            }

            byte[] bytes = buffer.toArray();
            buffer.release();
            params.release();

            if (bytes == null || bytes.length == 0) {
                Log.e(TAG, "Encoded bytes are null or empty");
                return null;
            }

            Log.d(TAG, String.format("Successfully encoded image: %d bytes", bytes.length));
            return Base64.encodeToString(bytes, Base64.NO_WRAP); // NO_WRAP for cleaner output

        } catch (Exception e) {
            Log.e(TAG, "Error encoding Mat to base64: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private Mat imageToMat(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Get the YUV data in NV21 format
        byte[] nv21 = YUV_420_888toNV21(image);

        // Create a Mat from the NV21 data
        Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        yuvMat.put(0, 0, nv21);

        // Convert YUV to RGB
        Mat rgbMat = new Mat();
        Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21);

        yuvMat.release();

        return rgbMat;
    }

    private byte[] YUV_420_888toNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8];
        int offset = 0;

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int ySize = yBuffer.remaining();
        yBuffer.get(data, 0, ySize);
        offset += ySize;

        byte[] uData = new byte[uBuffer.remaining()];
        uBuffer.get(uData);
        byte[] vData = new byte[vBuffer.remaining()];
        vBuffer.get(vData);

        for (int row = 0; row < height / 2; row++) {
            int uvRowStart = row * uvRowStride;

            for (int col = 0; col < width / 2; col++) {
                int uvPixelOffset = uvRowStart + col * uvPixelStride;

                data[offset++] = uData[uvPixelOffset]; // U (Cr)
                data[offset++] = vData[uvPixelOffset]; // V (Cb)
            }
        }

        return data;
    }

    // Implement the required methods of TextureView.SurfaceTextureListener

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        // Handle size changes if needed
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        // Called every time there's a new Camera preview frame
        // Send overlay coordinates to React Native for display
        sendOverlayCoordinates();
    }

    // Throttle overlay updates to avoid spam
    private long lastOverlaySentTime = 0;
    private static final long OVERLAY_UPDATE_INTERVAL_MS = 50; // Send overlay updates every 50ms for real-time response

    /**
     * Send overlay coordinates to React Native for display
     */
    private void sendOverlayCoordinates() {
        if (!showRectangleOverlay || !hasScanRegion || scanRegionWidth <= 0 || scanRegionHeight <= 0) {
            return;
        }

        // Minimal throttling for real-time response
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastOverlaySentTime < OVERLAY_UPDATE_INTERVAL_MS) {
            return;
        }

        try {
            // Get the view dimensions
            int viewWidth = getWidth();
            int viewHeight = getHeight();

            if (viewWidth <= 0 || viewHeight <= 0 || frameListener == null)
                return;

            // Get actual frame dimensions accounting for rotation
            int rotation = getImageRotation();
            double frameWidth, frameHeight;
            if (rotation == 90 || rotation == 270) {
                frameWidth = imageHeight;
                frameHeight = imageWidth;
            } else {
                frameWidth = imageWidth;
                frameHeight = imageHeight;
            }
            
            // Scale the scan region coordinates from frame space to view space
            double scaleX = (double) viewWidth / frameWidth;
            double scaleY = (double) viewHeight / frameHeight;

            // Calculate scaled rectangle position for React Native overlay
            double left = scanRegionX * scaleX;
            double top = scanRegionY * scaleY;
            double width = scanRegionWidth * scaleX;
            double height = scanRegionHeight * scaleY;

            Log.v(TAG, String.format("📱 Sending overlay coords: view=%dx%d, frame=%.0fx%.0f (rotation=%d°), scale=%.2fx%.2f",
                    viewWidth, viewHeight, frameWidth, frameHeight, rotation, scaleX, scaleY));
            Log.v(TAG, String.format("📱 Overlay rectangle: (%.1f,%.1f,%.1fx%.1f)", left, top, width, height));

            // Send coordinates to React Native via callback
            if (frameListener != null) {
                try {
                    frameListener.onOverlayUpdate(left, top, width, height);
                    lastOverlaySentTime = currentTime;
                    Log.d(TAG, "✅ Overlay coordinates sent to React Native");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error calling onOverlayUpdate", e);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sending overlay coordinates", e);
        }
    }

    /**
     * Simple real-time document detection based on react-native-document-scanner-master logic
     * This method detects rectangular documents without aspect ratio constraints
     */
    private Point[] detectDocumentRealTime(Mat frame, int width, int height) {
        Log.d(TAG, "⚡ Ultra-fast document detection");
        
        Mat gray = null;
        Mat thresh = null;
        
        try {
            // 1. Convert to grayscale
            gray = new Mat();
            if (frame.channels() == 3) {
                Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = frame.clone();
            }
            
            // 2. Otsu thresholding
            thresh = new Mat();
            Imgproc.threshold(gray, thresh, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            
            // 3. Find contours (fastest method)
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(thresh, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            
            if (contours.isEmpty()) {
                // No contours found - document not in view
                // sendFeedbackIfNeeded("No document detected. Please place a document in the camera view.");
                return null;
            }
            
            // 4. Find largest contour (simple and fast)
            MatOfPoint largestContour = null;
            double maxArea = 0;
            boolean foundLargeContour = false;
            
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area > maxArea && area > width * height * 0.15) { // At least 15% of frame
                    maxArea = area;
                    largestContour = contour;
                    foundLargeContour = true;
                }
            }
            
            if (largestContour == null) {
                // Contours found but none are large enough - document too small or not properly positioned
                // sendFeedbackIfNeeded("Document too small or not properly positioned. Please move the document closer to the camera.");
                return null;
            }
            
            // 5. Approximate to quadrilateral (fast approximation)
            MatOfPoint2f contour2f = new MatOfPoint2f();
            largestContour.convertTo(contour2f, CvType.CV_32FC2);
            
            double epsilon = 0.04 * Imgproc.arcLength(contour2f, true); // More lenient approximation
            MatOfPoint2f approx2f = new MatOfPoint2f();
            Imgproc.approxPolyDP(contour2f, approx2f, epsilon, true);
            
            MatOfPoint approx = new MatOfPoint();
            approx2f.convertTo(approx, CvType.CV_32S);
            
            Point[] points = approx.toArray();
            
            // 6. Check if it's a quadrilateral
            if (points.length == 4) {
                Log.d(TAG, "✅ Fast quadrilateral detection successful");
                Point[] orderedPoints = orderPoints(points);
                
                contour2f.release();
                approx2f.release();
                approx.release();
                
                return orderedPoints;
            } else {
                // Contour found but not quadrilateral - document edges not clear
                // sendFeedbackIfNeeded("Document edges not clear. Please ensure the document is flat and well-lit.");
                contour2f.release();
                approx2f.release();
                approx.release();
                return null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in fast document detection", e);
            // sendFeedbackIfNeeded("Processing error. Please try again.");
            return null;
        } finally {
            if (gray != null) gray.release();
            if (thresh != null) thresh.release();
        }
    }
    
    
    /**
     * Simple validation to check if the detected quadrilateral is reasonable
     */
    private boolean isValidQuadrilateral(Point[] corners) {
        if (corners == null || corners.length != 4) {
            return false;
        }
        
        // Check for minimum area (avoid tiny detections)
        double area = calculateQuadrilateralArea(corners);
        double frameArea = imageWidth * imageHeight;
        
        if (area < frameArea * 0.05) { // At least 5% of frame area
            Log.d(TAG, "Quadrilateral too small: " + area + " vs " + (frameArea * 0.05));
            return false;
        }
        
        // Check for reasonable aspect ratio (not too extreme)
        double width = Math.max(
            distance(corners[0], corners[1]), 
            distance(corners[2], corners[3])
        );
        double height = Math.max(
            distance(corners[1], corners[2]), 
            distance(corners[3], corners[0])
        );
        
        double aspectRatio = width / height;
        if (aspectRatio < 0.3 || aspectRatio > 3.0) { // Reasonable range
            Log.d(TAG, "Extreme aspect ratio: " + aspectRatio);
            return false;
        }
        
        Log.d(TAG, "✅ Valid quadrilateral: area=" + area + ", aspect=" + aspectRatio);
        return true;
    }
    
    /**
     * Calculate area of a quadrilateral using shoelace formula
     */
    private double calculateQuadrilateralArea(Point[] corners) {
        if (corners.length != 4) return 0;
        
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += corners[i].x * corners[j].y;
            area -= corners[j].x * corners[i].y;
        }
        return Math.abs(area) / 2.0;
    }
    
    
    /**
     * Simple perspective transformation without complex validation
     */
    private Mat performSimplePerspectiveTransform(Mat originalFrame, Point[] corners) {
        Log.d(TAG, "🔄 Performing simple perspective transform");
        Log.d(TAG, "📷 Source frame: " + originalFrame.width() + "x" + originalFrame.height());
        
        try {
            // Log the corners being used for transformation
            Log.d(TAG, "📍 Transform corners:");
            Log.d(TAG, "  TL: (" + corners[0].x + ", " + corners[0].y + ")");
            Log.d(TAG, "  TR: (" + corners[1].x + ", " + corners[1].y + ")");
            Log.d(TAG, "  BR: (" + corners[2].x + ", " + corners[2].y + ")");
            Log.d(TAG, "  BL: (" + corners[3].x + ", " + corners[3].y + ")");
            
            // Calculate output dimensions based on the detected corners
            double width1 = distance(corners[0], corners[1]);
            double width2 = distance(corners[2], corners[3]);
            double height1 = distance(corners[1], corners[2]);
            double height2 = distance(corners[3], corners[0]);
            
            Log.d(TAG, "📏 Calculated dimensions: width1=" + width1 + ", width2=" + width2 + ", height1=" + height1 + ", height2=" + height2);
            
            int outputWidth = (int) Math.max(width1, width2);
            int outputHeight = (int) Math.max(height1, height2);
            
            // Ensure minimum size
            outputWidth = Math.max(outputWidth, 400);
            outputHeight = Math.max(outputHeight, 300);
            
            Log.d(TAG, "📦 Output dimensions: " + outputWidth + "x" + outputHeight);
            
            // Define destination points (rectangle)
            Point[] dst = new Point[4];
            dst[0] = new Point(0, 0);                           // top-left
            dst[1] = new Point(outputWidth - 1, 0);             // top-right
            dst[2] = new Point(outputWidth - 1, outputHeight - 1); // bottom-right
            dst[3] = new Point(0, outputHeight - 1);            // bottom-left
            
            // Convert to MatOfPoint2f
            MatOfPoint2f srcPoints = new MatOfPoint2f(corners);
            MatOfPoint2f dstPoints = new MatOfPoint2f(dst);
            
            // Get perspective transform matrix
            Mat perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
            
            // Apply transformation
            Mat result = new Mat();
            Imgproc.warpPerspective(originalFrame, result, perspectiveMatrix, 
                new Size(outputWidth, outputHeight));
            
            // Clean up
            srcPoints.release();
            dstPoints.release();
            perspectiveMatrix.release();
            
            // Apply correction for inverted and upside down image
            Mat corrected = fixImageOrientation(result);
            result.release();
            
            Log.d(TAG, "✅ Perspective transform complete with corrections: " + outputWidth + "x" + outputHeight);
            return corrected;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in perspective transform", e);
            return null;
        }
    }
    
    /**
     * Set detection sensitivity - number of consistent detections required
     */
    public void setDetectionCountBeforeCapture(int count) {
        this.numOfRectangles = Math.max(1, count);
        Log.d(TAG, "Detection count set to: " + this.numOfRectangles);
    }
    
    /**
     * Enable or disable auto-capture when document is detected
     */
    public void setAutoCapture(boolean autoCapture) {
        this.autoCapture = autoCapture;
        Log.d(TAG, "Auto-capture set to: " + this.autoCapture);
    }
    
    /**
     * Enable or disable image flip correction for testing
     */
    public void setImageFlipCorrection(boolean enable) {
        this.enableImageFlipCorrection = enable;
        Log.d(TAG, "Image flip correction set to: " + this.enableImageFlipCorrection);
    }
    
    /**
     * Set the detection refresh rate in milliseconds (like react-native-document-scanner)
     * Lower values = faster detection, higher values = better performance
     */
    public void setDetectionRefreshRateInMS(int refreshRateMS) {
        if (refreshRateMS > 0) {
            // Note: This would require making PROCESSING_INTERVAL_MS non-final
            // For now, we'll use a different approach
            Log.d(TAG, "Detection refresh rate requested: " + refreshRateMS + "ms (use setDetectionCountBeforeCapture for tuning)");
        }
    }
    
    /**
     * Enable or disable blur detection
     */
    public void setBlurDetection(boolean enable) {
        this.enableBlurDetection = enable;
        Log.d(TAG, "Blur detection set to: " + this.enableBlurDetection);
    }
    

    
    /**
     * Set blur detection threshold (lower = more sensitive to blur)
     * @param threshold Laplacian variance threshold (default: 100.0)
     */
    public void setBlurThreshold(double threshold) {
        this.blurThreshold = threshold;
        Log.d(TAG, "Blur threshold set to: " + this.blurThreshold);
    }
    
    /**
     * Get current blur detection status
     * @return true if blur detection is enabled
     */
    public boolean isBlurDetectionEnabled() {
        return enableBlurDetection;
    }
    
    /**
     * Get current blur detection count
     * @return number of consecutive blur detections
     */
    public int getBlurDetectionCount() {
        return blurDetectionCount;
    }
    
    /**
     * Detect if image is blurry using Laplacian variance
     * @param image Input image to check for blur
     * @return true if image is blurry, false otherwise
     */
    private boolean isImageBlurry(Mat image) {
        if (!enableBlurDetection || image == null || image.empty()) {
            return false;
        }
        
        try {
            Mat gray = new Mat();
            Mat laplacian = new Mat();
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble stddev = new MatOfDouble();
            
            // Convert to grayscale if needed
            if (image.channels() == 3) {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = image.clone();
            }
            
            // Apply Laplacian filter to detect edges
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            
            // Calculate variance of Laplacian
            Core.meanStdDev(laplacian, mean, stddev);
            double variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
            
            // Clean up
            gray.release();
            laplacian.release();
            mean.release();
            stddev.release();
            
            Log.d(TAG, "Blur detection - Laplacian variance: " + variance + " (threshold: " + blurThreshold + ")");
            
            boolean isBlurry = variance < blurThreshold;
            
            if (isBlurry) {
                blurDetectionCount++;
                Log.d(TAG, "Blur detected! Count: " + blurDetectionCount + "/" + MAX_BLUR_COUNT);
                // No feedback message - just silently filter blurry images
            } else {
                blurDetectionCount = 0; // Reset counter for sharp images
            }
            
            return isBlurry;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in blur detection", e);
            return false;
        }
    }
    
    /**
     * Fix image orientation and mirroring issues
     */
    private Mat fixImageOrientation(Mat inputImage) {
        if (inputImage == null || inputImage.empty()) {
            return inputImage;
        }
        
        try {
            Log.d(TAG, "🔍 Image correction input: " + inputImage.width() + "x" + inputImage.height() + 
                  ", lensFacing: " + lensFacing + 
                  " (FRONT=" + CameraCharacteristics.LENS_FACING_FRONT + 
                  ", BACK=" + CameraCharacteristics.LENS_FACING_BACK + ")");
            
            Mat corrected = new Mat();
            
            if (enableImageFlipCorrection) {
                // Step 1: Apply horizontal flip for mirror correction
                Log.d(TAG, "🔄 Applying horizontal flip (mirror correction)");
                Mat flipped = new Mat();
                Core.flip(inputImage, flipped, 1); // Horizontal flip only (1 = flip around y-axis)
                
                // Step 2: Apply 90-degree rotation correction for landscape documents 
                Log.d(TAG, "🔄 Applying 90-degree rotation correction");
                Core.rotate(flipped, corrected, Core.ROTATE_90_COUNTERCLOCKWISE);
                flipped.release();
            } else {
                Log.d(TAG, "🚫 Image flip correction disabled, returning original");
                inputImage.copyTo(corrected);
            }
            
            Log.d(TAG, "✅ Image correction applied: " + corrected.width() + "x" + corrected.height());
            return corrected;
            
        } catch (Exception e) {
            Log.e(TAG, "Error fixing image orientation: " + e.getMessage());
            return inputImage; // Return original if correction fails
        }
    }
    
    /**
     * Offers manual cropping when automatic detection fails repeatedly
     */
    private void offerManualCropping(Mat frame) {
        try {
            Log.i(TAG, "🔧 Preparing manual cropping mode");
            manualCropMode = true;
            
            // Store this exact frame for manual cropping to ensure consistency
            if (lastProcessedFrame != null) {
                lastProcessedFrame.release();
            }
            lastProcessedFrame = frame.clone();
            
            // Convert frame to base64 for React Native
            String frameBase64 = matToBase64(frame);
            
            if (frameListener != null && frameBase64 != null) {
                Log.i(TAG, "📷 Sending frame to React Native for manual cropping: " + frame.width() + "x" + frame.height());
                frameListener.onManualCropNeeded(frame.width(), frame.height(), frameBase64);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error offering manual cropping: " + e.getMessage());
            manualCropMode = false;
        }
    }
    
    /**
     * Processes manual cropping with user-defined points
     */
    public void processManualCrop(double[] cornerPoints, int frameWidth, int frameHeight) {
        try {
            Log.i(TAG, "🔧 Processing manual crop with " + cornerPoints.length + " corner points");
            Log.i(TAG, "🔧 Manual crop frame dimensions: " + frameWidth + "x" + frameHeight);
            
            if (cornerPoints.length != 8) {
                Log.e(TAG, "❌ Invalid corner points count: " + cornerPoints.length + " (expected 8)");
                return;
            }
            
            // Convert corner points to OpenCV Points (order: TL, TR, BR, BL)
            Point[] corners = new Point[4];
            String[] cornerNames = {"TopLeft", "TopRight", "BottomRight", "BottomLeft"};
            for (int i = 0; i < 4; i++) {
                corners[i] = new Point(cornerPoints[i * 2], cornerPoints[i * 2 + 1]);
                Log.d(TAG, "📍 Manual " + cornerNames[i] + " " + i + ": (" + corners[i].x + ", " + corners[i].y + ")");
            }
            
            // Get the current frame for cropping
            Mat currentFrame = getCurrentFrame();
            if (currentFrame == null) {
                Log.e(TAG, "❌ No current frame available for manual cropping");
                return;
            }
            
            Log.i(TAG, "📷 Current frame dimensions: " + currentFrame.width() + "x" + currentFrame.height());
            
            // Order the points properly for perspective transformation
            Point[] orderedCorners = orderPoints(corners);
            String[] orderedNames = {"TopLeft", "TopRight", "BottomRight", "BottomLeft"};
            Log.d(TAG, "📍 Ordered corners after orderPoints():");
            for (int i = 0; i < orderedCorners.length; i++) {
                Log.d(TAG, "  " + orderedNames[i] + " " + i + ": (" + orderedCorners[i].x + ", " + orderedCorners[i].y + ")");
            }
            
            // Perform perspective transformation with manual points
            Mat croppedDocument = performSimplePerspectiveTransform(currentFrame, orderedCorners);
            String base64Image = null;
            
            if (croppedDocument != null) {
                Log.i(TAG, "📏 Cropped document dimensions: " + croppedDocument.width() + "x" + croppedDocument.height());
                base64Image = matToBase64(croppedDocument);
                croppedDocument.release();
            } else {
                Log.e(TAG, "❌ Failed to perform perspective transformation");
            }
            
            // Reset manual crop mode and detection failure count
            manualCropMode = false;
            detectionFailureCount = 0;
            
            // Notify listener with results
            List<Point> cornersList = Arrays.asList(orderedCorners);
            if (frameListener != null) {
                frameListener.onDocumentDetected(cornersList, currentFrame.width(), 
                    currentFrame.height(), base64Image);
            }
            
            Log.i(TAG, "✅ Manual cropping completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing manual crop: " + e.getMessage(), e);
            manualCropMode = false;
        }
    }
    
    /**
     * Gets the current camera frame for manual cropping
     */
    private Mat getCurrentFrame() {
        if (lastProcessedFrame != null) {
            Log.d(TAG, "✅ Returning last processed frame for manual cropping");
            return lastProcessedFrame.clone();
        } else {
            Log.w(TAG, "⚠️ No last processed frame available for manual cropping");
            return null;
        }
    }
}
