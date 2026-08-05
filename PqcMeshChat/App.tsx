import React, { useState, useEffect } from 'react';
import {
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
  Button,
  NativeModules,
  NativeEventEmitter,
  TextInput,
  FlatList,
} from 'react-native';

const { CryptoModule, BLEMeshModule } = NativeModules;
const bleEmitter = new NativeEventEmitter(BLEMeshModule);

interface Message {
  id: string;
  sender: string;
  text: string;
  isMine: boolean;
}

function App(): React.JSX.Element {
  const isDarkMode = useColorScheme() === 'dark';
  
  const [keys, setKeys] = useState<string>('No keys generated yet.');
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [targetDevice, setTargetDevice] = useState(''); // E.g. "00:11:22:33:44:55"
  
  useEffect(() => {
    // Listen for incoming reassembled messages from BLEMeshModule
    const subscription = bleEmitter.addListener('onMessageReceived', (event) => {
      const { senderAddress, payload } = event;
      setMessages(prev => [...prev, {
        id: Math.random().toString(),
        sender: senderAddress,
        text: payload,
        isMine: false
      }]);
    });
    
    return () => subscription.remove();
  }, []);

  const generateKeys = async () => {
    try {
      setKeys("Generating keys...");
      await CryptoModule.generateKeys();
      const pubKeys = await CryptoModule.getPublicKeys();
      setKeys(pubKeys);
    } catch (e: any) {
      setKeys(`Error: ${e.message}`);
    }
  };

  const startAdvertising = async () => {
    try {
      await BLEMeshModule.startAdvertising();
      console.log("Advertising & GATT Server started");
    } catch (e) {
      console.error(e);
    }
  };

  const sendMessage = async () => {
    if (!inputText || !targetDevice) return;
    
    try {
      // Send raw text for PoC (In reality, we'd use CryptoModule.encryptMessage first!)
      await BLEMeshModule.sendMessageToDevice(targetDevice, inputText);
      
      setMessages(prev => [...prev, {
        id: Math.random().toString(),
        sender: 'Me',
        text: inputText,
        isMine: true
      }]);
      setInputText('');
    } catch (e) {
      console.error("Failed to send:", e);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <View style={styles.header}>
        <Text style={styles.title}>PQC Mesh Chat</Text>
      </View>
      
      <ScrollView style={styles.content}>
        <View style={styles.card}>
          <Button title="1. Generate PQC Identity" onPress={generateKeys} />
          <Text style={styles.keyText} numberOfLines={2}>{keys}</Text>
        </View>

        <View style={styles.card}>
          <Button title="2. Start BLE Mesh Node" onPress={startAdvertising} color="#10B981" />
        </View>

        <View style={styles.chatArea}>
          <Text style={styles.sectionTitle}>Chat</Text>
          {messages.map(m => (
            <View key={m.id} style={[styles.messageBubble, m.isMine ? styles.myMessage : styles.theirMessage]}>
              <Text style={styles.messageSender}>{m.sender}</Text>
              <Text style={styles.messageText}>{m.text}</Text>
            </View>
          ))}
        </View>
      </ScrollView>

      <View style={styles.inputArea}>
        <TextInput 
          style={styles.input} 
          placeholder="Target MAC (e.g. 00:11:22...)" 
          value={targetDevice}
          onChangeText={setTargetDevice}
        />
        <TextInput 
          style={styles.input} 
          placeholder="Type message..." 
          value={inputText}
          onChangeText={setInputText}
        />
        <Button title="Send" onPress={sendMessage} />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F3F4F6',
  },
  header: {
    padding: 16,
    backgroundColor: '#1F2937',
    alignItems: 'center',
  },
  title: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#F9FAFB',
  },
  content: {
    padding: 16,
    flex: 1,
  },
  card: {
    marginBottom: 16,
    padding: 16,
    backgroundColor: '#FFFFFF',
    borderRadius: 8,
    elevation: 2,
  },
  keyText: {
    fontFamily: 'monospace',
    fontSize: 10,
    color: '#6B7280',
    marginTop: 8,
  },
  chatArea: {
    marginTop: 16,
    paddingBottom: 40,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 12,
  },
  messageBubble: {
    padding: 12,
    borderRadius: 12,
    marginBottom: 8,
    maxWidth: '80%',
  },
  myMessage: {
    backgroundColor: '#3B82F6',
    alignSelf: 'flex-end',
  },
  theirMessage: {
    backgroundColor: '#E5E7EB',
    alignSelf: 'flex-start',
  },
  messageSender: {
    fontSize: 10,
    color: '#D1D5DB',
    marginBottom: 4,
  },
  messageText: {
    fontSize: 16,
    color: '#FFFFFF',
  },
  inputArea: {
    padding: 16,
    backgroundColor: '#FFFFFF',
    borderTopWidth: 1,
    borderColor: '#E5E7EB',
  },
  input: {
    borderWidth: 1,
    borderColor: '#D1D5DB',
    borderRadius: 6,
    padding: 8,
    marginBottom: 8,
  }
});

export default App;
