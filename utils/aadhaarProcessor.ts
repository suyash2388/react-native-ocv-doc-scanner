/* eslint-disable prettier/prettier */
// eslint-disable-next-line prettier/prettier
import { ExtractedData } from './types';

interface ExtractedAadhaarInfo {
  name: string;
  dateOfBirth: string;
  gender: string;
  aadhaarNumber: string;
}

interface TemplateStructure {
  hasLeftVerticalIssueDate: boolean;
  hasTopRightElements: boolean;
  hasCompactLayout: boolean;
  hasWideSpreadLayout: boolean;
  avgBlocksPerLine: number;
  issuePattern: string;
  headerPattern: string;
}

interface TemplateMatchResult {
  templateIndex: number;
  format: string;
  formatId: number;
  score: number;
  extractedData: ExtractedAadhaarInfo;
}

/**
 * Helper function to clean text for analysis
 */
const cleanText = (text: string) =>
  text.toLowerCase().replace(/[^\w\s]/g, '').trim();

/**
 * Helper function to check if text looks like a name
 */
const isNamePattern = (text: string) => {
  // Names typically have 2-4 words, each starting with capital letter in original
  const words = text.trim().split(/\s+/);
  if (words.length < 2 || words.length > 4) return false;

  // Check if words look like names (alphabetic, reasonable length)
  return words.every(
    word =>
      /^[A-Za-z]+$/.test(word) &&
      word.length >= 2 &&
      word.length <= 15 &&
      word[0] === word[0].toUpperCase(), // First letter should be uppercase
  );
};

/**
 * Helper function to extract name from complex text blocks
 */
const extractNameFromText = (text: string) => {
  // Split by lines and look for name patterns
  const lines = text.split('\n');
  for (const line of lines) {
    const trimmedLine = line.trim();
    if (isNamePattern(trimmedLine)) {
      return trimmedLine;
    }

    // Try progressive word combinations from start of line
    const words = trimmedLine.split(/\s+/);
    for (let i = 2; i <= Math.min(4, words.length); i++) {
      const nameCandidate = words.slice(0, i).join(' ');
      if (isNamePattern(nameCandidate)) {
        return nameCandidate;
      }
    }
  }
  return null;
};

/**
 * Extract Aadhaar information from recognized text blocks using pattern recognition
 */
