/* eslint-disable prettier/prettier */
/**
 * Template-Driven Generic Document Processor
 * 
 * This processor reads ALL extraction logic directly from templates in scan_results.json:
 * - Each template defines its own regex patterns, confidence scoring, and extraction strategies
 * - No hardcoded document-specific logic - everything comes from templates
 * - Completely generic and extensible through JSON configuration alone
 * 
 * Template Structure (in scan_results.json):
 * {
 *   "blocks": [...],
 *   "extractionRules": {
 *     "blockType": {
 *       "patterns": ["regex1", "regex2"],
 *       "validation": { "minWords": 2, "maxWords": 4 },
 *       "postProcess": { "format": "XXXX XXXX XXXX" },
 *       "confidenceScoring": {
 *         "baseScore": 0.5,
 *         "patternMatchBonus": 0.3,
 *         "validationPassBonus": 0.2
 *       },
 *       "extractionStrategy": {
 *         "type": "multiCapture",
 *         "combineGroups": "spaceDelimited",
 *         "groupSeparator": " "
 *       }
 *     }
 *   },
 *   "format": "Document Type",
 *   "formatId": 1
 * }
 */
import { ExtractedData } from './types';

interface BoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

interface TemplateBlock {
  text: string;
  blockType: string;
  boundingBox: BoundingBox;
}

interface ConfidenceScoring {
  baseScore?: number;
  patternMatchBonus?: number;
  validationPassBonus?: number;
  wordCountBonus?: Record<string, number>;
  digitCountBonus?: Record<string, number>;
  formatMatchBonus?: number;
  exactMatchBonus?: number;
  abbreviationBonus?: number;
  keywordMatchBonus?: number;
  fullMatchBonus?: number;
  lengthBonus?: number;
  dateFormatBonus?: number;
  allCapsBonus?: number;
  commaBonus?: number;
  yearRangeBonus?: number;
  futureYearBonus?: number;
}

interface ExtractionStrategy {
  type: 'singleCapture' | 'multiCapture' | 'fullMatch';
  combineGroups?: 'spaceDelimited' | 'concatenate' | 'dateFormat';
  groupSeparator?: string;
  groupOrder?: string[];
  fallbackToFullMatch?: boolean;
  fallbackToDigitsOnly?: boolean;
  formatLength?: number;
  exactMatch?: boolean;
  standardizeOutput?: boolean;
  cleanAlphabetic?: boolean;
  preserveWhitespace?: boolean;
  preserveFormatting?: boolean;
  preserveRaw?: boolean;
  formatAsDate?: boolean;
}

interface ExtractionRule {
  patterns?: string[];
  validation?: {
    minWords?: number;
    maxWords?: number;
    allowedChars?: string;
    minLength?: number;
    maxLength?: number;
    format?: string;
    minYear?: number;
    maxYear?: number;
    length?: number;
    digitsOnly?: boolean;
    allowedValues?: string[];
    mustContain?: string[];
  };
  postProcess?: {
    removeNoiseWords?: string[];
    capitalizeEachWord?: boolean;
    format?: string;
    formatOutput?: string;
    convertEightDigits?: boolean;
    normalize?: Record<string, string>;
    standardize?: string;
    cleanText?: boolean;
    removeExtraSpaces?: boolean;
    maskPartial?: boolean;
    removeSpaces?: boolean;
  };
  confidenceScoring?: ConfidenceScoring;
  extractionStrategy?: ExtractionStrategy;
}

interface Template {
  blocks?: TemplateBlock[];
  textRecognition?: {
    blocks: TemplateBlock[];
    format?: string;
    formatId?: number;
  };
  extractionRules?: Record<string, ExtractionRule>;
  format?: string;
  formatId?: number;
}

interface RecognizedBlock {
  text: string;
  boundingBox: BoundingBox;
}

interface GenericExtractionResult {
  templateIndex: number;
  format: string;
  formatId: number;
  matchScore: number;
  extractedData: Record<string, string>;
  matchedBlockTypes: string[];
}

interface BlockMatch {
  blockType: string;
  matchedText: string;
  confidence: number;
}

/**
 * Calculate structural similarity between template and recognized blocks
 */
