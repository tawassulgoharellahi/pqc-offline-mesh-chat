import React, { useState } from 'react';
import {
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
  Button,
  NativeModules
} from 'react-native';

const { CryptoModule } = NativeModules;

function App(): React.JSX.Element {
  const isDarkMode = useColorScheme() === 'dark';
  const [keys, setKeys] = useState<string>('No keys generated yet.');

  const generateKeys = async () => {
    try {
      setKeys("Generating keys... Please wait.");
      await CryptoModule.generateKeys();
      const pubKeys = await CryptoModule.getPublicKeys();
      setKeys(pubKeys);
    } catch (e: any) {
      setKeys(`Error: ${e.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <ScrollView contentInsetAdjustmentBehavior="automatic">
        <View style={styles.header}>
          <Text style={styles.title}>PQC Mesh Chat</Text>
        </View>
        <View style={styles.content}>
          <Button title="Generate PQC Identity" onPress={generateKeys} />
          <View style={styles.keyContainer}>
            <Text style={styles.keyLabel}>Public Keys:</Text>
            <Text style={styles.keyText}>{keys}</Text>
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F3F4F6',
  },
  header: {
    padding: 24,
    backgroundColor: '#1F2937',
    alignItems: 'center',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#F9FAFB',
  },
  content: {
    padding: 24,
  },
  keyContainer: {
    marginTop: 24,
    padding: 16,
    backgroundColor: '#FFFFFF',
    borderRadius: 8,
    shadowColor: '#000',
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 2,
  },
  keyLabel: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 8,
    color: '#374151',
  },
  keyText: {
    fontFamily: 'monospace',
    fontSize: 14,
    color: '#111827',
  }
});

export default App;