export const extractAadhaarInfo = (
  recognitionResult: ExtractedData,
): ExtractedAadhaarInfo => {
  console.log(
    'Recognition Result:',
    JSON.stringify(recognitionResult, null, 2),
  );

  // Early validation of recognitionResult
  if (!recognitionResult || !recognitionResult.blocks) {
    console.error('Invalid recognition result: blocks array is missing');
    return {
      name: '',
      dateOfBirth: '',
      gender: '',
      aadhaarNumber: '',
    };
  }

  // Improved pattern-based extraction
  const extractedInfo: ExtractedAadhaarInfo = {
    name: '',
    dateOfBirth: '',
    gender: '',
    aadhaarNumber: '',
  };

  console.log('\n ANALYZING BLOCKS FOR PATTERNS:');

  // Process each block with improved pattern recognition
  recognitionResult.blocks.forEach((block, index) => {
    if (!block.text) return;

    console.log(`\nBlock ${index}: "${block.text}"`);
    const text = block.text;
    const cleanedText = cleanText(text);

    // Skip blocks that are obviously not user data
    if (
      cleanedText.includes('government') ||
      cleanedText.includes('proof') ||
      cleanedText.includes('identity') ||
      cleanedText.includes('citizenship') ||
      cleanedText.includes('verification') ||
      cleanedText.includes('authentication') ||
      cleanedText.includes('scanning')
    ) {
      return;
    }

    // DOB extraction with priority for explicit DOB patterns
    if (!extractedInfo.dateOfBirth) {
      // Priority 1: Look for explicit DOB patterns first
      const dobPatterns = [
        /dob[:\s]*(\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{4})/i,
        /date[^:]*birth[:\s]*(\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{4})/i,
      ];

      for (const pattern of dobPatterns) {
        const dobMatch = text.match(pattern);
        if (dobMatch) {
          extractedInfo.dateOfBirth = dobMatch[1];
          console.log(`  ✓ Identified as DOB: ${extractedInfo.dateOfBirth}`);
          break;
        }
      }

      // Priority 2: Only if no explicit DOB found, look for general date patterns
      // but exclude those that contain "issued"
      if (
        !extractedInfo.dateOfBirth &&
        !text.toLowerCase().includes('issued')
      ) {
        const datePattern = /\b(\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{4})\b/;
        const dateMatch = text.match(datePattern);
        if (dateMatch) {
          extractedInfo.dateOfBirth = dateMatch[1];
          console.log(`  ✓ Identified as DOB: ${extractedInfo.dateOfBirth}`);
        }
      }
    }

    // Name extraction with improved logic
    if (!extractedInfo.name) {
      const extractedName = extractNameFromText(text);
      if (extractedName) {
        extractedInfo.name = extractedName;
        console.log(`  ✓ Identified as NAME: ${extractedInfo.name}`);
      }
    }

    // Gender extraction
    if (!extractedInfo.gender) {
      const genderPattern = /\b(male|female|transgender)\b/i;
      const genderMatch = text.match(genderPattern);
      if (genderMatch) {
        extractedInfo.gender =
          genderMatch[1].charAt(0).toUpperCase() +
          genderMatch[1].slice(1).toLowerCase();
        console.log(`  ✓ Identified as GENDER: ${extractedInfo.gender}`);
      }
    }

    // Aadhaar number extraction
    if (!extractedInfo.aadhaarNumber) {
      const aadhaarPattern = /\b(\d{4}\s*\d{4}\s*\d{4})\b/;
      const aadhaarMatch = text.match(aadhaarPattern);
      if (aadhaarMatch) {
        extractedInfo.aadhaarNumber = aadhaarMatch[1];
        console.log(
          `  ✓ Identified as AADHAAR: ${extractedInfo.aadhaarNumber}`,
        );
      }
    }
  });

  return extractedInfo;
};

/**
 * Calculate template match score based on layout and content analysis
 */
