# Template Matching Module for React Native

This module provides OpenCV template matching functionality for React Native Android applications.

## Features

- Single template matching with multiple algorithms
- Batch processing with all matching methods
- Multiple template detection with thresholds
- Support for both file paths and base64 encoded images
- Complete result metadata including confidence scores and bounding boxes

## Available Methods

- **TM_CCOEFF (0)**: Coefficient correlation
- **TM_CCOEFF_NORMED (1)**: Normalized coefficient correlation  
- **TM_CCORR (2)**: Cross correlation
- **TM_CCORR_NORMED (3)**: Normalized cross correlation
- **TM_SQDIFF (4)**: Squared difference
- **TM_SQDIFF_NORMED (5)**: Normalized squared difference

## API Reference

### `matchTemplate(imagePath, templatePath, method)`

Performs template matching on a single image with specified method.

**Parameters:**
- `imagePath` (string): Path to the main image
- `templatePath` (string): Path to the template image
- `method` (number): Template matching method (0-5)

**Returns:**
```javascript
{
  confidence: number,
  minVal: number,
  maxVal: number,
  topLeft: {x: number, y: number},
  bottomRight: {x: number, y: number},
  boundingBox: {x: number, y: number, width: number, height: number}
}
```

### `matchTemplateFromBase64(imageBase64, templateBase64, method)`

Same as `matchTemplate` but accepts base64 encoded images.

### `matchTemplateAllMethods(imagePath, templatePath)`

Tests all available template matching methods.

**Returns:** Array of results with method information.

### `findMultipleMatches(imagePath, templatePath, method, threshold)`

Finds multiple occurrences of a template in an image.

**Parameters:**
- `threshold` (number): Confidence threshold for matches

**Returns:** Array of match objects with coordinates and confidence.

### `getTemplateMatchingMethods()`

Returns available template matching methods with descriptions.

### `matchTemplateReturnJSON(imagePath, templatePath, method)`

Same as `matchTemplate` but returns the result as a JSON string instead of a JavaScript object.

**Returns:** JSON string with the same structure as `matchTemplate`.

### `matchTemplateAllMethodsReturnJSON(imagePath, templatePath)`

Same as `matchTemplateAllMethods` but returns the results as a JSON string instead of a JavaScript array.

**Returns:** JSON array string with all method results.

## Usage Examples

### Basic Template Matching

```javascript
import {NativeModules} from 'react-native';
const {TemplateMatchingModule} = NativeModules;

// Single template matching
const result = await TemplateMatchingModule.matchTemplate(
  '/path/to/image.jpg',
  '/path/to/template.jpg',
  5 // TM_CCOEFF_NORMED
);

console.log('Match confidence:', result.confidence);
console.log('Bounding box:', result.boundingBox);
```

### Find Multiple Matches

```javascript
// Find all matches above 80% confidence
const matches = await TemplateMatchingModule.findMultipleMatches(
  '/path/to/image.jpg',
  '/path/to/template.jpg',
  5, // TM_CCOEFF_NORMED
  0.8 // 80% threshold
);

matches.forEach((match, index) => {
  console.log(`Match ${index + 1}:`, match.confidence);
});
```

### Compare All Methods

```javascript
// Test all matching methods
const allResults = await TemplateMatchingModule.matchTemplateAllMethods(
  '/path/to/image.jpg',
  '/path/to/template.jpg'
);

allResults.forEach(result => {
  console.log(`${result.method}: ${result.confidence}`);
});
```

### Base64 Images

```javascript
// Use with base64 encoded images
const result = await TemplateMatchingModule.matchTemplateFromBase64(
  imageBase64String,
  templateBase64String,
  5
);
```

### JSON String Results

```javascript
// Get result as JSON string
const jsonResult = await TemplateMatchingModule.matchTemplateReturnJSON(
  '/path/to/image.jpg',
  '/path/to/template.jpg',
  5
);

const resultObject = JSON.parse(jsonResult);
console.log('Confidence:', resultObject.confidence);

// Get all methods results as JSON string
const allMethodsJson = await TemplateMatchingModule.matchTemplateAllMethodsReturnJSON(
  '/path/to/image.jpg',
  '/path/to/template.jpg'
);

const allMethodsArray = JSON.parse(allMethodsJson);
allMethodsArray.forEach(result => {
  console.log(`${result.method}: ${result.confidence}`);
});
```

## Integration Tips

1. **Method Selection**: 
   - Use `TM_CCOEFF_NORMED` or `TM_CCORR_NORMED` for most cases
   - Values closer to 1.0 indicate better matches for normalized methods
   - For `TM_SQDIFF` methods, lower values indicate better matches

2. **Image Preprocessing**:
   - Convert images to grayscale for better performance
   - Ensure template size is smaller than the search image

3. **Performance**:
   - Template matching is CPU intensive
   - Use smaller images when possible
   - Consider using background processing for large images

4. **Threshold Tuning**:
   - Start with 0.7-0.8 for normalized methods
   - Adjust based on your specific use case and accuracy requirements

## Error Handling

The module provides detailed error messages for common issues:
- `IMAGE_READ_ERROR`: Could not read the main image
- `TEMPLATE_READ_ERROR`: Could not read the template image
- `IMAGE_DECODE_ERROR`: Could not decode base64 image
- `TEMPLATE_MATCHING_ERROR`: Error during template matching process

## Installation

The module is automatically registered when you add the `TemplateMatchingPackage` to your `MainApplication.java`. Make sure OpenCV is properly initialized in your Android project. 