const calculateStructuralSimilarity = (
  templateBlocks: TemplateBlock[],
  recognizedBlocks: RecognizedBlock[]
): number => {
  if (!templateBlocks.length || !recognizedBlocks.length) {return 0;}

  // Calculate layout characteristics
  const getLayoutFeatures = (blocks: Array<{boundingBox: BoundingBox}>) => {
    const avgX = blocks.reduce((sum, block) => sum + (block.boundingBox.left + block.boundingBox.right) / 2, 0) / blocks.length;
    const avgY = blocks.reduce((sum, block) => sum + (block.boundingBox.top + block.boundingBox.bottom) / 2, 0) / blocks.length;

    const leftBlocks = blocks.filter(block => (block.boundingBox.left + block.boundingBox.right) / 2 < avgX).length;
    const topBlocks = blocks.filter(block => (block.boundingBox.top + block.boundingBox.bottom) / 2 < avgY).length;

    return {
      avgX,
      avgY,
      leftRatio: leftBlocks / blocks.length,
      topRatio: topBlocks / blocks.length,
      totalBlocks: blocks.length,
    };
  };

  const templateFeatures = getLayoutFeatures(templateBlocks);
  const recognizedFeatures = getLayoutFeatures(recognizedBlocks);

  // Calculate similarity score
  const positionSimilarity = 1 - Math.abs(templateFeatures.avgX - recognizedFeatures.avgX) / 1000;
  const distributionSimilarity = 1 - Math.abs(templateFeatures.leftRatio - recognizedFeatures.leftRatio);
  const blockCountSimilarity = 1 - Math.abs(templateFeatures.totalBlocks - recognizedFeatures.totalBlocks) / Math.max(templateFeatures.totalBlocks, recognizedFeatures.totalBlocks);

  return (positionSimilarity + distributionSimilarity + blockCountSimilarity) / 3;
};

/**
 * Calculate content similarity using template-specific extraction rules
 */
const calculateContentSimilarity = (
  templateBlocks: TemplateBlock[],
  recognizedBlocks: RecognizedBlock[],
  extractionRules?: Record<string, ExtractionRule>
): number => {
  if (!templateBlocks.length || !recognizedBlocks.length) {return 0;}

  let totalScore = 0;
  let totalBlocks = 0;

  templateBlocks.forEach(templateBlock => {
    if (!templateBlock.blockType) {return;}

    totalBlocks++;
    let bestMatchScore = 0;

    recognizedBlocks.forEach(recognizedBlock => {
      const score = calculateBlockTypeMatchWithRules(templateBlock.blockType, recognizedBlock.text, extractionRules);
      bestMatchScore = Math.max(bestMatchScore, score);
    });

    totalScore += bestMatchScore;
  });

  return totalBlocks > 0 ? totalScore / totalBlocks : 0;
};

/**
 * Calculate blockType match score using template-defined confidence scoring rules
 */
const calculateBlockTypeMatchWithRules = (
  blockType: string, 
  recognizedText: string, 
  extractionRules?: Record<string, ExtractionRule>
): number => {
  if (!recognizedText || !recognizedText.trim()) {return 0;}

  const text = recognizedText.trim();
  const rules = extractionRules?.[blockType];

  if (!rules) {
    // Generic fallback scoring if no rules defined
    const textLength = text.length;
    if (textLength < 2) return 0.1;
    if (textLength > 200) return 0.3;
    return 0.4;
  }

  let confidence = 0;
  let patternMatched = false;

  // Step 1: Get base score from template
  const scoring = rules.confidenceScoring;
  if (scoring?.baseScore) {
    confidence = scoring.baseScore;
  } else {
    confidence = 0.5; // Default base score
  }

  // Step 2: Check pattern matching
  if (rules.patterns) {
    for (const pattern of rules.patterns) {
      try {
        const regex = new RegExp(pattern, 'i');
        if (regex.test(text)) {
          patternMatched = true;
          if (scoring?.patternMatchBonus) {
            confidence += scoring.patternMatchBonus;
          }
          break;
        }
      } catch (e) {
        console.warn(`Invalid regex pattern for ${blockType}:`, pattern);
      }
    }
  }

  // Step 3: Apply template-defined scoring bonuses
  if (scoring) {
    // Word count bonus
    if (scoring.wordCountBonus) {
      const words = text.split(/\s+/).filter(w => w.length > 0);
      const wordCountStr = words.length.toString();
      if (scoring.wordCountBonus[wordCountStr]) {
        confidence += scoring.wordCountBonus[wordCountStr];
      }
    }

    // Digit count bonus
    if (scoring.digitCountBonus) {
      const digits = text.replace(/\D/g, '');
      const digitCountStr = digits.length.toString();
      if (scoring.digitCountBonus[digitCountStr]) {
        confidence += scoring.digitCountBonus[digitCountStr];
      }
    }

    // Various other bonuses based on template rules
    if (scoring.exactMatchBonus && rules.validation?.allowedValues) {
      const hasExactMatch = rules.validation.allowedValues.some(value =>
        text.toUpperCase() === value.toUpperCase()
      );
      if (hasExactMatch) confidence += scoring.exactMatchBonus;
    }

    if (scoring.keywordMatchBonus && rules.validation?.mustContain) {
      const containsAll = rules.validation.mustContain.every(keyword =>
        text.toLowerCase().includes(keyword.toLowerCase())
      );
      if (containsAll) confidence += scoring.keywordMatchBonus;
    }

    if (scoring.lengthBonus && text.length >= (rules.validation?.minLength || 0)) {
      confidence += scoring.lengthBonus;
    }

    if (scoring.allCapsBonus && text === text.toUpperCase() && /[A-Z]/.test(text)) {
      confidence += scoring.allCapsBonus;
    }

    if (scoring.commaBonus && text.includes(',')) {
      confidence += scoring.commaBonus;
    }

    if (scoring.dateFormatBonus && /\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{4}/.test(text)) {
      confidence += scoring.dateFormatBonus;
    }

    if (scoring.formatMatchBonus && patternMatched) {
      confidence += scoring.formatMatchBonus;
    }
  }

  // Step 4: Apply validation-based bonuses
  if (rules.validation && scoring?.validationPassBonus) {
    let validationPassed = true;
    
    const val = rules.validation;
    
    // Check various validation rules
    if (val.minWords || val.maxWords) {
      const words = text.split(/\s+/).filter(w => w.length > 0);
      if (val.minWords && words.length < val.minWords) validationPassed = false;
      if (val.maxWords && words.length > val.maxWords) validationPassed = false;
    }
    
    if (val.minLength && text.length < val.minLength) validationPassed = false;
    if (val.maxLength && text.length > val.maxLength) validationPassed = false;
    if (val.length && text.replace(/\D/g, '').length !== val.length) validationPassed = false;
    
    if (validationPassed) {
      confidence += scoring.validationPassBonus;
    }
  }

  return Math.min(confidence, 1.0);
};

