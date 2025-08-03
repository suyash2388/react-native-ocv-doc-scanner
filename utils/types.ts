/**
 * Interfaces for OCR scan results data structure
 */

// Represents the rectangular coordinates of any text element
export interface BoundingBox {
  bottom: number;
  right: number;
  top: number;
  left: number;
}

// Represents a point with x,y coordinates
export interface CornerPoint {
  x: number;
  y: number;
}

// Represents the smallest unit of text with its position
export interface TextElement {
  boundingBox: BoundingBox;
  cornerPoints: CornerPoint[];
  text: string;
}

// Represents a line of text containing multiple elements
export interface Line {
  elements: TextElement[];
  boundingBox: BoundingBox;
  cornerPoints: CornerPoint[];
  text: string;
}

// Represents a block of text containing multiple lines
export interface Block {
  boundingBox: BoundingBox;
  lines: Line[];
  cornerPoints: CornerPoint[];
  text: string;
}

// Main interface for each extracted data object
export interface ExtractedData {
  blocks: Block[];
  text: string;
}

// Type for array of extracted data objects
export type ScanResults = ExtractedData[];

/**
 * Validates if a given object matches the ExtractedData interface structure
 * @param data The object to validate
 * @returns An object containing validation result and any error messages
 */
export function validateExtractedData(data: any): {
  isValid: boolean;
  errors: string[];
} {
  const errors: string[] = [];

  // Check if data is an object
  if (!data || typeof data !== 'object') {
    return {isValid: false, errors: ['Data must be an object']};
  }

  // Check required properties
  if (!Array.isArray(data.blocks)) {
    errors.push('Missing or invalid blocks array');
  }
  if (typeof data.text !== 'string') {
    errors.push('Missing or invalid text property');
  }

  // Validate each block
  if (Array.isArray(data.blocks)) {
    data.blocks.forEach((block: any, blockIndex: number) => {
      if (!block || typeof block !== 'object') {
        errors.push(`Block ${blockIndex} is not an object`);
        return;
      }

      // Validate block properties
      if (!validateBoundingBox(block.boundingBox)) {
        errors.push(`Invalid boundingBox in block ${blockIndex}`);
      }
      if (!Array.isArray(block.cornerPoints)) {
        errors.push(
          `Missing or invalid cornerPoints array in block ${blockIndex}`,
        );
      }
      if (typeof block.text !== 'string') {
        errors.push(`Missing or invalid text property in block ${blockIndex}`);
      }
      if (!Array.isArray(block.lines)) {
        errors.push(`Missing or invalid lines array in block ${blockIndex}`);
      }

      // Validate each line in the block
      if (Array.isArray(block.lines)) {
        block.lines.forEach((line: any, lineIndex: number) => {
          if (!line || typeof line !== 'object') {
            errors.push(
              `Line ${lineIndex} in block ${blockIndex} is not an object`,
            );
            return;
          }

          // Validate line properties
          if (!validateBoundingBox(line.boundingBox)) {
            errors.push(
              `Invalid boundingBox in line ${lineIndex} of block ${blockIndex}`,
            );
          }
          if (!Array.isArray(line.cornerPoints)) {
            errors.push(
              `Missing or invalid cornerPoints array in line ${lineIndex} of block ${blockIndex}`,
            );
          }
          if (typeof line.text !== 'string') {
            errors.push(
              `Missing or invalid text property in line ${lineIndex} of block ${blockIndex}`,
            );
          }
          if (!Array.isArray(line.elements)) {
            errors.push(
              `Missing or invalid elements array in line ${lineIndex} of block ${blockIndex}`,
            );
          }

          // Validate each element in the line
          if (Array.isArray(line.elements)) {
            line.elements.forEach((element: any, elementIndex: number) => {
              if (!element || typeof element !== 'object') {
                errors.push(
                  `Element ${elementIndex} in line ${lineIndex} of block ${blockIndex} is not an object`,
                );
                return;
              }

              // Validate element properties
              if (!validateBoundingBox(element.boundingBox)) {
                errors.push(
                  `Invalid boundingBox in element ${elementIndex} of line ${lineIndex} in block ${blockIndex}`,
                );
              }
              if (!Array.isArray(element.cornerPoints)) {
                errors.push(
                  `Missing or invalid cornerPoints array in element ${elementIndex} of line ${lineIndex} in block ${blockIndex}`,
                );
              }
              if (typeof element.text !== 'string') {
                errors.push(
                  `Missing or invalid text property in element ${elementIndex} of line ${lineIndex} in block ${blockIndex}`,
                );
              }
            });
          }
        });
      }
    });
  }

  return {
    isValid: errors.length === 0,
    errors,
  };
}

/**
 * Helper function to validate BoundingBox structure
 */
function validateBoundingBox(box: any): boolean {
  return (
    box &&
    typeof box === 'object' &&
    typeof box.bottom === 'number' &&
    typeof box.right === 'number' &&
    typeof box.top === 'number' &&
    typeof box.left === 'number'
  );
}

export interface DocumentType {
  id: string;
  name: string;
  width: number; // Standard width in mm
  height: number; // Standard height in mm
  aspectRatio: number;
  description?: string;
}

export const DOCUMENT_TYPES: DocumentType[] = [
  {
    id: 'aadhaar',
    name: 'Aadhaar Card',
    width: 85.6,
    height: 53.98,
    aspectRatio: 1.588,
    description: 'Indian National ID Card',
  },
  {
    id: 'pan',
    name: 'PAN Card',
    width: 85.6,
    height: 53.98,
    aspectRatio: 1.588,
    description: 'Permanent Account Number Card',
  },
  {
    id: 'passport',
    name: 'Passport',
    width: 125,
    height: 88,
    aspectRatio: 1.42,
    description: 'Passport',
  },
  {
    id: 'a4',
    name: 'A4 Document',
    width: 210,
    height: 297,
    aspectRatio: 0.707,
    description: 'Standard A4 Paper',
  },
  {
    id: 'visitCard',
    name: 'Visiting card Document',
    width: 89,
    height: 51,
    aspectRatio: 1.74,
    description: 'Standard Visiting Card',
  },
  {
    id: 'usd',
    name: 'US Dollar',
    width: 156,
    height: 66.3,
    aspectRatio: 2.35,
    description: 'Standard US Dollar',
  },
];
