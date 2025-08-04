/* eslint-disable prettier/prettier */

import React, { useState, useRef, useCallback } from 'react';
import {
  View,
  Text,
  Image,
  TouchableOpacity,
  PanResponder,
  StyleSheet,
  Dimensions,
} from 'react-native';
import Svg, { Polygon } from 'react-native-svg';

const { width: windowWidth, height: windowHeight } = Dimensions.get('window');

const ManualCropOverlay = ({ 
  frameImage, 
  frameWidth, 
  frameHeight, 
  onCropConfirm, 
  onCancel,
  visible 
}) => {
  // Calculate display scale
  const displayWidth = windowWidth * 0.9;
  const displayHeight = windowHeight * 0.7;
  const scaleX = displayWidth / frameWidth;
  const scaleY = displayHeight / frameHeight;
  const scale = Math.min(scaleX, scaleY);

  // Initial corner positions (default to a centered rectangle)
  const [corners, setCorners] = useState({
    topLeft: { x: frameWidth * 0.1, y: frameHeight * 0.1 },
    topRight: { x: frameWidth * 0.9, y: frameHeight * 0.1 },
    bottomLeft: { x: frameWidth * 0.1, y: frameHeight * 0.9 },
    bottomRight: { x: frameWidth * 0.9, y: frameHeight * 0.9 },
  });

  // Store refs for each corner's start position
  const startPositions = useRef({});

  // Create individual pan responders for each corner
  const createCornerPanResponder = (cornerKey) => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderGrant: () => {
      startPositions.current[cornerKey] = { ...corners[cornerKey] };
    },
    onPanResponderMove: (evt, gestureState) => {
      const { dx, dy } = gestureState;
      const startPos = startPositions.current[cornerKey];
      
      if (!startPos) return;
      
      // Calculate new position based on start position + delta
      const frameX = startPos.x + (dx / scale);
      const frameY = startPos.y + (dy / scale);
      
      // Constrain to frame bounds
      const constrainedX = Math.max(0, Math.min(frameWidth, frameX));
      const constrainedY = Math.max(0, Math.min(frameHeight, frameY));
      
      setCorners(prevCorners => ({
        ...prevCorners,
        [cornerKey]: { x: constrainedX, y: constrainedY },
      }));
    },
  });

  const topLeftPanResponder = createCornerPanResponder('topLeft');
  const topRightPanResponder = createCornerPanResponder('topRight');
  const bottomLeftPanResponder = createCornerPanResponder('bottomLeft');
  const bottomRightPanResponder = createCornerPanResponder('bottomRight');

  const handleConfirm = () => {
    // Extract corner coordinates in the order expected by native code
    const cornerArray = [
      corners.topLeft.x, corners.topLeft.y,
      corners.topRight.x, corners.topRight.y,
      corners.bottomRight.x, corners.bottomRight.y,
      corners.bottomLeft.x, corners.bottomLeft.y,
    ];
    
    console.log('🔧 ManualCropOverlay - Sending corners:', {
      topLeft: `(${corners.topLeft.x.toFixed(1)}, ${corners.topLeft.y.toFixed(1)})`,
      topRight: `(${corners.topRight.x.toFixed(1)}, ${corners.topRight.y.toFixed(1)})`,
      bottomRight: `(${corners.bottomRight.x.toFixed(1)}, ${corners.bottomRight.y.toFixed(1)})`,
      bottomLeft: `(${corners.bottomLeft.x.toFixed(1)}, ${corners.bottomLeft.y.toFixed(1)})`,
      frameSize: `${frameWidth}x${frameHeight}`,
      scale: scale.toFixed(3)
    });
    
    onCropConfirm(cornerArray, frameWidth, frameHeight);
  };

  if (!visible) {
    return null;
  }

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Adjust Document Corners</Text>
        <Text style={styles.subtitle}>Drag the corners to match your document</Text>
      </View>

      <View style={styles.imageContainer}>
        <Image
          source={{ uri: `data:image/png;base64,${frameImage}` }}
          style={[
            styles.image,
            {
              width: frameWidth * scale,
              height: frameHeight * scale,
            }
          ]}
          resizeMode="contain"
        />
        
        {/* Corner handles */}
        <View
          {...topLeftPanResponder.panHandlers}
          style={[
            styles.corner,
            {
              left: corners.topLeft.x * scale - 20,
              top: corners.topLeft.y * scale - 20,
            }
          ]}
        />
        
        <View
          {...topRightPanResponder.panHandlers}
          style={[
            styles.corner,
            {
              left: corners.topRight.x * scale - 20,
              top: corners.topRight.y * scale - 20,
            }
          ]}
        />
        
        <View
          {...bottomLeftPanResponder.panHandlers}
          style={[
            styles.corner,
            {
              left: corners.bottomLeft.x * scale - 20,
              top: corners.bottomLeft.y * scale - 20,
            }
          ]}
        />
        
        <View
          {...bottomRightPanResponder.panHandlers}
          style={[
            styles.corner,
            {
              left: corners.bottomRight.x * scale - 20,
              top: corners.bottomRight.y * scale - 20,
            }
          ]}
        />

        {/* SVG overlay for connecting lines */}
        <Svg 
          style={styles.svgOverlay} 
          width={frameWidth * scale} 
          height={frameHeight * scale}
        >
          <Polygon
            points={`${corners.topLeft.x * scale},${corners.topLeft.y * scale} ${corners.topRight.x * scale},${corners.topRight.y * scale} ${corners.bottomRight.x * scale},${corners.bottomRight.y * scale} ${corners.bottomLeft.x * scale},${corners.bottomLeft.y * scale}`}
            fill="rgba(0, 255, 0, 0.2)"
            stroke="rgba(0, 255, 0, 0.8)"
            strokeWidth="2"
          />
        </Svg>
      </View>

      <View style={styles.buttonContainer}>
        <TouchableOpacity style={styles.cancelButton} onPress={onCancel}>
          <Text style={styles.buttonText}>Cancel</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.confirmButton} onPress={handleConfirm}>
          <Text style={styles.buttonText}>Crop Document</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.9)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  header: {
    alignItems: 'center',
    marginBottom: 20,
  },
  title: {
    color: 'white',
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 5,
  },
  subtitle: {
    color: 'rgba(255, 255, 255, 0.7)',
    fontSize: 14,
  },
  imageContainer: {
    position: 'relative',
    alignItems: 'center',
    justifyContent: 'center',
  },
  image: {
    backgroundColor: 'rgba(255, 255, 255, 0.1)',
  },
  corner: {
    position: 'absolute',
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0, 255, 0, 0.8)',
    borderWidth: 3,
    borderColor: 'white',
    justifyContent: 'center',
    alignItems: 'center',
  },
  svgOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    pointerEvents: 'none', // Allow touches to pass through to corner handles
  },
  topLeft: {
    // Additional styling if needed
  },
  topRight: {
    // Additional styling if needed
  },
  bottomLeft: {
    // Additional styling if needed
  },
  bottomRight: {
    // Additional styling if needed
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    // This would contain the SVG path for the crop area
  },
  buttonContainer: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    width: '80%',
    marginTop: 30,
  },
  cancelButton: {
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
    paddingHorizontal: 30,
    paddingVertical: 15,
    borderRadius: 25,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.3)',
  },
  confirmButton: {
    backgroundColor: 'rgba(0, 255, 0, 0.8)',
    paddingHorizontal: 30,
    paddingVertical: 15,
    borderRadius: 25,
  },
  buttonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: 'bold',
  },
});

export default ManualCropOverlay;