/**
 * Extract and process value using template-defined extraction strategy
 */
const extractValueWithRules = (
  blockType: string, 
  recognizedText: string, 
  extractionRules?: Record<string, ExtractionRule>
): string => {
  const text = recognizedText.trim();
  if (!text) return text;

  const rules = extractionRules?.[blockType];
  if (!rules) return text; // Return raw text if no rules

  let extractedValue = text;

  // Step 1: Pattern extraction using template-defined strategy
  if (rules.patterns && rules.extractionStrategy) {
    const strategy = rules.extractionStrategy;
    
    for (const pattern of rules.patterns) {
      try {
        const regex = new RegExp(pattern, 'i');
        const match = text.match(regex);
        if (match) {
          // Apply extraction strategy from template
          switch (strategy.type) {
            case 'singleCapture':
              if (match[1]) {
                extractedValue = match[1];
              } else if (strategy.fallbackToFullMatch) {
                extractedValue = match[0];
              }
              
              // Apply strategy-specific processing
              if (strategy.cleanAlphabetic) {
                const words = extractedValue.split(/\s+/).filter(word => /^[A-Za-z]+$/.test(word));
                if (words.length >= 2) {
                  extractedValue = words.join(' ');
                }
              }
              break;

            case 'multiCapture':
              if (strategy.combineGroups === 'spaceDelimited' && match.length >= 4 && match[1] && match[2] && match[3]) {
                extractedValue = `${match[1]}${strategy.groupSeparator || ' '}${match[2]}${strategy.groupSeparator || ' '}${match[3]}`;
              } else if (strategy.combineGroups === 'concatenate' && match.length >= 3) {
                const groups = match.slice(1).filter(g => g);
                extractedValue = groups.join(strategy.groupSeparator || '');
              } else if (strategy.combineGroups === 'dateFormat' && match.length >= 4 && match[1] && match[2] && match[3]) {
                extractedValue = `${match[1]}${strategy.groupSeparator || '/'}${match[2]}${strategy.groupSeparator || '/'}${match[3]}`;
              } else if (strategy.fallbackToDigitsOnly) {
                const digits = text.replace(/\D/g, '');
                if (strategy.formatLength && digits.length === strategy.formatLength) {
                  const chunkSize = strategy.formatLength / 3;
                  extractedValue = `${digits.slice(0, chunkSize)}${strategy.groupSeparator || ' '}${digits.slice(chunkSize, chunkSize * 2)}${strategy.groupSeparator || ' '}${digits.slice(chunkSize * 2)}`;
                } else {
                  extractedValue = digits;
                }
              } else if (strategy.fallbackToFullMatch) {
                extractedValue = match[0];
              }
              break;

            case 'fullMatch':
            default:
              extractedValue = match[0];
              break;
          }
          break;
        }
      } catch (e) {
        console.warn(`Invalid regex pattern for ${blockType}:`, pattern);
      }
    }
  }

  // Step 2: Post-processing (using existing logic)
  if (rules.postProcess) {
    const pp = rules.postProcess;
    
    // Remove noise words
    if (pp.removeNoiseWords) {
      pp.removeNoiseWords.forEach(noise => {
        extractedValue = extractedValue.replace(new RegExp(`\\b${noise}\\b`, 'gi'), '').trim();
      });
    }
    
    // Normalize values
    if (pp.normalize) {
      const normalized = pp.normalize[extractedValue.toUpperCase()];
      if (normalized) extractedValue = normalized;
    }
    
    // Standardize output
    if (pp.standardize) {
      extractedValue = pp.standardize;
    }
    
    // Format numbers (Aadhaar, etc.)
    if (pp.format === 'XXXX XXXX XXXX') {
      const digits = extractedValue.replace(/\D/g, '');
      if (digits.length === 12) {
        extractedValue = `${digits.slice(0,4)} ${digits.slice(4,8)} ${digits.slice(8,12)}`;
      }
    }
    
    // Convert 8-digit dates
    if (pp.convertEightDigits && /^\d{8}$/.test(extractedValue.replace(/\D/g, ''))) {
      const digits = extractedValue.replace(/\D/g, '');
      if (digits.length === 8) {
        extractedValue = `${digits.slice(0,2)}/${digits.slice(2,4)}/${digits.slice(4,8)}`;
      }
    }
    
    // Capitalize each word
    if (pp.capitalizeEachWord) {
      extractedValue = extractedValue.replace(/\b\w/g, l => l.toUpperCase());
    }
    
    // Clean text
    if (pp.cleanText) {
      extractedValue = extractedValue
        .replace(/\s+/g, ' ')
        .replace(/[^\w\s.,\-\/]/g, '')
        .trim();
    }
    
    // Remove extra spaces
    if (pp.removeExtraSpaces) {
      extractedValue = extractedValue.replace(/\s+/g, ' ').trim();
    }

    // Remove spaces
    if (pp.removeSpaces) {
      extractedValue = extractedValue.replace(/\s/g, '');
    }
  }

  return extractedValue;
};

