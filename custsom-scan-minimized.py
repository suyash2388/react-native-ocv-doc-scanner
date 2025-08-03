import math
import cv2 as cv
import numpy as np

# Document type constants
DOCUMENT_TYPES = [
    {
        'id': 'aadhaar',
        'name': 'Aadhaar Card',
        'width': 85.6,
        'height': 53.98,
        'aspectRatio': 1.588,
        'description': 'Indian National ID Card',
    },
    {
        'id': 'pan',
        'name': 'PAN Card',
        'width': 85.6,
        'height': 53.98,
        'aspectRatio': 1.588,
        'description': 'Permanent Account Number Card',
    },
    {
        'id': 'passport',
        'name': 'Passport',
        'width': 125,
        'height': 88,
        'aspectRatio': 1.42,
        'description': 'Passport',
    },
    {
        'id': 'a4',
        'name': 'A4 Document',
        'width': 210,
        'height': 297,
        'aspectRatio': 0.707,
        'description': 'Standard A4 Paper',
    },
    {
        'id': 'visitCard',
        'name': 'Visiting card Document',
        'width': 89,
        'height': 51,
        'aspectRatio': 1.74,
        'description': 'Standard Visiting Card',
    },
    {
        'id': 'usd',
        'name': 'US Dollar',
        'width': 156,
        'height': 66.3,
        'aspectRatio': 2.35,
        'description': 'Standard US Dollar',
    },
]

def order_points(pts):
    rect = np.zeros((4, 2), dtype="float32")
    
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    
    return rect

def is_skewed(image, angle_threshold=5):
    """
    Returns True if the image is skewed by more than angle_threshold degrees.
    """
    gray = cv.cvtColor(image, cv.COLOR_BGR2GRAY)
    blurred = cv.GaussianBlur(gray, (5, 5), 0)
    edges = cv.Canny(blurred, 50, 150, apertureSize=3)

    lines = cv.HoughLines(edges, 1, np.pi / 180, threshold=100)
    if lines is None:
        # No lines detected, can't determine skew
        return False

    angles = []
    for line in lines:
        rho, theta = line[0]
        angle = (theta * 180 / np.pi) - 90  # Convert to degrees, 0 = vertical
        # Normalize angle to [-90, 90]
        if angle < -45:
            angle += 90
        elif angle > 45:
            angle -= 90
        angles.append(angle)

    if not angles:
        return False

    # Compute the median angle
    median_angle = np.median(angles)
    # If the median angle is greater than the threshold, consider it skewed
    return abs(median_angle) > angle_threshold

