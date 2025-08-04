/* eslint-disable prettier/prettier */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Dimensions,
  Image,
  Modal,
  NativeEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import { Picker } from '@react-native-picker/picker';
import CameraView from './CameraView';
import ManualCropOverlay from './ManualCropOverlay';
import {
  ExtractedAadhaarInfo,
} from './utils/aadhaarProcessor';
import { processDocumentGeneric } from './utils/genericDocumentProcessor';
import { DOCUMENT_TYPES, DocumentType, ExtractedData } from './utils/types';

const {TextRecognitionModule, FaceDetectionModule} = NativeModules;

interface CameraViewWithScanning extends CameraView {
  pauseScanning: () => void;
  resumeScanning: () => void;
  setExpectedRatio: (aspectRatio: number, documentType: string) => void;
}

const {width: windowWidth, height: windowHeight} = Dimensions.get('window');

const scanResults = require('./logs/scan_results.json');

// Generic document processing function
const processGenericDocument = async (recognitionResult: ExtractedData) => {
  return processDocumentGeneric(recognitionResult, scanResults);
};

const App = () => {
  const [selectedDocType, setSelectedDocType] = useState<DocumentType>(DOCUMENT_TYPES[0]);
  const [showCamera, setShowCamera] = useState(false);
  const [feedbackMessage, setFeedbackMessage] = useState('');
  const [croppedImage, setCroppedImage] = useState<any>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [documentDimensions, setDocumentDimensions] = useState<{
    width: number;
    height: number;
  } | null>(null);
  const [croppedDimensions, setCroppedDimensions] = useState<{
    width: number;
    height: number;
  } | null>(null);
  const [faceData, setFaceData] = useState<any>(null);
  const [faceImage, setFaceImage] = useState<string | null>(null);
  const [showResultModal, setShowResultModal] = useState(false);
  const [resultText, setResultText] = useState('');
  const [_extractedAadhaarData, setExtractedAadhaarData] = useState<ExtractedAadhaarInfo | null>(null);
  const [_templateMatchResult, setTemplateMatchResult] = useState<any>(null);
  const [showManualCrop, setShowManualCrop] = useState(false);
  const [manualCropData, setManualCropData] = useState<{
    frameImage: string;
    frameWidth: number;
    frameHeight: number;
  } | null>(null);


  const cameraViewRef = useRef<CameraViewWithScanning | null>(null);

  // Helper function to calculate GCD (Greatest Common Divisor)
  const getGCD = (a: number, b: number): number => {
    return b === 0 ? a : getGCD(b, a % b);
  };

  // Helper function to get simplified ratio
  const getSimplifiedRatio = (width: number, height: number): string => {
    const gcd = getGCD(width, height);
    return `${width / gcd}:${height / gcd}`;
  };

  // Custom permission request function for Android
  const requestAndroidPermissions = async () => {
    if (Platform.OS !== 'android') {
      // For iOS, permissions are handled in Info.plist, just open camera
      setShowCamera(true);
      return;
    }

    try {
      // Check if we already have camera permission (most important one)
      const cameraPermission = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.CAMERA);
      console.log('Camera permission already granted:', cameraPermission);

      if (cameraPermission) {
        console.log('Camera permission already exists, opening camera...');
        setShowCamera(true);
        return;
      }

      // Only request essential permissions for document scanning
      const permissions = [
        PermissionsAndroid.PERMISSIONS.CAMERA,
        PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
      ];

      // Only request WRITE_EXTERNAL_STORAGE on older Android versions
      if (Platform.Version < 33) {
        permissions.push(PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE);
      }

      console.log('Requesting permissions:', permissions);
      const granted = await PermissionsAndroid.requestMultiple(permissions);
      console.log('Permission results:', granted);

      // Check if camera permission is granted (most critical)
      const cameraGranted = granted[PermissionsAndroid.PERMISSIONS.CAMERA] === PermissionsAndroid.RESULTS.GRANTED;
      const readStorageGranted = granted[PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE] === PermissionsAndroid.RESULTS.GRANTED;

      console.log('Camera granted:', cameraGranted);
      console.log('Read storage granted:', readStorageGranted);

      // Camera is the most important permission - we can work without storage permissions
      if (cameraGranted) {
        console.log('Camera permission granted, opening camera...');
        setShowCamera(true);
      } else {
        console.log('Camera permission denied, showing alert...');
        Alert.alert(
          'Camera Permission Required',
          'This app needs camera access to scan documents. Please grant camera permission to continue.',
          [
            {
              text: 'Cancel',
              style: 'cancel',
            },
            {
              text: 'Try Again',
              onPress: requestAndroidPermissions,
            },
            {
              text: 'Settings',
              onPress: () => {
                Alert.alert(
                  'Open Settings',
                  'Please go to Settings > Apps > MyDocumentScanner > Permissions and enable Camera permission.',
                  [{ text: 'OK' }]
                );
              },
            },
          ]
        );
      }
    } catch (error) {
      console.error('Permission request error:', error);
      Alert.alert(
        'Permission Error',
        'Failed to request permissions. Please try again or check your device settings.',
        [
          {
            text: 'OK',
            onPress: () => {},
          },
        ]
      );
    }
  };

  useEffect(() => {
    const eventEmitter = new NativeEventEmitter(NativeModules.CameraView);
    const eventListener = eventEmitter.addListener(
      'DocumentDetected',
      event => {
        console.log('DocumentDetected Event : ', event);
        if (event.corners) {
          if (event.frameWidth && event.frameHeight) {
            setDocumentDimensions({
              width: event.frameWidth,
              height: event.frameHeight,
            });
          }
          if (event.croppedImage) {
            const imageUri = `data:image/png;base64,${event.croppedImage}`;
            setCroppedImage(imageUri);

            // Get cropped image dimensions using React Native's Image.getSize
            Image.getSize(
              imageUri,
              (width, height) => {
                setCroppedDimensions({
                  width,
                  height,
                });
              },
              error => {
                console.error('Error getting image dimensions:', error);
              },
            );
          }
        } else {
          setCroppedImage(null);
          setCroppedDimensions(null);
        }
      },
    );
    const feedbackListener = eventEmitter.addListener('onFeedback', event => {
      setFeedbackMessage(event.feedbackMessage);
    });

    return () => {
      eventListener.remove();
      feedbackListener.remove();
    };
  }, []);

  // Update scan region when needed
  const updateScanRegion = useCallback(() => {
    console.log('🎯 updateScanRegion called with conditions:', {
      hasCameraRef: !!cameraViewRef.current,
      showCamera,
      noCroppedImage: !croppedImage,
      selectedDocType: selectedDocType.name,
    });

    if (cameraViewRef.current && showCamera && !croppedImage) {
      try {
        // Set the expected aspect ratio for the selected document type
        // The native code will handle rectangle calculation and drawing
        console.log('📞 About to call setExpectedRatio with:', {
          aspectRatio: selectedDocType.aspectRatio,
          documentType: selectedDocType.name,
        });

        cameraViewRef.current.setExpectedRatio(
          selectedDocType.aspectRatio,
          selectedDocType.name
        );

        console.log('✅ setExpectedRatio call completed');
        console.log('✅ Rectangle overlay coordinates will be sent to React Native');

      } catch (error) {
        console.error('❌ Error in updateScanRegion:', error);
      }
    } else {
      console.log('⚠️ setExpectedRatio not called - conditions not met:', {
        hasCameraRef: !!cameraViewRef.current,
        showCamera,
        noCroppedImage: !croppedImage,
      });
    }
  }, [showCamera, croppedImage, selectedDocType]);

  // Update scan region when camera is ready
  useEffect(() => {
    if (showCamera) {
      // Wait for camera view to be ready
      const timer = setTimeout(() => {
        console.log('🎥 Camera view should be ready, updating scan region...');
        updateScanRegion();
      }, 1000); // Give camera 1 second to initialize

      return () => clearTimeout(timer);
    }
  }, [showCamera, updateScanRegion]);

  // Update scan region when document type changes
  useEffect(() => {
    if (showCamera) {
      updateScanRegion();
    }
  }, [showCamera, selectedDocType, updateScanRegion]);

  // Handle scanning state
  useEffect(() => {
    if (cameraViewRef.current) {
      if (croppedImage) {
        cameraViewRef.current.pauseScanning();
      } else {
        cameraViewRef.current.resumeScanning();
        // Small delay to ensure camera is resumed before setting scan region
        setTimeout(() => {
          updateScanRegion();
        }, 100);
      }
    }
  }, [croppedImage, updateScanRegion]);

  const handleRetake = () => {
    setCroppedImage(null);
    setFaceData(null);
    setFaceImage(null);
    setShowResultModal(false);
    setResultText('');
    setExtractedAadhaarData(null);
    setTemplateMatchResult(null);
  };

  // Handle real-time document detection events
  const handleDocumentDetected = useCallback((event: any) => {
    console.log('📄 Document detected in App:', event);
    
    if (event.corners && event.croppedImage) {
      console.log('✅ Document successfully detected with', event.corners.length, 'corners');
      
      // Auto-process the detected document without cluttering feedback
      processDetectedDocument(event.croppedImage, event.frameWidth, event.frameHeight);
    }
  }, []);

  const handleDocumentContoursDetected = useCallback((event: any) => {
    console.log('🔲 Document contours detected in App:', event);
    // Remove feedback messages - let the visual overlay do the work
  }, []);

  const handleManualCropNeeded = useCallback((event: any) => {
    console.log('🔧 Manual crop needed in App:', event);
    setManualCropData({
      frameImage: event.frameImage,
      frameWidth: event.frameWidth,
      frameHeight: event.frameHeight,
    });
    setShowManualCrop(true);
  }, []);

  const handleManualCropConfirm = useCallback((cornerPoints: number[], frameWidth: number, frameHeight: number) => {
    console.log('✅ Manual crop confirmed:', cornerPoints);
    
    if (cameraViewRef.current) {
      cameraViewRef.current.processManualCrop(cornerPoints, frameWidth, frameHeight);
    }
    
    setShowManualCrop(false);
    setManualCropData(null);
  }, []);

  const handleManualCropCancel = useCallback(() => {
    console.log('❌ Manual crop cancelled');
    setShowManualCrop(false);
    setManualCropData(null);
  }, []);

  const processDetectedDocument = async (croppedImageBase64: string, frameWidth: number, frameHeight: number) => {
    try {
      setIsLoading(true);
      
      // Convert base64 to proper data URI format
      const imageUri = `data:image/png;base64,${croppedImageBase64}`;
      console.log('🖼️ Setting cropped image URI length:', imageUri.length);
      console.log('🖼️ Base64 prefix:', croppedImageBase64.substring(0, 50));
      setCroppedImage(imageUri);
      setDocumentDimensions({
        width: frameWidth,
        height: frameHeight
      });
      
      console.log('📸 Document processed and ready for analysis');
      
    } catch (error) {
      console.error('Error processing detected document:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleProceed = async () => {
    console.log('handleProceed Started');
    try {
      if (!croppedImage) {
        Alert.alert('Error', 'No image to proceed with');
        return;
      }

      setIsLoading(true);

      // Remove data URI prefix if present
      const croppedImageBase64 = croppedImage.replace(
        /^data:image\/\w+;base64,/,
        '',
      );

      // Use ML Kit for text recognition and face detection
      TextRecognitionModule.recognizeText(
        croppedImageBase64,
        async (result: ExtractedData) => {
          console.log('Recognized Text New:', JSON.stringify(result));

          // Use the generic processing function to get all results
          const genericResult = await processGenericDocument(result);
          if (genericResult) {
            // Store the generic result directly - no hardcoded mapping
            setExtractedAadhaarData(null); // Clear old aadhaar-specific data
            setTemplateMatchResult(genericResult);
            console.log('Complete Generic Processing Result:', {
              extractedData: genericResult.extractedData,
              matchedBlockTypes: genericResult.matchedBlockTypes,
              format: genericResult.format,
              formatId: genericResult.formatId,
              score: genericResult.matchScore,
            });
          }
          // After text recognition, perform face detection
          FaceDetectionModule.detectFace(
            croppedImageBase64,
            (faceResult: any) => {
              setFaceData(faceResult);
              if (faceResult?.faceImage) {
                setFaceImage(`data:image/jpeg;base64,${faceResult?.faceImage}`);
              }

              // Set result text and show modal
              setResultText(
                `Text Recognition:\n${
                  result.text
                }`,
              );
              setShowResultModal(true);
              setIsLoading(false);
            },
            (error: string) => {
              console.error('Face Detection Error:', error);
              Alert.alert('Face Detection Error', error);
              setIsLoading(false);
            },
          );
        },
        (error: string) => {
          console.error('Text Recognition Error:', error);
          Alert.alert('Error', 'Failed to recognize text: ' + error);
          setIsLoading(false);
        },
      );
    } catch (error) {
      console.error('handleProceed Error:', error);
      Alert.alert('Error', 'An error occurred during processing');
      setIsLoading(false);
    }
  };

  if (!showCamera) {
    return (
      <View style={styles.container}>
        <View style={styles.documentSelectionContainer}>
          <Text style={styles.selectionTitle}>Select Document Type</Text>
          <View style={styles.pickerContainer}>
            <Picker
              selectedValue={selectedDocType.id}
              style={styles.picker}
              onValueChange={(itemValue: string) => {
                const docType = DOCUMENT_TYPES.find(doc => doc.id === itemValue);
                if (docType) {
                  setSelectedDocType(docType);
                }
              }}>
              {DOCUMENT_TYPES.map(docType => (
                <Picker.Item
                  key={docType.id}
                  label={docType.name}
                  value={docType.id}
                />
              ))}
            </Picker>
          </View>
          {selectedDocType.description && (
            <Text style={styles.docDescription}>{selectedDocType.description}</Text>
          )}
          <Text style={{marginBottom: 10}}>
            Standard Size: {selectedDocType.width}mm x {selectedDocType.height}mm
          </Text>
          <Text style={styles.docDimensions}>
            Aspect Ratio: {selectedDocType.aspectRatio}
          </Text>
          <TouchableOpacity
            style={styles.startButton}
            onPress={requestAndroidPermissions}>
            <Text style={styles.startButtonText}>Start Scanning</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {!croppedImage && (
        <>
          <CameraView 
            style={styles.cameraView} 
            ref={cameraViewRef}
            onDocumentDetected={handleDocumentDetected}
            onDocumentContoursDetected={handleDocumentContoursDetected}
            onManualCropNeeded={handleManualCropNeeded}
          />

          <View style={styles.instructionContainer}>
            <Text style={styles.instructionText}>
              Position document in camera view
            </Text>
          </View>
          <TouchableOpacity
            style={styles.backButton}
            onPress={() => setShowCamera(false)}>
            <Text style={styles.backButtonText}>← Back</Text>
          </TouchableOpacity>
        </>
      )}
      {croppedImage && (
        <>
          <Image
            source={{uri: croppedImage}}
            style={styles.croppedImage}
            resizeMode="contain"
          />
          {documentDimensions && (
            <View style={styles.dimensionsContainer}>
              <Text style={styles.dimensionsText}>
                Frame Dimensions: {documentDimensions.width}x
                {documentDimensions.height}
              </Text>
              <Text style={styles.dimensionsText}>
                Frame Aspect Ratio:{' '}
                {getSimplifiedRatio(
                  documentDimensions.width,
                  documentDimensions.height,
                )}
              </Text>
              {croppedDimensions && (
                <>
                  <Text style={styles.dimensionsText}>
                    Cropped Dimensions: {croppedDimensions.width}x
                    {croppedDimensions.height}
                  </Text>
                  <Text style={styles.dimensionsText}>
                    Cropped Aspect Ratio:{' '}
                    {getSimplifiedRatio(
                      croppedDimensions.width,
                      croppedDimensions.height,
                    )}
                  </Text>
                </>
              )}
            </View>
          )}
        </>
      )}
      {isLoading && (
        <View style={styles.loaderContainer}>
          <ActivityIndicator size="large" color="#0000ff" />
          <Text style={styles.loaderText}>Processing...</Text>
        </View>
      )}
      {croppedImage && !isLoading && (
        <View style={styles.buttonContainer}>
          <TouchableOpacity style={styles.button} onPress={handleRetake}>
            <Text style={styles.buttonText}>Re-take</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.button} onPress={handleProceed}>
            <Text style={styles.buttonText}>Proceed</Text>
          </TouchableOpacity>
        </View>
      )}
      {!croppedImage && feedbackMessage !== '' && (
        <View style={styles.feedbackContainer}>
          <Text style={styles.feedbackText}>
            {feedbackMessage}
          </Text>
        </View>
      )}

      <Modal
        animationType="slide"
        transparent={true}
        visible={showResultModal}
        onRequestClose={() => setShowResultModal(false)}>
        <View style={styles.modalContainer}>
          <View style={styles.modalContent}>
            <ScrollView style={styles.modalScrollView}>
              {/* <View style={styles.modalHeader}>
                <Text style={styles.modalTitle}>Document Analysis Results</Text>
              </View> */}
              <View style={styles.modalBody}>
                {faceImage && (
                  <View style={styles.faceImageContainer}>
                    <Text style={styles.sectionTitle}>Detected Face</Text>
                    <Image
                      source={{uri: faceImage}}
                      style={styles.modalFaceImage}
                      // resizeMode="contain"
                    />
                  </View>
                )}
                <View style={styles.textResultsContainer}>
                  {_templateMatchResult && _templateMatchResult.extractedData && (
                    <>
                      <Text style={styles.sectionTitle}>Extracted Document Information</Text>
                      <View style={styles.aadhaarDataContainer}>
                        {Object.entries(_templateMatchResult.extractedData).map(([blockType, value]) => (
                          <View key={blockType} style={styles.aadhaarDataItem}>
                            <Text style={styles.aadhaarLabel}>{blockType}</Text>
                            <Text style={styles.aadhaarValue}>{String(value) || 'Not detected'}</Text>
                          </View>
                        ))}
                        <View style={styles.aadhaarDataItem}>
                          <Text style={styles.aadhaarLabel}>Document Format:</Text>
                          <Text style={styles.aadhaarValue}>{_templateMatchResult.format || 'Unknown'}</Text>
                        </View>
                        {/* <View style={styles.aadhaarDataItem}>
                          <Text style={styles.aadhaarLabel}>Match Score:</Text>
                          <Text style={styles.aadhaarValue}>{Math.round((_templateMatchResult.matchScore || 0) * 100)}%</Text>
                        </View> */}
                      </View>
                    </>
                  )}

                  <Text style={[styles.sectionTitle, styles.topMargin]}>
                    Text Recognition
                  </Text>
                  <Text style={styles.resultText}>{resultText}</Text>

                  <Text style={[styles.sectionTitle, styles.topMargin]}>
                    Face Analysis
                  </Text>
                  <View style={styles.faceMetrics}>
                    <View style={styles.metricItem}>
                      <Text style={styles.metricLabel}>Smile Probability</Text>
                      <Text style={styles.metricValue}>
                        {Math.round(faceData?.smileProb * 100)}%
                      </Text>
                    </View>
                    <View style={styles.metricItem}>
                      <Text style={styles.metricLabel}>Left Eye</Text>
                      <Text style={styles.metricValue}>
                        {Math.round(faceData?.leftEyeOpenProb * 100)}%
                      </Text>
                    </View>
                    <View style={styles.metricItem}>
                      <Text style={styles.metricLabel}>Right Eye</Text>
                      <Text style={styles.metricValue}>
                        {Math.round(faceData?.rightEyeOpenProb * 100)}%
                      </Text>
                    </View>
                  </View>
                </View>
              </View>
            </ScrollView>
            <TouchableOpacity
              style={styles.modalButton}
              onPress={() => setShowResultModal(false)}>
              <Text style={styles.modalButtonText}>Close</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Manual Crop Overlay */}
      {showManualCrop && manualCropData && (
        <ManualCropOverlay
          frameImage={manualCropData.frameImage}
          frameWidth={manualCropData.frameWidth}
          frameHeight={manualCropData.frameHeight}
          onCropConfirm={handleManualCropConfirm}
          onCancel={handleManualCropCancel}
          visible={showManualCrop}
        />
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
  cameraView: {
    flex: 1,
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    bottom: 0,
    right: 0,
  },
  instructionContainer: {
    position: 'absolute',
    top: '20%',
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  instructionText: {
    color: 'white',
    fontSize: 18,
    backgroundColor: 'rgba(0,0,0,0.6)',
    padding: 12,
    borderRadius: 8,
    textAlign: 'center',
  },
  croppedImage: {
    flex: 1,
    width: windowWidth,
    height: windowHeight,
  },
  buttonContainer: {
    position: 'absolute',
    bottom: 30,
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  button: {
    backgroundColor: '#ffffffaa',
    padding: 15,
    borderRadius: 10,
    width: 120,
    alignItems: 'center',
  },
  buttonText: {
    color: '#000',
    fontSize: 16,
  },
  feedbackContainer: {
    position: 'absolute',
    bottom: 100,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  feedbackText: {
    color: 'white',
    fontSize: 18,
    backgroundColor: 'rgba(0,0,0,0.5)',
    padding: 10,
  },
  loaderContainer: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.3)',
  },
  loaderText: {
    marginTop: 10,
    fontSize: 16,
    color: '#000',
  },
  dimensionsContainer: {
    position: 'absolute',
    top: 40,
    left: 0,
    right: 0,
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    padding: 10,
  },
  dimensionsText: {
    color: 'white',
    fontSize: 16,
    marginVertical: 2,
  },
  faceDataContainer: {
    position: 'absolute',
    bottom: 120,
    left: 0,
    right: 0,
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    padding: 10,
  },
  faceImage: {
    width: 150,
    height: 150,
    marginBottom: 10,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: 'white',
  },
  faceDataText: {
    color: 'white',
    fontSize: 16,
    marginVertical: 2,
  },
  modalContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
  },
  modalContent: {
    backgroundColor: 'white',
    borderRadius: 20,
    padding: 0,
    width: '90%',
    maxHeight: '80%',
    overflow: 'hidden',
  },
  modalScrollView: {
    width: '100%',
  },
  modalHeader: {
    backgroundColor: '#f8f9fa',
    borderBottomWidth: 1,
    borderBottomColor: '#e9ecef',
    padding: 15,
    alignItems: 'center',
  },
  modalTitle: {
    color: '#2c3e50',
    fontSize: 20,
    fontWeight: 'bold',
  },
  modalBody: {
    padding: 20,
  },
  faceImageContainer: {
    width: '100%',
    alignItems: 'center',
    marginBottom: 20,
    backgroundColor: '#f8f9fa',
    padding: 15,
    borderRadius: 10,
  },
  modalFaceImage: {
    width: 150,
    height: 150,
    borderRadius: 10,
  },
  textResultsContainer: {
    width: '100%',
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#2c3e50',
    marginBottom: 10,
  },
  topMargin: {
    marginTop: 20,
  },
  resultText: {
    fontSize: 16,
    color: '#2c3e50',
    lineHeight: 24,
    backgroundColor: '#f8f9fa',
    padding: 15,
    borderRadius: 10,
  },
  faceMetrics: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    flexWrap: 'wrap',
    backgroundColor: '#f8f9fa',
    padding: 15,
    borderRadius: 10,
  },
  metricItem: {
    alignItems: 'center',
    minWidth: '30%',
    marginBottom: 10,
  },
  metricLabel: {
    fontSize: 14,
    color: '#2c3e50',
    marginBottom: 5,
  },
  metricValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#2c3e50',
  },
  modalButton: {
    backgroundColor: '#f8f9fa',
    padding: 15,
    borderRadius: 10,
    width: '100%',
    alignItems: 'center',
    marginTop: 10,
    borderWidth: 1,
    borderColor: '#e9ecef',
  },
  modalButtonText: {
    color: '#2c3e50',
    fontSize: 16,
    fontWeight: 'bold',
  },
  aadhaarDataContainer: {
    backgroundColor: '#f8f9fa',
    padding: 15,
    borderRadius: 10,
    marginBottom: 15,
  },
  aadhaarDataItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#e9ecef',
  },
  aadhaarLabel: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#2c3e50',
    flex: 1,
  },
  aadhaarValue: {
    fontSize: 16,
    color: '#495057',
    flex: 2,
    textAlign: 'right',
  },
  documentSelectionContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#f8f9fa',
  },
  selectionTitle: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#2c3e50',
    marginBottom: 20,
    textAlign: 'center',
  },
  pickerContainer: {
    width: '100%',
    marginBottom: 20,
    borderRadius: 10,
    backgroundColor: 'white',
    overflow: 'hidden',
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  picker: {
    width: '100%',
    height: 50,
  },
  docDescription: {
    fontSize: 16,
    color: '#666',
    textAlign: 'center',
    marginBottom: 10,
  },
  docDimensions: {
    fontSize: 14,
    color: '#666',
    marginBottom: 30,
  },
  startButton: {
    backgroundColor: '#007AFF',
    paddingHorizontal: 30,
    paddingVertical: 15,
    borderRadius: 10,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  startButtonText: {
    color: 'white',
    fontSize: 18,
    fontWeight: '600',
  },
  backButton: {
    position: 'absolute',
    top: 40,
    left: 20,
    backgroundColor: 'rgba(0,0,0,0.5)',
    padding: 10,
    borderRadius: 8,
  },
  backButtonText: {
    color: 'white',
    fontSize: 16,
  },
  orientationText: {
    color: 'white',
    fontSize: 14,
    marginTop: 10,
  },
  scanRegionText: {
    color: 'white',
    fontSize: 14,
    marginTop: 10,
  },
  scanRegionBox: {
    position: 'absolute',
  },
  corner: {
    position: 'absolute',
    width: 40,
    height: 40,
    borderColor: '#00FF00',
    borderWidth: 3,
  },
  topLeft: {
    top: 0,
    left: 0,
    borderRightWidth: 0,
    borderBottomWidth: 0,
  },
  topRight: {
    top: 0,
    right: 0,
    borderLeftWidth: 0,
    borderBottomWidth: 0,
  },
  bottomLeft: {
    bottom: 0,
    left: 0,
    borderRightWidth: 0,
    borderTopWidth: 0,
  },
  bottomRight: {
    bottom: 0,
    right: 0,
    borderLeftWidth: 0,
    borderTopWidth: 0,
  },
  subtleBorder: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderWidth: 1,
    borderColor: 'rgba(0, 255, 0, 0.5)',
  },
  debugOverlay: {
    position: 'absolute',
    top: 100,
    left: 10,
    right: 10,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    padding: 8,
    borderRadius: 5,
  },
  debugText: {
    color: 'white',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  testOverlay: {
    position: 'absolute',
    backgroundColor: 'rgba(255, 0, 0, 0.3)', // Semi-transparent red
    borderWidth: 2,
    borderColor: 'red',
  },
});

export default App;