const calculateTemplateMatchScore = (
  template: any,
  recognizedBlocks: any[],
  extractedInfo: ExtractedAadhaarInfo,
): number => {
  let templateBlocks;
  if (template.blocks && Array.isArray(template.blocks)) {
    templateBlocks = template.blocks;
  } else if (template.textRecognition && template.textRecognition.blocks) {
    templateBlocks = template.textRecognition.blocks;
  } else {
    return 0;
  }

  let totalScore = 0;
  let maxPossibleScore = 0;

  // Analyze template structure patterns
  const templateStructure: TemplateStructure = {
    hasLeftVerticalIssueDate: false,
    hasTopRightElements: false,
    hasCompactLayout: false,
    hasWideSpreadLayout: false,
    avgBlocksPerLine: 0,
    issuePattern: '',
    headerPattern: '',
  };

  const recognizedStructure: TemplateStructure = {
    hasLeftVerticalIssueDate: false,
    hasTopRightElements: false,
    hasCompactLayout: false,
    hasWideSpreadLayout: false,
    avgBlocksPerLine: 0,
    issuePattern: '',
    headerPattern: '',
  };

  // Analyze template structure
  let templateIssueBlock: any = null;
  let templateGovBlock: any = null;

  templateBlocks.forEach((block: any) => {
    if (!block.boundingBox) return;
    const {left, right, top, bottom} = block.boundingBox;
    const width = right - left;
    const height = bottom - top;

    if (block.blockType === 'issued_on') {
      templateIssueBlock = block;
      // Check if issue date is vertical (height > width) and on left side
      if (height > width && left < 50) {
        templateStructure.hasLeftVerticalIssueDate = true;
      }
      templateStructure.issuePattern = `${left}-${top}-${width}-${height}`;
    }

    if (block.blockType === 'government_name') {
      templateGovBlock = block;
      templateStructure.headerPattern = `${left}-${top}-${width}-${height}`;
    }

    // Check for top-right positioned elements (typical in card format)
    if (top < 100 && left > 500) {
      templateStructure.hasTopRightElements = true;
    }
  });

  // Determine layout type for template
  if (templateIssueBlock && templateGovBlock) {
    const issueWidth =
      templateIssueBlock.boundingBox.right -
      templateIssueBlock.boundingBox.left;
    const issueHeight =
      templateIssueBlock.boundingBox.bottom -
      templateIssueBlock.boundingBox.top;
    const govLeft = templateGovBlock.boundingBox.left;

    // Printout characteristics: vertical issue date on left, government header more centered
    if (
      issueHeight > issueWidth &&
      templateIssueBlock.boundingBox.left < 50 &&
      govLeft > 180
    ) {
      templateStructure.hasWideSpreadLayout = true;
    }
    // Card characteristics: more compact, elements closer together
    else if (govLeft < 250) {
      templateStructure.hasCompactLayout = true;
    }
  }

  // Analyze recognized structure
  let recognizedIssueBlock: any = null;
  let recognizedGovBlock: any = null;

  recognizedBlocks.forEach((block: any) => {
    if (!block.boundingBox || !block.text) return;
    const {left, right, top, bottom} = block.boundingBox;
    const width = right - left;
    const height = bottom - top;
    const text = block.text.toLowerCase();

    // Identify issue date block
    if (
      (text.includes('aadhaar') && text.includes('issued')) ||
      (text.includes('aadhaar') && /\d{4}/.test(text))
    ) {
      recognizedIssueBlock = block;
      if (height > width && left < 50) {
        recognizedStructure.hasLeftVerticalIssueDate = true;
      }
      recognizedStructure.issuePattern = `${left}-${top}-${width}-${height}`;
    }

    // Identify government block
    if (text.includes('government') || text.includes('india')) {
      recognizedGovBlock = block;
      recognizedStructure.headerPattern = `${left}-${top}-${width}-${height}`;
    }

    // Check for elements that might be just date/number on left (card format)
    if (
      left < 50 &&
      /^\d+[A-Z]*\d*$/.test(block.text.trim()) &&
      block.text.length >= 6
    ) {
      // This pattern suggests card format (just date/number on left, not full "Aadhaar issued" text)
      recognizedStructure.hasCompactLayout = true;
    }

    // Check for top-right elements
    if (top < 100 && left > 500) {
      recognizedStructure.hasTopRightElements = true;
    }
  });

  // Determine layout type for recognized
  if (recognizedIssueBlock && recognizedGovBlock) {
    const govLeft = recognizedGovBlock.boundingBox.left;

    // If government text is more to the right and we have vertical issue text, likely printout
    if (recognizedStructure.hasLeftVerticalIssueDate && govLeft > 200) {
      recognizedStructure.hasWideSpreadLayout = true;
    }
  }

  // Calculate layout similarity scores
  let layoutScore = 0;
  maxPossibleScore += 1.0;

  // Key distinguishing features scoring
  if (
    templateStructure.hasLeftVerticalIssueDate ===
    recognizedStructure.hasLeftVerticalIssueDate
  ) {
    layoutScore += 0.25;
  }

  if (
    templateStructure.hasCompactLayout === recognizedStructure.hasCompactLayout
  ) {
    layoutScore += 0.25;
  }

  if (
    templateStructure.hasWideSpreadLayout ===
    recognizedStructure.hasWideSpreadLayout
  ) {
    layoutScore += 0.25;
  }

  if (
    templateStructure.hasTopRightElements ===
    recognizedStructure.hasTopRightElements
  ) {
    layoutScore += 0.25;
  }

  totalScore += layoutScore;

  // Content matching with improved block type detection
  let contentScore = 0;
  maxPossibleScore += 1.0;

  let totalTemplateBlocks = 0;

  templateBlocks.forEach((templateBlock: any) => {
    if (!templateBlock.text || !templateBlock.blockType) return;
    totalTemplateBlocks++;

    let bestMatch = 0;
    recognizedBlocks.forEach((recBlock: any) => {
      if (!recBlock.text) return;

      let blockScore = 0;
      const recognizedText = recBlock.text.toLowerCase();

      // Enhanced block type matching
      switch (templateBlock.blockType) {
        case 'issued_on':
          if (
            recognizedText.includes('aadhaar') ||
            /\d{2}[\/\-\.]\d{2}[\/\-\.]\d{4}/.test(recBlock.text) ||
            /\d{8}/.test(recBlock.text)
          ) {
            blockScore = 0.8;
          }
          break;
        case 'government_name':
          if (
            recognizedText.includes('government') ||
            recognizedText.includes('india')
          ) {
            blockScore = 0.8;
          }
          break;
        case 'user_full_name':
          const words = recBlock.text.trim().split(/\s+/);
          if (
            words.length >= 2 &&
            words.length <= 4 &&
            words.every((word: string) => /^[A-Za-z]+$/.test(word))
          ) {
            blockScore = 0.8;
          }
          break;
        case 'user_dob':
          if (
            recognizedText.includes('dob') ||
            /\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{4}/.test(recBlock.text)
          ) {
            blockScore = 0.9;
          }
          break;
        case 'user_gender':
          if (
            recognizedText.includes('male') ||
            recognizedText.includes('female')
          ) {
            blockScore = 0.9;
          }
          break;
        case 'user_aadhar_no':
          if (/^\d{4}\s*\d{4}\s*\d{4}$/.test(recBlock.text.trim())) {
            blockScore = 0.9;
          }
          break;
        case 'aadhar_info':
          if (
            recognizedText.includes('proof') &&
            recognizedText.includes('identity')
          ) {
            blockScore = 0.7;
          }
          break;
      }

      // Position bonus for similar positioning
      if (templateBlock.boundingBox && recBlock.boundingBox && blockScore > 0) {
        const templateCenterX =
          (templateBlock.boundingBox.left + templateBlock.boundingBox.right) /
          2;
        const templateCenterY =
          (templateBlock.boundingBox.top + templateBlock.boundingBox.bottom) /
          2;
        const recognizedCenterX =
          (recBlock.boundingBox.left + recBlock.boundingBox.right) / 2;
        const recognizedCenterY =
          (recBlock.boundingBox.top + recBlock.boundingBox.bottom) / 2;

        const maxDimension = 800;
        const distanceX =
          Math.abs(templateCenterX - recognizedCenterX) / maxDimension;
        const distanceY =
          Math.abs(templateCenterY - recognizedCenterY) / maxDimension;
        const distance = Math.sqrt(
          distanceX * distanceX + distanceY * distanceY,
        );
        const positionSimilarity = Math.max(0, 1 - distance);

        blockScore = blockScore * 0.8 + positionSimilarity * 0.2;
      }

      bestMatch = Math.max(bestMatch, blockScore);
    });

    contentScore += bestMatch;
  });

  contentScore =
    totalTemplateBlocks > 0 ? contentScore / totalTemplateBlocks : 0;
  totalScore += contentScore;

  // Extraction bonus (lower weight)
  let extractionBonus = 0;
  maxPossibleScore += 0.3;

  if (extractedInfo.name) extractionBonus += 0.08;
  if (extractedInfo.dateOfBirth) extractionBonus += 0.08;
  if (extractedInfo.gender) extractionBonus += 0.07;
  if (extractedInfo.aadhaarNumber) extractionBonus += 0.07;

  totalScore += extractionBonus;

  // Format-specific bonus based on detected patterns
  let formatBonus = 0;
  maxPossibleScore += 0.2;

  const templateFormat =
    template.format ||
    (template.textRecognition && template.textRecognition.format);

  if (
    templateFormat === 'printout' &&
    recognizedStructure.hasWideSpreadLayout
  ) {
    formatBonus += 0.2;
  } else if (
    templateFormat === 'card' &&
    recognizedStructure.hasCompactLayout
  ) {
    formatBonus += 0.2;
  }

  totalScore += formatBonus;

  // Normalize score
  const normalizedScore =
    maxPossibleScore > 0 ? totalScore / maxPossibleScore : 0;

  console.log(`Template Structure: ${JSON.stringify(templateStructure)}`);
  console.log(
    `Recognized Structure: ${JSON.stringify(recognizedStructure)}`,
  );
  console.log(
    `Layout: ${layoutScore.toFixed(3)}, Content: ${contentScore.toFixed(
      3,
    )}, Extraction: ${extractionBonus.toFixed(
      3,
    )}, Format: ${formatBonus.toFixed(3)}`,
  );

  return normalizedScore;
};