def grabcut_improved(image_path, document_type=None, show_steps=True):
    print("Trying Approach 6: GrabCut Imporved + Skewed")
    if document_type:
        print(f"Target document: {document_type['name']} (aspect ratio: {document_type['aspectRatio']})")

    image = cv.imread(image_path)
    if image is None:
        print("Error: Could not load image")
        return None
    orig = image.copy()

    height = 500
    ratio = height / image.shape[0]
    image = cv.resize(image, (int(image.shape[1] * ratio), height))
    display_image = image.copy()

    # 1. Use GrabCut to get black background (mask)
    mask = np.zeros(image.shape[:2], np.uint8)
    rect = (10, 10, image.shape[1] - 20, image.shape[0] - 20)
    # rect = (0, 0, image.shape[1], image.shape[0] - 20)
    bgdModel = np.zeros((1, 65), np.float64)
    fgdModel = np.zeros((1, 65), np.float64)
    cv.grabCut(image, mask, rect, bgdModel, fgdModel, 5, cv.GC_INIT_WITH_RECT)
    grabcut_mask = np.where((mask == 2) | (mask == 0), 0, 1).astype('uint8')
    grabcut_result = image * grabcut_mask[:, :, np.newaxis]
    if show_steps:
        cv.imshow("GrabCut Masked", grabcut_result)
        cv.waitKey(0)

    # 2. Edge detection, contour detection
    grabcut_gray = cv.cvtColor(grabcut_result, cv.COLOR_BGR2GRAY)
    _, grabcut_bin = cv.threshold(grabcut_gray, 10, 255, cv.THRESH_BINARY)

    # Apply morphological operations to clean up torn edges
    kernel = np.ones((3, 3), np.uint8)
    cleaned_bin = cv.morphologyEx(grabcut_bin, cv.MORPH_CLOSE, kernel)
    cleaned_bin = cv.morphologyEx(cleaned_bin, cv.MORPH_OPEN, kernel)

    edges = cv.Canny(cleaned_bin, 75, 200)
    if show_steps:
        cv.imshow("Cleaned Binary", cleaned_bin)
        cv.waitKey(0)
        cv.imshow("Edges after GrabCut", edges)
        cv.waitKey(0)
    contours, _ = cv.findContours(edges.copy(), cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE)
    print('Contours found:', len(contours))
    if show_steps:
        contour_img = np.zeros_like(image)
        cv.drawContours(grabcut_result, contours, -1, (0, 255, 255), 2)
        cv.imshow("Contours", grabcut_result)
        cv.waitKey(0)

    # 3. Detect corner points (find largest 4-point contour)
    screenCnt = None
    contours = sorted(contours, key=cv.contourArea, reverse=True)[:5]
    
    for i, c in enumerate(contours):
        print(f"\n--- Processing Contour {i+1}/{len(contours)} ---")
        hull = cv.convexHull(c)
        cv.drawContours(grabcut_result, [c], -1, (0, 0, 255), 2)
        cv.drawContours(grabcut_result, [hull], -1, (0, 255, 0), 2)
        cv.imshow("Contours + Hull", grabcut_result)
        cv.waitKey(0)

        
        peri = cv.arcLength(hull, True)
        print('arcLength : ', peri)
        approx = cv.approxPolyDP(hull, 0.02 * peri, True)
        print('approxPolyDP length : ', len(approx))
        cv.waitKey(0)
        if len(approx) == 4:
            # Test aspect ratio before accepting this contour
            aspect_ratio = document_type['aspectRatio'] if document_type else None
            temp_warped = four_point_transform(orig, approx.reshape(4, 2) / ratio, aspect_ratio)
            
            # Validate aspect ratio if document type is provided
            aspect_ratio_match = True
            if document_type:
                detected_aspect_ratio = temp_warped.shape[1] / temp_warped.shape[0]  # width/height
                expected_aspect_ratio = document_type['aspectRatio']
                aspect_ratio_tolerance = 0.15  # 15% tolerance for stricter matching
                
                print(f"Contour {i+1} - Detected aspect ratio: {detected_aspect_ratio:.3f}")
                print(f"Contour {i+1} - Expected aspect ratio: {expected_aspect_ratio:.3f}")
                
                if abs(detected_aspect_ratio - expected_aspect_ratio) > aspect_ratio_tolerance:
                    print(f"Contour {i+1} - FAILED: Aspect ratio mismatch! Expected ~{expected_aspect_ratio:.3f}, got {detected_aspect_ratio:.3f}")
                    aspect_ratio_match = False
                else:
                    print(f"Contour {i+1} - SUCCESS: Aspect ratio matches! Detected: {detected_aspect_ratio:.3f}, Expected: {expected_aspect_ratio:.3f}")
                    aspect_ratio_match = True
            
            # Use this contour if aspect ratio matches (or if no document type specified)
            if aspect_ratio_match:
                screenCnt = approx
                break
        
        else:
            print(f"Contour {i+1} - FAILED: Not a 4-point contour")
            # Try boundingRect fallback
            # x, y, w, h = cv.boundingRect(hull)
            # rect_pts = np.array([
            #     [x, y],
            #     [x + w, y],
            #     [x + w, y + h],
            #     [x, y + h]
            # ], dtype="float32")

            rect = cv.minAreaRect(hull)
            rect_pts = cv.boxPoints(rect)

            temp_image = image.copy()
            # Convert rect_pts to proper contour format (reshape to 3D array)
            rect_contour = rect_pts.reshape(-1, 1, 2).astype(np.int32)
            cv.drawContours(temp_image, [rect_contour], -1, (0, 0, 255), 2)
            cv.imshow("BoundingRect", temp_image)
            cv.waitKey(0)

            aspect_ratio = document_type['aspectRatio'] if document_type else None
            temp_warped = four_point_transform(orig, rect_pts / ratio, aspect_ratio)
            aspect_ratio_match = True
            if document_type:
                detected_aspect_ratio = temp_warped.shape[1] / temp_warped.shape[0]  # width/height
                expected_aspect_ratio = document_type['aspectRatio']
                aspect_ratio_tolerance = 0.15  # 15% tolerance for stricter matching
                print(f"BoundingRect {i+1} - Detected aspect ratio: {detected_aspect_ratio:.3f}")
                print(f"BoundingRect {i+1} - Expected aspect ratio: {expected_aspect_ratio:.3f}")
                if abs(detected_aspect_ratio - expected_aspect_ratio) > aspect_ratio_tolerance:
                    print(f"BoundingRect {i+1} - FAILED: Aspect ratio mismatch! Expected ~{expected_aspect_ratio:.3f}, got {detected_aspect_ratio:.3f}")
                    aspect_ratio_match = False
                else:
                    print(f"BoundingRect {i+1} - SUCCESS: Aspect ratio matches! Detected: {detected_aspect_ratio:.3f}, Expected: {expected_aspect_ratio:.3f}")
                    aspect_ratio_match = True
            if aspect_ratio_match:
                screenCnt = rect_pts.reshape(-1, 1, 2).astype(np.int32)
                break

    if screenCnt is not None:
        if show_steps:
            corners_img = image.copy()
            cv.drawContours(corners_img, [screenCnt], -1, (255, 255, 0), 2)
            for idx, pt in enumerate(screenCnt):
                pt = tuple(pt[0])
                cv.circle(corners_img, pt, 8, (0, 0, 255), -1)
                cv.putText(corners_img, chr(65+idx), pt, cv.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 255), 2)
            cv.imshow("Detected Corners", corners_img)
            cv.waitKey(0)
        # 5. Perspective transform to align document
        aspect_ratio = document_type['aspectRatio'] if document_type else None
        warped = four_point_transform(orig, screenCnt.reshape(4, 2) / ratio, aspect_ratio)
        # warped_gray = cv.cvtColor(warped, cv.COLOR_BGR2GRAY)
        # final = cv.adaptiveThreshold(warped_gray, 255, cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 11, 2)
        if show_steps:
            cv.imshow("Scanned - Morph Operations", warped)
            cv.waitKey(0)
        print("Approach 6 SUCCESS - Document detected and processed")
        return warped
    else:
        print("Approach 6 FAILED: No document contour found")
        return None


