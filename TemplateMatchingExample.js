import React, { useState } from 'react';
import {
    Alert,
    NativeModules,
    ScrollView,
    StyleSheet,
    Text,
    TouchableOpacity,
    View,
} from 'react-native';

const {TemplateMatchingModule} = NativeModules;

const TemplateMatchingExample = () => {
  const [result, setResult] = useState(null);
  const [isLoading, setIsLoading] = useState(false);

  // Example: Single template matching with specific method
  const performTemplateMatching = async () => {
    setIsLoading(true);
    try {
      const imagePath = '/path/to/your/main/image.jpg';
      const templatePath = '/path/to/your/template/image.jpg';
      const method = 5; // TM_CCOEFF_NORMED

      const matchResult = await TemplateMatchingModule.matchTemplate(
        imagePath,
        templatePath,
        method,
      );

      setResult(matchResult);
      console.log('Template matching result:', matchResult);
    } catch (error) {
      Alert.alert('Error', error.message);
      console.error('Template matching error:', error);
    } finally {
      setIsLoading(false);
    }
  };

  // Example: Template matching with all methods
  const performAllMethodsMatching = async () => {
    setIsLoading(true);
    try {
      const imagePath = '/path/to/your/main/image.jpg';
      const templatePath = '/path/to/your/template/image.jpg';

      const allResults = await TemplateMatchingModule.matchTemplateAllMethods(
        imagePath,
        templatePath,
      );

      setResult(allResults);
      console.log('All methods results:', allResults);
    } catch (error) {
      Alert.alert('Error', error.message);
      console.error('All methods error:', error);
    } finally {
      setIsLoading(false);
    }
  };

  // Get available template matching methods
  const getAvailableMethods = async () => {
    try {
      const methods = await TemplateMatchingModule.getTemplateMatchingMethods();
      setResult(methods);
      console.log('Available methods:', methods);
    } catch (error) {
      Alert.alert('Error', error.message);
      console.error('Get methods error:', error);
    }
  };

  const renderResult = () => {
    if (!result) return null;

    if (Array.isArray(result)) {
      return (
        <ScrollView style={styles.resultContainer}>
          <Text style={styles.resultTitle}>Results:</Text>
          {result.map((item, index) => (
            <View key={index} style={styles.resultItem}>
              <Text style={styles.resultText}>
                {JSON.stringify(item, null, 2)}
              </Text>
            </View>
          ))}
        </ScrollView>
      );
    }

    return (
      <View style={styles.resultContainer}>
        <Text style={styles.resultTitle}>Result:</Text>
        <Text style={styles.resultText}>
          {JSON.stringify(result, null, 2)}
        </Text>
      </View>
    );
  };

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>Template Matching Examples</Text>

      <TouchableOpacity
        style={styles.button}
        onPress={performTemplateMatching}
        disabled={isLoading}>
        <Text style={styles.buttonText}>Single Template Matching</Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.button}
        onPress={performAllMethodsMatching}
        disabled={isLoading}>
        <Text style={styles.buttonText}>All Methods Matching</Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={styles.button}
        onPress={getAvailableMethods}
        disabled={isLoading}>
        <Text style={styles.buttonText}>Get Available Methods</Text>
      </TouchableOpacity>

      {isLoading && (
        <Text style={styles.loadingText}>Processing...</Text>
      )}

      {renderResult()}
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    backgroundColor: '#f5f5f5',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
    textAlign: 'center',
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 15,
    borderRadius: 8,
    marginBottom: 10,
    alignItems: 'center',
  },
  buttonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: '600',
  },
  loadingText: {
    textAlign: 'center',
    fontSize: 16,
    color: '#666',
    marginVertical: 20,
  },
  resultContainer: {
    marginTop: 20,
    padding: 15,
    backgroundColor: 'white',
    borderRadius: 8,
    maxHeight: 300,
  },
  resultTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
  },
  resultItem: {
    marginBottom: 10,
    padding: 10,
    backgroundColor: '#f9f9f9',
    borderRadius: 5,
  },
  resultText: {
    fontSize: 12,
    fontFamily: 'monospace',
    color: '#333',
  },
});

export default TemplateMatchingExample; 