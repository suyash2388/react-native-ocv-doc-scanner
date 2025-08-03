package com.mydocumentscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.content.Context;
import java.io.InputStream;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.calib3d.Calib3d;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class TemplateMatchingModule extends ReactContextBaseJavaModule {

    private static final String TAG = "TemplateMatchingModule";

    // Template matching methods
    private static final int TM_CCOEFF_NORMED = Imgproc.TM_CCOEFF_NORMED;
    private static final int TM_SQDIFF_NORMED = Imgproc.TM_SQDIFF_NORMED;

    // Helper class for template configuration (similar to Python dict)
    private static class TemplateConfig {
        public Mat template;
        public String name;
        public String position;
        public double offsetX;
        public double offsetY;
        public double docWidthRatio;
        public double docHeightRatio;
        
        public TemplateConfig(Mat template, String name, String position, 
                             double offsetX, double offsetY, 
                             double docWidthRatio, double docHeightRatio) {
            this.template = template;
            this.name = name;
            this.position = position;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.docWidthRatio = docWidthRatio;
            this.docHeightRatio = docHeightRatio;
        }
    }

    // Helper class for template match results
    private static class TemplateMatch {
        public String name;
        public String position;
        public Point matchCoord;
        public Size templateSize;
        public double confidence;
        public TemplateConfig config;
        
        public TemplateMatch(String name, String position, Point matchCoord, 
                           Size templateSize, double confidence, TemplateConfig config) {
            this.name = name;
            this.position = position;
            this.matchCoord = matchCoord;
            this.templateSize = templateSize;
            this.confidence = confidence;
            this.config = config;
        }
    }

    // Helper class for document bounds
    private static class DocumentBounds {
        public int x, y, width, height;
        
        public DocumentBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed");
        } else {
            Log.d(TAG, "OpenCV loaded successfully");
        }
    }

    public TemplateMatchingModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return "TemplateMatchingModule";
    }

    // ========================================
    // MAIN PUBLIC METHODS (React Native Bridge)
    // ========================================

    /**
     * Extract document from image using template matching (from file path)
     * Similar to Python's DocumentExtractor.extract_document()
     */
    @ReactMethod
    public void extractDocument(String imagePath, ReadableArray templateConfigs, 
                               double threshold, Promise promise) {
        try {
            Mat image = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) {
                promise.reject("IMAGE_READ_ERROR", "Could not read image from path: " + imagePath);
                return;
            }

            List<TemplateConfig> configs = parseTemplateConfigs(templateConfigs);
            extractDocumentInternal(image, configs, threshold, false, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractDocument", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Extract document from base64 image using template matching
     * Similar to Python's DocumentExtractor.extract_document()
     */
    @ReactMethod
    public void extractDocumentFromBase64(String imageBase64, ReadableArray templateConfigs, 
                                         double threshold, Promise promise) {
        try {
            Mat image = base64ToMat(imageBase64);
            if (image.empty()) {
                promise.reject("IMAGE_DECODE_ERROR", "Could not decode image from base64");
                return;
            }

            List<TemplateConfig> configs = parseTemplateConfigsFromBase64(templateConfigs);
            extractDocumentInternal(image, configs, threshold, false, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractDocumentFromBase64", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Extract document with perspective correction
     * Similar to Python's DocumentExtractor.extract_with_perspective_correction()
     */
    @ReactMethod
    public void extractDocumentWithPerspectiveCorrection(String imagePath, ReadableArray templateConfigs, 
                                                        double threshold, Promise promise) {
        try {
            Mat image = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) {
                promise.reject("IMAGE_READ_ERROR", "Could not read image from path: " + imagePath);
                return;
            }

            List<TemplateConfig> configs = parseTemplateConfigs(templateConfigs);
            extractDocumentInternal(image, configs, threshold, true, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractDocumentWithPerspectiveCorrection", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Extract document with perspective correction from base64
     */
    @ReactMethod
    public void extractDocumentWithPerspectiveCorrectionFromBase64(String imageBase64, ReadableArray templateConfigs, 
                                                                  double threshold, Promise promise) {
        try {
            Mat image = base64ToMat(imageBase64);
            if (image.empty()) {
                promise.reject("IMAGE_DECODE_ERROR", "Could not decode image from base64");
                return;
            }

            List<TemplateConfig> configs = parseTemplateConfigsFromBase64(templateConfigs);
            extractDocumentInternal(image, configs, threshold, true, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractDocumentWithPerspectiveCorrectionFromBase64", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Find templates in image (equivalent to Python's find_templates method)
     */
    @ReactMethod
    public void findTemplates(String imagePath, ReadableArray templateConfigs, 
                             double threshold, Promise promise) {
        try {
            Mat image = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) {
                promise.reject("IMAGE_READ_ERROR", "Could not read image from path: " + imagePath);
                return;
            }

            List<TemplateConfig> configs = parseTemplateConfigs(templateConfigs);
            List<TemplateMatch> matches = findTemplatesInternal(image, configs, threshold);
            
            WritableArray result = convertMatchesToWritableArray(matches);
            promise.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Error in findTemplates", e);
            promise.reject("TEMPLATE_FINDING_ERROR", e.getMessage());
        }
    }

    // ========================================
    // HARDCODED TEMPLATE CONFIGURATIONS
    // ========================================

    /**
     * Create hardcoded template configurations using Ashoka Chakra & Emblem
     * Loads images from Android drawable resources
     */
    private List<TemplateConfig> createAadhaarTemplateConfigs() {
        List<TemplateConfig> configs = new ArrayList<>();
        
        try {
            Log.d(TAG, "🔍 Starting to load Aadhaar template configurations...");
            
            // Ashoka Emblem (lions) - typically found on left side of Aadhaar
            try {
                Log.d(TAG, "Loading Ashoka emblem template...");
                Mat emblemTemplate = loadMatFromDrawable("ashoka");
                if (!emblemTemplate.empty()) {
                    Log.d(TAG, String.format("✅ Ashoka emblem loaded: %dx%d", 
                          emblemTemplate.width(), emblemTemplate.height()));
                    configs.add(new TemplateConfig(
                        emblemTemplate,
                        "ashoka_emblem",
                        "top-left",
                        30,    // 30px from left edge
                        20,    // 20px from top edge  
                        0.08,  // 8% of document width
                        0.12   // 12% of document height
                    ));
                } else {
                    Log.w(TAG, "❌ Ashoka emblem template is empty or failed to load");
                }
            } catch (Exception emblemError) {
                Log.e(TAG, "❌ Error loading Ashoka emblem template: " + emblemError.getMessage());
                emblemError.printStackTrace();
            }
            
            // Aadhaar Logo - typically found on right side of Aadhaar
            try {
                Log.d(TAG, "Loading Aadhaar logo template...");
                Mat aadhaarTemplate = loadMatFromDrawable("aadhaarlogo");
                if (!aadhaarTemplate.empty()) {
                    Log.d(TAG, String.format("✅ Aadhaar logo loaded: %dx%d", 
                          aadhaarTemplate.width(), aadhaarTemplate.height()));
                    configs.add(new TemplateConfig(
                        aadhaarTemplate,
                        "aadhaar_logo", 
                        "top-right",
                        150,   // 150px from right edge
                        20,    // 20px from top edge
                        0.06,  // 6% of document width
                        0.10   // 10% of document height
                    ));
                } else {
                    Log.w(TAG, "❌ Aadhaar logo template is empty or failed to load");
                }
            } catch (Exception logoError) {
                Log.e(TAG, "❌ Error loading Aadhaar logo template: " + logoError.getMessage());
                logoError.printStackTrace();
            }
            
            Log.d(TAG, String.format("Template configuration complete: %d templates loaded", configs.size()));
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Critical error creating hardcoded template configs: " + e.getMessage());
            e.printStackTrace();
        }
        
        return configs;
    }

    // ========================================
    // SIMPLIFIED PUBLIC METHODS (No template config needed)
    // ========================================

    /**
     * Extract Aadhaar document using hardcoded templates
     * Just pass image path - templates are built-in!
     */
    @ReactMethod
    public void extractAadhaarDocument(String imagePath, double threshold, Promise promise) {
        try {
            Mat image = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) {
                promise.reject("IMAGE_READ_ERROR", "Could not read image from path: " + imagePath);
                return;
            }

            List<TemplateConfig> configs = createAadhaarTemplateConfigs();
            if (configs.isEmpty()) {
                promise.reject("TEMPLATE_ERROR", "No template images configured. Please replace base64 placeholders.");
                return;
            }

            extractDocumentInternal(image, configs, threshold, false, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractAadhaarDocument", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Extract Aadhaar document from base64 using hardcoded templates
     */
    @ReactMethod
    public void extractAadhaarDocumentFromBase64(String imageBase64, double threshold, Promise promise) {
        try {
            Mat image = base64ToMat(imageBase64);
            if (image.empty()) {
                promise.reject("IMAGE_DECODE_ERROR", "Could not decode image from base64");
                return;
            }

            List<TemplateConfig> configs = createAadhaarTemplateConfigs();
            if (configs.isEmpty()) {
                promise.reject("TEMPLATE_ERROR", "No template images configured. Please replace base64 placeholders.");
                return;
            }

            extractDocumentInternal(image, configs, threshold, false, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractAadhaarDocumentFromBase64", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Extract Aadhaar document from Mat using hardcoded templates
     * @param image OpenCV Mat containing the image
     * @param threshold Matching threshold
     * @param promise Promise to resolve/reject with result
     */
    public void extractAadhaarDocumentFromMat(Mat image, double threshold, Promise promise) {
        try {
            Log.d(TAG, "🔍 extractAadhaarDocumentFromMat called");
            
            if (image == null || image.empty()) {
                Log.e(TAG, "❌ Input Mat is null or empty");
                promise.reject("IMAGE_ERROR", "Input Mat is empty");
                return;
            }
            
            Log.d(TAG, String.format("Input image: %dx%d, channels=%d", 
                  image.width(), image.height(), image.channels()));

            List<TemplateConfig> configs = createAadhaarTemplateConfigs();
            Log.d(TAG, "Template configs created: " + configs.size() + " templates");
            
            if (configs.isEmpty()) {
                Log.w(TAG, "⚠️ No template images configured - returning success without matches");
                // Return success with no matches instead of error to prevent crashes
                WritableMap result = Arguments.createMap();
                result.putNull("extractedDocument");
                result.putArray("matches", Arguments.createArray());
                result.putString("status", "No template images available - skipping template matching");
                promise.resolve(result);
                return;
            }

            Log.d(TAG, "Calling extractDocumentInternal...");
            extractDocumentInternal(image, configs, threshold, false, promise);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in extractAadhaarDocumentFromMat: " + e.getMessage());
            e.printStackTrace();
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Extract Aadhaar document with perspective correction using hardcoded templates
     */
    @ReactMethod
    public void extractAadhaarDocumentWithPerspectiveCorrection(String imagePath, double threshold, Promise promise) {
        try {
            Mat image = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) {
                promise.reject("IMAGE_READ_ERROR", "Could not read image from path: " + imagePath);
                return;
            }

            List<TemplateConfig> configs = createAadhaarTemplateConfigs();
            if (configs.isEmpty()) {
                promise.reject("TEMPLATE_ERROR", "No template images configured. Please replace base64 placeholders.");
                return;
            }

            extractDocumentInternal(image, configs, threshold, true, promise);

        } catch (Exception e) {
            Log.e(TAG, "Error in extractAadhaarDocumentWithPerspectiveCorrection", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    // ========================================
    // CORE INTERNAL METHODS (DocumentExtractor Logic)
    // ========================================

    /**
     * Core extraction logic - similar to Python DocumentExtractor class methods
     */
    private void extractDocumentInternal(Mat image, List<TemplateConfig> configs, 
                                       double threshold, boolean withPerspectiveCorrection, 
                                       Promise promise) {
        try {
            // Step 1: Find template matches (similar to Python find_templates)
            List<TemplateMatch> matches = findTemplatesInternal(image, configs, threshold);
            
            if (matches.isEmpty()) {
                WritableMap result = Arguments.createMap();
                result.putNull("extractedDocument");
                result.putArray("matches", Arguments.createArray());
                result.putString("status", "No templates found");
                promise.resolve(result);
                return;
            }

            // Step 2: Calculate document bounds (similar to Python calculate_document_bounds)
            DocumentBounds bounds = calculateDocumentBounds(matches, image.size());
            
            WritableMap result = Arguments.createMap();
            
            if (bounds != null) {
                Mat extractedDoc;
                
                // Step 3: Extract document
                if (withPerspectiveCorrection && matches.size() >= 4) {
                    extractedDoc = extractWithPerspectiveCorrection(image, matches);
                } else {
                    // Simple extraction
                    Rect docRect = new Rect(bounds.x, bounds.y, bounds.width, bounds.height);
                    extractedDoc = new Mat(image, docRect);
                }
                
                // Convert to base64 for return
                String extractedBase64 = matToBase64(extractedDoc);
                result.putString("extractedDocument", extractedBase64);
                
                // Add bounds information
                WritableMap boundsMap = Arguments.createMap();
                boundsMap.putInt("x", bounds.x);
                boundsMap.putInt("y", bounds.y);
                boundsMap.putInt("width", bounds.width);
                boundsMap.putInt("height", bounds.height);
                result.putMap("documentBounds", boundsMap);
                result.putString("status", "success");
                
            } else {
                result.putNull("extractedDocument");
                result.putString("status", "Could not calculate document bounds");
            }
            
            // Add matches information
            WritableArray matchesArray = convertMatchesToWritableArray(matches);
            result.putArray("matches", matchesArray);
            
            promise.resolve(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in extractDocumentInternal", e);
            promise.reject("EXTRACTION_ERROR", e.getMessage());
        }
    }

    /**
     * Find all template matches in the image
     * Direct equivalent to Python's find_templates method
     */
    private List<TemplateMatch> findTemplatesInternal(Mat image, List<TemplateConfig> configs, double threshold) {
        List<TemplateMatch> matches = new ArrayList<>();
        
        // Convert image to grayscale for template matching
        Mat grayImage = new Mat();
        if (image.channels() == 3) {
            Imgproc.cvtColor(image, grayImage, Imgproc.COLOR_BGR2GRAY);
        } else {
            grayImage = image.clone();
        }
        
        for (TemplateConfig config : configs) {
            Mat template = config.template;
            
            // Ensure template is grayscale
            Mat grayTemplate = new Mat();
            if (template.channels() == 3) {
                Imgproc.cvtColor(template, grayTemplate, Imgproc.COLOR_BGR2GRAY);
            } else {
                grayTemplate = template.clone();
            }
            
            // Perform template matching
            Mat result = new Mat();
            Imgproc.matchTemplate(grayImage, grayTemplate, result, TM_CCOEFF_NORMED);
            
            // Find the best match
            Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(result);
            double maxVal = minMaxResult.maxVal;
            
            if (maxVal >= threshold) {
                Point topLeft = minMaxResult.maxLoc;
                Size templateSize = grayTemplate.size();
                
                matches.add(new TemplateMatch(
                    config.name,
                    config.position,
                    topLeft,
                    templateSize,
                    maxVal,
                    config
                ));
            }
        }
        
        return matches;
    }

    /**
     * Calculate document boundaries from template matches
     * Direct equivalent to Python's calculate_document_bounds method
     */
    private DocumentBounds calculateDocumentBounds(List<TemplateMatch> matches, Size imageSize) {
        if (matches.isEmpty()) {
            return null;
        }
        
        int imgWidth = (int) imageSize.width;
        int imgHeight = (int) imageSize.height;
        
        // Method 1: Single reference point extrapolation (same as Python)
        if (matches.size() == 1) {
            TemplateMatch match = matches.get(0);
            TemplateConfig config = match.config;
            Point matchCoord = match.matchCoord;
            Size templateSize = match.templateSize;
            
            // Calculate document dimensions using known ratios
            int docWidth = (int) (templateSize.width / config.docWidthRatio);
            int docHeight = (int) (templateSize.height / config.docHeightRatio);
            
            int docX, docY;
            
            // Calculate document top-left based on template position
            switch (config.position.toLowerCase()) {
                case "top-left":
                    docX = (int) (matchCoord.x - config.offsetX);
                    docY = (int) (matchCoord.y - config.offsetY);
                    break;
                case "center":
                    docX = (int) (matchCoord.x - docWidth / 2.0);
                    docY = (int) (matchCoord.y - docHeight / 2.0);
                    break;
                case "top-right":
                    docX = (int) (matchCoord.x - docWidth + templateSize.width + config.offsetX);
                    docY = (int) (matchCoord.y - config.offsetY);
                    break;
                default:
                    // Default to top-left assumption
                    docX = (int) (matchCoord.x - config.offsetX);
                    docY = (int) (matchCoord.y - config.offsetY);
                    break;
            }
            
            // Ensure bounds are within image
            docX = Math.max(0, docX);
            docY = Math.max(0, docY);
            docWidth = Math.min(docWidth, imgWidth - docX);
            docHeight = Math.min(docHeight, imgHeight - docY);
            
            return new DocumentBounds(docX, docY, docWidth, docHeight);
            
        } else {
            // Method 2: Multiple reference points triangulation (same as Python)
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = Double.MIN_VALUE;
            double maxY = Double.MIN_VALUE;
            
            for (TemplateMatch match : matches) {
                Point coord = match.matchCoord;
                Size size = match.templateSize;
                
                minX = Math.min(minX, coord.x);
                minY = Math.min(minY, coord.y);
                maxX = Math.max(maxX, coord.x + size.width);
                maxY = Math.max(maxY, coord.y + size.height);
            }
            
            // Add margins based on document structure knowledge (10% margin like Python)
            double marginX = (maxX - minX) * 0.1;
            double marginY = (maxY - minY) * 0.1;
            
            int docX = Math.max(0, (int) (minX - marginX));
            int docY = Math.max(0, (int) (minY - marginY));
            int docWidth = Math.min(imgWidth - docX, (int) (maxX - docX + marginX));
            int docHeight = Math.min(imgHeight - docY, (int) (maxY - docY + marginY));
            
            return new DocumentBounds(docX, docY, docWidth, docHeight);
        }
    }

    /**
     * Extract document with perspective correction
     * Similar to Python's extract_with_perspective_correction method
     */
    private Mat extractWithPerspectiveCorrection(Mat image, List<TemplateMatch> matches) {
        // Use first 4 matches for perspective correction
        List<Point> corners = new ArrayList<>();
        for (int i = 0; i < Math.min(4, matches.size()); i++) {
            Point matchPoint = matches.get(i).matchCoord;
            Size templateSize = matches.get(i).templateSize;
            // Use center of template as corner point
            Point center = new Point(
                matchPoint.x + templateSize.width / 2.0,
                matchPoint.y + templateSize.height / 2.0
            );
            corners.add(center);
        }
        
        // Convert to Mat
        Mat srcPoints = new Mat(4, 1, CvType.CV_32FC2);
        for (int i = 0; i < corners.size(); i++) {
            srcPoints.put(i, 0, corners.get(i).x, corners.get(i).y);
        }
        
        // Define destination points (rectified document) - same as Python
        int docWidth = 800;  // Standard width
        int docHeight = 1100; // Standard height
        
        Mat dstPoints = new Mat(4, 1, CvType.CV_32FC2);
        dstPoints.put(0, 0, 0, 0);
        dstPoints.put(1, 0, docWidth, 0);
        dstPoints.put(2, 0, docWidth, docHeight);
        dstPoints.put(3, 0, 0, docHeight);
        
        // Calculate perspective transform
        Mat transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
        
        // Apply perspective correction
        Mat correctedDoc = new Mat();
        Imgproc.warpPerspective(image, correctedDoc, transformMatrix, new Size(docWidth, docHeight));
        
        return correctedDoc;
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    /**
     * Parse template configurations from file paths
     */
    private List<TemplateConfig> parseTemplateConfigs(ReadableArray templateConfigs) {
        List<TemplateConfig> configs = new ArrayList<>();
        
        for (int i = 0; i < templateConfigs.size(); i++) {
            try {
                ReadableMap config = templateConfigs.getMap(i);
                String templatePath = config.getString("templatePath");
                String name = config.getString("name");
                String position = config.getString("position");
                double offsetX = config.hasKey("offsetX") ? config.getDouble("offsetX") : 0;
                double offsetY = config.hasKey("offsetY") ? config.getDouble("offsetY") : 0;
                double docWidthRatio = config.hasKey("docWidthRatio") ? config.getDouble("docWidthRatio") : 0.1;
                double docHeightRatio = config.hasKey("docHeightRatio") ? config.getDouble("docHeightRatio") : 0.1;
                
                Mat template = Imgcodecs.imread(templatePath, Imgcodecs.IMREAD_COLOR);
                if (!template.empty()) {
                    configs.add(new TemplateConfig(template, name, position, offsetX, offsetY, docWidthRatio, docHeightRatio));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse template config at index " + i, e);
            }
        }
        
        return configs;
    }

    /**
     * Parse template configurations from base64 images
     */
    private List<TemplateConfig> parseTemplateConfigsFromBase64(ReadableArray templateConfigs) {
        List<TemplateConfig> configs = new ArrayList<>();
        
        for (int i = 0; i < templateConfigs.size(); i++) {
            try {
                ReadableMap config = templateConfigs.getMap(i);
                String templateBase64 = config.getString("templateBase64");
                String name = config.getString("name");
                String position = config.getString("position");
                double offsetX = config.hasKey("offsetX") ? config.getDouble("offsetX") : 0;
                double offsetY = config.hasKey("offsetY") ? config.getDouble("offsetY") : 0;
                double docWidthRatio = config.hasKey("docWidthRatio") ? config.getDouble("docWidthRatio") : 0.1;
                double docHeightRatio = config.hasKey("docHeightRatio") ? config.getDouble("docHeightRatio") : 0.1;
                
                Mat template = base64ToMat(templateBase64);
                if (!template.empty()) {
                    configs.add(new TemplateConfig(template, name, position, offsetX, offsetY, docWidthRatio, docHeightRatio));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse template config from base64 at index " + i, e);
            }
        }
        
        return configs;
    }

    /**
     * Convert template matches to WritableArray for React Native
     */
    private WritableArray convertMatchesToWritableArray(List<TemplateMatch> matches) {
        WritableArray matchesArray = Arguments.createArray();
        
        for (TemplateMatch match : matches) {
            WritableMap matchMap = Arguments.createMap();
            matchMap.putString("name", match.name);
            matchMap.putString("position", match.position);
            matchMap.putDouble("confidence", match.confidence);
            
            WritableMap coordMap = Arguments.createMap();
            coordMap.putDouble("x", match.matchCoord.x);
            coordMap.putDouble("y", match.matchCoord.y);
            matchMap.putMap("matchCoord", coordMap);
            
            WritableMap sizeMap = Arguments.createMap();
            sizeMap.putDouble("width", match.templateSize.width);
            sizeMap.putDouble("height", match.templateSize.height);
            matchMap.putMap("templateSize", sizeMap);
            
            matchesArray.pushMap(matchMap);
        }
        
        return matchesArray;
    }

    /**
     * Convert OpenCV Mat to base64 string
     */
    private String matToBase64(Mat mat) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(mat, bitmap);
            
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            
            return Base64.encodeToString(byteArray, Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "Error converting Mat to base64", e);
            return "";
        }
    }

    /**
     * Convert base64 string to OpenCV Mat
     */
    private Mat base64ToMat(String base64String) {
        try {
            byte[] decodedString = Base64.decode(base64String, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
            
            Mat mat = new Mat();
            Utils.bitmapToMat(bitmap, mat);
            
            return mat;
        } catch (Exception e) {
            Log.e(TAG, "Error converting base64 to Mat", e);
            return new Mat();
        }
    }

    /**
     * Load Mat from Android drawable resource
     */
    private Mat loadMatFromDrawable(String drawableName) {
        try {
            Log.d(TAG, "Attempting to load drawable: " + drawableName);
            
            Context context = getReactApplicationContext();
            if (context == null) {
                Log.e(TAG, "❌ React context is null for drawable: " + drawableName);
                return new Mat();
            }
            Log.d(TAG, "✅ React context available for: " + drawableName);
            
            int resourceId = context.getResources().getIdentifier(drawableName, "drawable", context.getPackageName());
            if (resourceId == 0) {
                Log.e(TAG, "❌ Drawable resource not found: " + drawableName + " in package: " + context.getPackageName());
                // List available drawables for debugging
                try {
                    String[] drawables = context.getAssets().list("drawable");
                    Log.d(TAG, "Available drawables: " + java.util.Arrays.toString(drawables));
                } catch (Exception listError) {
                    Log.w(TAG, "Could not list available drawables");
                }
                return new Mat();
            }
            Log.d(TAG, "✅ Resource ID found for " + drawableName + ": " + resourceId);
            
            InputStream inputStream = null;
            Bitmap bitmap = null;
            try {
                inputStream = context.getResources().openRawResource(resourceId);
                Log.d(TAG, "✅ InputStream opened for: " + drawableName);
                
                bitmap = BitmapFactory.decodeStream(inputStream);
                if (bitmap == null) {
                    Log.e(TAG, "❌ Failed to decode bitmap from drawable: " + drawableName);
                    return new Mat();
                }
                Log.d(TAG, String.format("✅ Bitmap decoded for %s: %dx%d", 
                      drawableName, bitmap.getWidth(), bitmap.getHeight()));
                
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Exception closeError) {
                        Log.w(TAG, "Error closing input stream for: " + drawableName);
                    }
                }
            }

            Mat mat = new Mat();
            try {
                Utils.bitmapToMat(bitmap, mat);
                Log.d(TAG, String.format("✅ Mat created for %s: %dx%d, channels=%d", 
                      drawableName, mat.width(), mat.height(), mat.channels()));
                
                // Recycle bitmap to free memory
                bitmap.recycle();
                
                return mat;
            } catch (Exception matError) {
                Log.e(TAG, "❌ Error converting bitmap to Mat for: " + drawableName + " - " + matError.getMessage());
                matError.printStackTrace();
                return new Mat();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Critical error loading Mat from drawable: " + drawableName + " - " + e.getMessage());
            e.printStackTrace();
            return new Mat();
        }
    }

    // ========================================
    // UTILITY METHODS FOR DEBUGGING/TESTING
    // ========================================

    /**
     * Get available template matching methods
     */
    @ReactMethod
    public void getTemplateMatchingMethods(Promise promise) {
        try {
            WritableArray methods = Arguments.createArray();
            
            WritableMap tmCcoeffNormed = Arguments.createMap();
            tmCcoeffNormed.putString("name", "TM_CCOEFF_NORMED");
            tmCcoeffNormed.putInt("value", TM_CCOEFF_NORMED);
            tmCcoeffNormed.putString("description", "Normalized coefficient correlation (recommended)");
            methods.pushMap(tmCcoeffNormed);

            WritableMap tmSqdiffNormed = Arguments.createMap();
            tmSqdiffNormed.putString("name", "TM_SQDIFF_NORMED");
            tmSqdiffNormed.putInt("value", TM_SQDIFF_NORMED);
            tmSqdiffNormed.putString("description", "Normalized squared difference");
            methods.pushMap(tmSqdiffNormed);

            promise.resolve(methods);
        } catch (Exception e) {
            promise.reject("GET_METHODS_ERROR", e.getMessage());
        }
    }

    /**
     * SAFE ALTERNATIVE: Simple document validation without template images
     * This method performs basic document validation without loading external templates
     * to prevent crashes from drawable resource loading issues
     */
    public void validateDocumentSafely(Mat image, double threshold, Promise promise) {
        try {
            Log.d(TAG, "🔍 SAFE: Starting safe document validation...");
            
            if (image == null || image.empty()) {
                Log.e(TAG, "❌ SAFE: Input Mat is null or empty");
                promise.reject("IMAGE_ERROR", "Input Mat is empty");
                return;
            }
            
            Log.d(TAG, String.format("SAFE: Input image: %dx%d, channels=%d", 
                  image.width(), image.height(), image.channels()));

            // Perform basic document validation checks
            boolean isValidDocument = performBasicDocumentValidation(image);
            
            WritableMap result = Arguments.createMap();
            result.putBoolean("isValidDocument", isValidDocument);
            result.putString("status", isValidDocument ? "Document validated" : "Document validation failed");
            result.putArray("matches", Arguments.createArray()); // Empty matches array
            
            if (isValidDocument) {
                // Add basic document bounds (full image for now)
                WritableMap boundsMap = Arguments.createMap();
                boundsMap.putInt("x", 0);
                boundsMap.putInt("y", 0);
                boundsMap.putInt("width", image.width());
                boundsMap.putInt("height", image.height());
                result.putMap("documentBounds", boundsMap);
            }
            
            promise.resolve(result);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ SAFE: Error in safe document validation: " + e.getMessage());
            e.printStackTrace();
            promise.reject("VALIDATION_ERROR", e.getMessage());
        }
    }
    
    /**
     * Perform basic document validation without template matching
     */
    private boolean performBasicDocumentValidation(Mat image) {
        try {
            // Convert to grayscale for analysis
            Mat gray = new Mat();
            if (image.channels() == 3) {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = image.clone();
            }
            
            // Check 1: Image quality (not too blurry)
            boolean goodQuality = checkImageQuality(gray);
            if (!goodQuality) {
                Log.d(TAG, "SAFE: Image quality check failed");
                gray.release();
                return false;
            }
            
            // Check 2: Reasonable aspect ratio for documents
            boolean goodAspectRatio = checkAspectRatio(image);
            if (!goodAspectRatio) {
                Log.d(TAG, "SAFE: Aspect ratio check failed");
                gray.release();
                return false;
            }
            
            // Check 3: Has reasonable edge density (indicates document-like content)
            boolean hasEdges = checkEdgeDensity(gray);
            if (!hasEdges) {
                Log.d(TAG, "SAFE: Edge density check failed");
                gray.release();
                return false;
            }
            
            gray.release();
            Log.d(TAG, "SAFE: All basic validation checks passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "SAFE: Error in basic document validation: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if image has good quality (not too blurry)
     */
    private boolean checkImageQuality(Mat gray) {
        try {
            Mat laplacian = new Mat();
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble stddev = new MatOfDouble();
            Core.meanStdDev(laplacian, mean, stddev);
            
            double variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
            
            laplacian.release();
            
            // Reasonable quality threshold
            boolean goodQuality = variance > 30;
            Log.d(TAG, String.format("SAFE: Image quality variance=%.2f, good=%b", variance, goodQuality));
            
            return goodQuality;
            
        } catch (Exception e) {
            Log.e(TAG, "SAFE: Error checking image quality: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if image has reasonable aspect ratio for documents
     */
    private boolean checkAspectRatio(Mat image) {
        double aspectRatio = (double) image.width() / image.height();
        
        // Documents typically have aspect ratios between 0.5 and 3.0
        boolean goodAspectRatio = aspectRatio >= 0.5 && aspectRatio <= 3.0;
        
        Log.d(TAG, String.format("SAFE: Aspect ratio=%.2f, good=%b", aspectRatio, goodAspectRatio));
        
        return goodAspectRatio;
    }
    
    /**
     * Check if image has reasonable edge density (indicates document-like content)
     */
    private boolean checkEdgeDensity(Mat gray) {
        try {
            Mat edges = new Mat();
            Imgproc.Canny(gray, edges, 50, 150);
            
            int edgePixels = Core.countNonZero(edges);
            int totalPixels = edges.rows() * edges.cols();
            double edgeDensity = (double) edgePixels / totalPixels;
            
            edges.release();
            
            // Documents should have reasonable edge density (not too few, not too many)
            boolean hasEdges = edgeDensity > 0.01 && edgeDensity < 0.3;
            
            Log.d(TAG, String.format("SAFE: Edge density=%.3f, good=%b", edgeDensity, hasEdges));
            
            return hasEdges;
            
        } catch (Exception e) {
            Log.e(TAG, "SAFE: Error checking edge density: " + e.getMessage());
            return false;
        }
    }
} 