/**
 * Find the best matching template for the recognized text blocks
 */
export const findBestTemplateMatch = (
  recognitionResult: ExtractedData,
  extractedInfo: ExtractedAadhaarInfo,
  templates: any[],
): TemplateMatchResult | null => {
  if (!recognitionResult || !recognitionResult.blocks) {
    console.error('Invalid recognition result: blocks array is missing');
    return null;
  }

  console.log('CHECKING', templates.length, 'TEMPLATES FOR FORMAT MATCH:');

  let bestTemplate: any = null;
  let bestScore = 0;
  let bestIndex = -1;

  templates.forEach((template, index) => {
    const templateFormat =
      template.format ||
      (template.textRecognition && template.textRecognition.format);
    const templateFormatId =
      template.formatId ||
      (template.textRecognition && template.textRecognition.formatId);

    console.log(
      `\n  Analyzing Template ${index} (${templateFormat}, FormatId: ${templateFormatId}):`,
    );

    const score = calculateTemplateMatchScore(
      template,
      recognitionResult.blocks,
      extractedInfo,
    );

    console.log(`  Final Score: ${score.toFixed(3)}`);

    if (score > bestScore) {
      bestScore = score;
      bestTemplate = template;
      bestIndex = index;
    }
  });

  if (bestTemplate) {
    const format =
      bestTemplate.format ||
      (bestTemplate.textRecognition && bestTemplate.textRecognition.format);
    const formatId =
      bestTemplate.formatId ||
      (bestTemplate.textRecognition && bestTemplate.textRecognition.formatId);

    console.log('\n BEST MATCHING TEMPLATE:');
    console.log('Template Index:', bestIndex);
    console.log('Format:', format);
    console.log('FormatId:', formatId);
    console.log('Score:', bestScore.toFixed(3));

    return {
      templateIndex: bestIndex,
      format: format || 'unknown',
      formatId: formatId || 0,
      score: bestScore,
      extractedData: extractedInfo,
    };
  }

  return null;
};

/**
 * Complete Aadhaar processing pipeline: extract info and find best template match
 */
export const processAadhaarCard = (
  recognitionResult: ExtractedData,
  templates: any[],
): TemplateMatchResult | null => {
  // Step 1: Extract Aadhaar information using pattern recognition
  const extractedInfo = extractAadhaarInfo(recognitionResult);

  // Step 2: Find the best matching template
  const templateMatchResult = findBestTemplateMatch(
    recognitionResult,
    extractedInfo,
    templates,
  );

  if (templateMatchResult) {
    console.log(
      'Extracted Data:',
      JSON.stringify(templateMatchResult.extractedData, null, 2),
    );
    console.log(JSON.stringify(templateMatchResult.extractedData, null, 2));
    return templateMatchResult;
  }

  console.log('NO AADHAAR DATA EXTRACTED OR TEMPLATE MATCHED');
  return null;
};

export type { ExtractedAadhaarInfo, TemplateMatchResult, TemplateStructure };
