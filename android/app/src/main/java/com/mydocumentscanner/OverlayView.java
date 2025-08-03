package com.mydocumentscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import org.opencv.core.Point;
import java.util.List;

public class OverlayView extends View {
    private double scanRegionX = 0;
    private double scanRegionY = 0;
    private double scanRegionWidth = 0;
    private double scanRegionHeight = 0;
    private boolean overlayVisible = true;

    // Document contour overlay
    private List<Point> documentCorners = null;
    private int frameWidth = 0;
    private int frameHeight = 0;

    private final Paint scanRegionPaint;
    private final Paint documentContourPaint;

    public OverlayView(Context context) {
        super(context);
        scanRegionPaint = new Paint();
        documentContourPaint = new Paint();
        init();
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scanRegionPaint = new Paint();
        documentContourPaint = new Paint();
        init();
    }

    public OverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scanRegionPaint = new Paint();
        documentContourPaint = new Paint();
        init();
    }

    private void init() {
        // Scan region paint (green rectangle)
        scanRegionPaint.setColor(Color.GREEN);
        scanRegionPaint.setStyle(Paint.Style.STROKE);
        scanRegionPaint.setStrokeWidth(4);
        scanRegionPaint.setAlpha(150);
        
        // Document contour paint (orange overlay)
        documentContourPaint.setColor(Color.parseColor("#FF6A00")); // Orange color
        documentContourPaint.setStyle(Paint.Style.STROKE);
        documentContourPaint.setStrokeWidth(8);
        documentContourPaint.setAlpha(200);
        
        setWillNotDraw(false);
    }

    public void setOverlayVisible(boolean visible) {
        this.overlayVisible = visible;
        invalidate();
    }

    public void updateScanRegion(double x, double y, double width, double height) {
        this.scanRegionX = x;
        this.scanRegionY = y;
        this.scanRegionWidth = width;
        this.scanRegionHeight = height;
        invalidate();
    }

    /**
     * Update document contours for real-time overlay
     */
    public void updateDocumentContours(List<Point> corners, int frameWidth, int frameHeight) {
        this.documentCorners = corners;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        post(() -> invalidate()); // Ensure invalidate runs on UI thread
    }

    /**
     * Clear document contours overlay
     */
    public void clearDocumentContours() {
        this.documentCorners = null;
        post(() -> invalidate()); // Ensure invalidate runs on UI thread
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (!overlayVisible) {
            return;
        }

        // Draw scan region (green rectangle) - only if no document detected
        if (documentCorners == null && scanRegionWidth > 0 && scanRegionHeight > 0) {
            float left = (float) scanRegionX;
            float top = (float) scanRegionY;
            float right = (float) (scanRegionX + scanRegionWidth);
            float bottom = (float) (scanRegionY + scanRegionHeight);
            canvas.drawRect(left, top, right, bottom, scanRegionPaint);
        }

        // Draw detected document contours (orange overlay)
        if (documentCorners != null && documentCorners.size() == 4) {
            drawDocumentContour(canvas);
        }
    }

    /**
     * Draw the detected document contour as an orange overlay
     */
    private void drawDocumentContour(Canvas canvas) {
        if (documentCorners == null || documentCorners.size() != 4) {
            return;
        }

        try {
            // Get view dimensions
            int viewWidth = getWidth();
            int viewHeight = getHeight();
            
            if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
                return;
            }

            // Calculate scale factors from camera frame to view
            double scaleX = (double) viewWidth / frameWidth;
            double scaleY = (double) viewHeight / frameHeight;

            // Create path for the document contour
            Path path = new Path();
            
            // Convert first corner to view coordinates
            Point firstCorner = documentCorners.get(0);
            float startX = (float) (firstCorner.x * scaleX);
            float startY = (float) (firstCorner.y * scaleY);
            path.moveTo(startX, startY);

            // Draw lines to other corners
            for (int i = 1; i < documentCorners.size(); i++) {
                Point corner = documentCorners.get(i);
                float x = (float) (corner.x * scaleX);
                float y = (float) (corner.y * scaleY);
                path.lineTo(x, y);
            }

            // Close the path back to first corner
            path.lineTo(startX, startY);

            // Draw the contour
            canvas.drawPath(path, documentContourPaint);

            // Draw corner circles for better visibility
            Paint cornerPaint = new Paint();
            cornerPaint.setColor(Color.parseColor("#FF6A00"));
            cornerPaint.setStyle(Paint.Style.FILL);
            cornerPaint.setAlpha(180);

            for (Point corner : documentCorners) {
                float x = (float) (corner.x * scaleX);
                float y = (float) (corner.y * scaleY);
                canvas.drawCircle(x, y, 12, cornerPaint);
            }

        } catch (Exception e) {
            // Ignore drawing errors to prevent crashes
        }
    }
} 