/**
 * Find the best matching template based on structural and content similarity
 */
const findBestTemplate = (
  recognitionResult: ExtractedData,
  templates: Template[]
): { template: Template; index: number; score: number } | null => {
  if (!recognitionResult.blocks || !templates.length) {return null;}

  let bestTemplate: Template | null = null;
  let bestIndex = -1;
  let bestScore = 0;

  console.log(`\n EVALUATING ${templates.length} TEMPLATES FOR BEST MATCH:`);

  templates.forEach((template, index) => {
    const templateBlocks = template.blocks || template.textRecognition?.blocks || [];
    if (!templateBlocks.length) {return;}

    const structuralScore = calculateStructuralSimilarity(templateBlocks, recognitionResult.blocks);
    const contentScore = calculateContentSimilarity(templateBlocks, recognitionResult.blocks, template.extractionRules);

    // Weighted combination: 80% structural, 20% content
    const totalScore = (structuralScore * 0.8) + (contentScore * 0.2);

    const format = template.format || template.textRecognition?.format || 'unknown';

    console.log(`Template ${index} (${format}): Structural=${structuralScore.toFixed(3)}, Content=${contentScore.toFixed(3)}, Total=${totalScore.toFixed(3)}`);

    if (totalScore > bestScore) {
      bestScore = totalScore;
      bestTemplate = template;
      bestIndex = index;
    }
  });

  if (bestTemplate) {
    console.log(`\n BEST TEMPLATE: Index ${bestIndex}, Score: ${bestScore.toFixed(3)}`);
    return { template: bestTemplate, index: bestIndex, score: bestScore };
  }

  return null;
};

/**
 * Extract data using the best matching template's blockTypes and extraction rules
 */
