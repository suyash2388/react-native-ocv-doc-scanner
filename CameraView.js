import React from 'react';
// eslint-disable-next-line prettier/prettier
import { findNodeHandle, requireNativeComponent, UIManager, DeviceEventManager } from 'react-native';

const CameraViewNativeComponent = requireNativeComponent('CameraView');

class CameraView extends React.Component {
  static pauseScanning() {
    throw new Error('Method not implemented.');
  }
  static resumeScanning() {
    throw new Error('Method not implemented.');
  }
  constructor(props) {
    super(props);
    this.cameraRef = React.createRef();
    this.isReady = false;
    this.documentDetectionListener = null;
    this.documentContoursListener = null;
    this.manualCropListener = null;
  }

  componentDidMount() {
    // Mark as ready after a short delay to ensure native view is initialized
    setTimeout(() => {
      this.isReady = true;
      console.log('📸 CameraView is now ready');
    }, 500);

    // Set up event listeners for real-time document detection
    this.setupDocumentDetectionListeners();
  }

  componentWillUnmount() {
    this.removeDocumentDetectionListeners();
  }

  setupDocumentDetectionListeners = () => {
    const { DeviceEventEmitter } = require('react-native');

    // Listen for document detection events
    this.documentDetectionListener = DeviceEventEmitter.addListener(
      'DocumentDetected',
      this.onDocumentDetected
    );

    // Listen for real-time contours for overlay visualization
    this.documentContoursListener = DeviceEventEmitter.addListener(
      'onDocumentContoursDetected',
      this.onDocumentContoursDetected
    );

    // Listen for manual crop needed events
    this.manualCropListener = DeviceEventEmitter.addListener(
      'onManualCropNeeded',
      this.onManualCropNeeded
    );

    console.log('📡 Document detection listeners set up');
  };

  removeDocumentDetectionListeners = () => {
    if (this.documentDetectionListener) {
      this.documentDetectionListener.remove();
      this.documentDetectionListener = null;
    }
    if (this.documentContoursListener) {
      this.documentContoursListener.remove();
      this.documentContoursListener = null;
    }
    if (this.manualCropListener) {
      this.manualCropListener.remove();
      this.manualCropListener = null;
    }
    console.log('📡 Document detection listeners removed');
  };

  onDocumentDetected = (event) => {
    console.log('📄 Document detected:', event);
    
    // Pass to parent component if callback provided
    if (this.props.onDocumentDetected) {
      this.props.onDocumentDetected(event);
    }
  };

  onDocumentContoursDetected = (event) => {
    console.log('🔲 Document contours detected:', event);
    
    // Pass to parent component for real-time overlay updates
    if (this.props.onDocumentContoursDetected) {
      this.props.onDocumentContoursDetected(event);
    }
  };

  onManualCropNeeded = (event) => {
    console.log('🔧 Manual crop needed:', event);
    
    // Pass to parent component to show manual cropping UI
    if (this.props.onManualCropNeeded) {
      this.props.onManualCropNeeded(event);
    }
  };

  pauseScanning = () => {
    const viewId = findNodeHandle(this.cameraRef.current);
    UIManager.dispatchViewManagerCommand(viewId, 'pauseScanning', null);
  };

  resumeScanning = () => {
    const viewId = findNodeHandle(this.cameraRef.current);
    UIManager.dispatchViewManagerCommand(viewId, 'resumeScanning', null);
  };

  setExpectedRatio = (aspectRatio, documentType) => {
    // For now, ignore aspect ratio constraints and enable generic document detection
    console.log('📞 CameraView.setExpectedRatio called - ignoring aspect ratio for generic detection');
    console.log('🎯 Generic document detection enabled - any rectangular document will be detected');
  };

  processManualCrop = (cornerPoints, frameWidth, frameHeight) => {
    const viewId = findNodeHandle(this.cameraRef.current);
    UIManager.dispatchViewManagerCommand(
      viewId,
      'processManualCrop',
      [cornerPoints, frameWidth, frameHeight]
    );
  };

  // New method to set detection sensitivity
  setDetectionSensitivity = (detectionCount = 3) => {
    try {
      if (!this.isReady) {
        console.log('⏳ CameraView not ready yet, waiting...');
        setTimeout(() => {
          this.setDetectionSensitivity(detectionCount);
        }, 100);
        return;
      }

      console.log('🎯 Setting detection sensitivity to:', detectionCount);

      const viewId = findNodeHandle(this.cameraRef.current);
      if (viewId) {
        // We'll add this command to the native side if needed
        console.log('✅ Detection sensitivity would be set to:', detectionCount);
      }
    } catch (error) {
      console.error('❌ Error setting detection sensitivity:', error);
    }
  };

  render() {
    return <CameraViewNativeComponent {...this.props} ref={this.cameraRef} />;
  }
}

export default CameraView;
