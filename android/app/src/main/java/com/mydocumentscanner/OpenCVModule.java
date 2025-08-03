package com.mydocumentscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

public class OpenCVModule extends ReactContextBaseJavaModule {

    private static final String TAG = "OpenCVModule";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    static {
        if (!OpenCVLoader.initDebug()) {
            // Handle initialization error
            System.out.println("OpenCV initialization failed");
            Log.e("OpenCV", "Unable to load OpenCV");
        } else {
            Log.d("OpenCV", "OpenCV loaded Successfully");
            System.loadLibrary("opencv_java4");
            System.out.println("OpenCV initialized successfully");
        }
    }

    public OpenCVModule(ReactApplicationContext reactContext) {
        super(reactContext);
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "Failed to initialize OpenCV");
        } else {
            Log.d(TAG, "OpenCV initialized successfully");
        }
    }

    @Override
    public String getName() {
        return "OpenCVModule";
    }

    @ReactMethod
    public void scanDocument(String imagePath, Promise promise) {
        try {
            Mat image = Imgcodecs.imread(imagePath);

            // Preprocessing
            Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(image, image, new Size(5, 5), 0);
            Imgproc.Canny(image, image, 75, 200);

            // TODO: Implement contour detection and perspective transform

            // Save the processed image
            String outputPath = imagePath.replace(".jpg", "_scanned.jpg");
            Imgcodecs.imwrite(outputPath, image);

            promise.resolve(outputPath);
        } catch (Exception e) {
            promise.reject("Error scanning document", e);
        }
    }

    @ReactMethod
    public void testOpenCV(Promise promise) {
        try {
            if (!OpenCVLoader.initDebug()) {
                // Handle initialization error
                System.out.println("OpenCV initialization failed");
                Log.e("OpenCV", "Unable to load OpenCV");
                promise.resolve("OpenCV initialization failed");
            } else {
                Log.d("OpenCV", "OpenCV loaded Successfully");
                System.loadLibrary("opencv_java4");
                System.out.println("OpenCV initialized successfully");
                promise.resolve("OpenCV initialized successfully");
            }
        } catch (Exception e) {
            promise.reject("OpenCV Test Error", e);
        }
    }

    @ReactMethod
    public void checkForBlurryImage(String imageAsBase64, Callback errorCallback, Callback successCallback) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDither = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            byte[] decodedString = Base64.decode(imageAsBase64, Base64.DEFAULT);
            Bitmap image = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);


    //      Bitmap image = decodeSampledBitmapFromFile(imageurl, 2000, 2000);
                int l = CvType.CV_8UC1; //8-bit grey scale image
                Mat matImage = new Mat();
                Utils.bitmapToMat(image, matImage);
                Mat matImageGrey = new Mat();
                Imgproc.cvtColor(matImage, matImageGrey, Imgproc.COLOR_BGR2GRAY);

                Bitmap destImage;
                destImage = Bitmap.createBitmap(image);
                Mat dst2 = new Mat();
                Utils.bitmapToMat(destImage, dst2);
                Mat laplacianImage = new Mat();
                dst2.convertTo(laplacianImage, l);
                Imgproc.Laplacian(matImageGrey, laplacianImage, CvType.CV_8U);
                Mat laplacianImage8bit = new Mat();
                laplacianImage.convertTo(laplacianImage8bit, l);

                Bitmap bmp = Bitmap.createBitmap(laplacianImage8bit.cols(), laplacianImage8bit.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(laplacianImage8bit, bmp);
                int[] pixels = new int[bmp.getHeight() * bmp.getWidth()];
                bmp.getPixels(pixels, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
                int maxLap = -16777216; // 16m
                for (int pixel : pixels) {
                    if (pixel > maxLap)
                        maxLap = pixel;
                }

    //            int soglia = -6118750;
            int soglia = -8118750;
            if (maxLap <= soglia) {
                System.out.println("is blur image");
            }

            successCallback.invoke(maxLap <= soglia);
        } catch (Exception e) {
            errorCallback.invoke(e.getMessage());
        }
    }
}