const extractDataUsingTemplate = (
  template: Template,
  recognitionResult: ExtractedData
): { extractedData: Record<string, string>; matchedBlockTypes: string[] } => {
  const templateBlocks = template.blocks || template.textRecognition?.blocks || [];
  const extractedData: Record<string, string> = {};
  const matchedBlockTypes: string[] = [];

  console.log('\n🔧 EXTRACTING DATA USING TEMPLATE-DEFINED RULES:');

  // For each blockType in template, find the best matching recognized block
  templateBlocks.forEach(templateBlock => {
    if (!templateBlock.blockType) {return;}

    let bestMatch: BlockMatch | null | any = null;
    let bestScore = 0;

    recognitionResult.blocks.forEach(recognizedBlock => {
      const contentScore = calculateBlockTypeMatchWithRules(
        templateBlock.blockType, 
        recognizedBlock.text, 
        template.extractionRules
      );

      // Add position similarity bonus
      let positionScore = 0;
      if (templateBlock.boundingBox && recognizedBlock.boundingBox) {
        const templateCenterX = (templateBlock.boundingBox.left + templateBlock.boundingBox.right) / 2;
        const templateCenterY = (templateBlock.boundingBox.top + templateBlock.boundingBox.bottom) / 2;
        const recognizedCenterX = (recognizedBlock.boundingBox.left + recognizedBlock.boundingBox.right) / 2;
        const recognizedCenterY = (recognizedBlock.boundingBox.top + recognizedBlock.boundingBox.bottom) / 2;

        const maxDimension = 1000;
        const distanceX = Math.abs(templateCenterX - recognizedCenterX) / maxDimension;
        const distanceY = Math.abs(templateCenterY - recognizedCenterY) / maxDimension;
        const distance = Math.sqrt(distanceX * distanceX + distanceY * distanceY);
        positionScore = Math.max(0, 1 - distance);
      }

      // Combined score: 70% content, 30% position
      const totalScore = (contentScore * 0.7) + (positionScore * 0.3);

      if (totalScore > bestScore && totalScore > 0.1) {
        bestScore = totalScore;
        bestMatch = {
          blockType: templateBlock.blockType,
          matchedText: extractValueWithRules(templateBlock.blockType, recognizedBlock.text, template.extractionRules),
          confidence: totalScore,
        } as BlockMatch;
      }
    });

    if (bestMatch !== null) {
      extractedData[bestMatch.blockType] = bestMatch.matchedText;
      matchedBlockTypes.push(bestMatch.blockType);

      console.log(`  ✨ ${bestMatch.blockType}: "${bestMatch.matchedText}" (confidence: ${bestMatch.confidence.toFixed(3)})`);
    } else {
      console.log(`   ${templateBlock.blockType}: No suitable match found`);
    }
  });

  return { extractedData, matchedBlockTypes };
};

/**
 * Main template-driven document processing function
 * 100% generic - all logic comes from templates
 */
export const processDocumentGeneric = (
  recognitionResult: ExtractedData,
  templates: Template[]
): GenericExtractionResult | null => {
  console.log('\n🚀 STARTING FULLY TEMPLATE-DRIVEN DOCUMENT PROCESSING');

  // Validate input
  if (!recognitionResult || !recognitionResult.blocks || !recognitionResult.blocks.length) {
    console.error(' Invalid recognition result: blocks array is missing or empty');
    return null;
  }

  if (!templates || !templates.length) {
    console.error(' No templates provided');
    return null;
  }

  // Step 1: Find the best matching template
  const bestTemplateMatch = findBestTemplate(recognitionResult, templates);

  if (!bestTemplateMatch) {
    console.log(' No suitable template match found');
    return null;
  }

  // Step 2: Extract data using the template's blockTypes and extraction rules
  const { extractedData, matchedBlockTypes } = extractDataUsingTemplate(
    bestTemplateMatch.template,
    recognitionResult
  );

  const format = bestTemplateMatch.template.format || bestTemplateMatch.template.textRecognition?.format || 'unknown';
  const formatId = bestTemplateMatch.template.formatId || bestTemplateMatch.template.textRecognition?.formatId || 0;

  const result: GenericExtractionResult = {
    templateIndex: bestTemplateMatch.index,
    format,
    formatId,
    matchScore: bestTemplateMatch.score,
    extractedData,
    matchedBlockTypes,
  };

  console.log('\n🎉 FINAL TEMPLATE-DRIVEN EXTRACTION RESULT:');
  console.log(`  📋 Template: ${result.templateIndex} (${result.format})`);
  console.log(`  📊 Match Score: ${result.matchScore.toFixed(3)}`);
  console.log(`  🏷️  Extracted BlockTypes: ${result.matchedBlockTypes.join(', ')}`);
  console.log('  📝 Extracted Data:', JSON.stringify(result.extractedData, null, 2));

  return result;
};

// Export types for template extensions
export type {
  BlockMatch, ConfidenceScoring, ExtractionRule, ExtractionStrategy, GenericExtractionResult, RecognizedBlock, Template, TemplateBlock
};