def process_result(warped_image, show_steps=True):
    warped_gray = cv.cvtColor(warped_image, cv.COLOR_BGR2GRAY)
    final = cv.adaptiveThreshold(warped_gray, 255, cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 11, 2)
    
    if show_steps:
        cv.imshow("Scanned Document", final)
        cv.waitKey(0)
    
    return final

def scan_document_with_comprehensive_fallback(image_path, document_type=DOCUMENT_TYPES[0], show_steps=True):
    print(f"Scanning for document type: {document_type['name']}")
    print(f"Expected aspect ratio: {document_type['aspectRatio']}")
    print(f"Expected dimensions: {document_type['width']}mm x {document_type['height']}mm")
    
    approaches = [
        grabcut_improved
    ]
    
    for i, approach in enumerate(approaches, 1):
        print(f"\n{'='*50}")
        print(f"ATTEMPTING APPROACH {i}")
        print(f"{'='*50}")
        
        try:
            result = approach(image_path, document_type=document_type, show_steps=show_steps)
            if result is not None:
                print(f"\nSUCCESS with Approach {i}!")
                return result
        except Exception as e:
            print(f"Approach {i} failed with error: {e}")
            continue
    
    print("\nALL APPROACHES FAILED!")
    print("No document could be detected with any method.")
    return None

if __name__ == "__main__":
    image_path = "ocr/arun.jpeg"
    # image_path = "ocr/aadhaar_front_1.jpeg"
    # image_path = "ocr/screenshot.png"
    # image_path = "ocr/arun_cropped.jpeg"
    # image_path = "ocr/arun_cropped_2.jpeg"
    # image_path = "ocr/visit-card.jpeg"
    # image_path = "ocr/visit-card-2.jpeg"
    # image_path = "ocr/visit-card-3.jpeg"
    # image_path = "ocr/visit-card-4.jpeg"
    # image_path = "ocr/visit-card-trp.jpeg"
    # image_path = "ocr/cropped-torn.jpeg"
    # image_path = "ocr/torn-skew-1.jpeg"
    # image_path = "ocr/torn-skew-2.jpeg"
    # image_path = "ocr/torn-skew-3.jpeg"
    # image_path = "ocr/torn-skew-4.jpeg"
    # image_path = "ocr/torn-skew-5.jpeg"
    # image_path = "ocr/torn-skew-6.jpeg"
    # image_path = "ocr/dollar_bill.JPG"
    image_path = "ocr/cell_a4_pic.jpg"
    

    result = scan_document_with_comprehensive_fallback(image_path, document_type=DOCUMENT_TYPES[3], show_steps=True)
    
    if result is not None:
        print("Document scanning completed successfully!")
    else:
        print("Document scanning failed with all approaches.")
    
    cv.destroyAllWindows() 