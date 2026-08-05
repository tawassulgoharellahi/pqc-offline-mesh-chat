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
  Alert,
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
  
  const [myKeys, setMyKeys] = useState<string>('');
  const [theirKeys, setTheirKeys] = useState<string>('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [targetDevice, setTargetDevice] = useState(''); // E.g. "00:11:22:33:44:55"
  
  const [handshakeDone, setHandshakeDone] = useState(false);

  useEffect(() => {
    // Listen for incoming reassembled messages from BLEMeshModule
    const subscription = bleEmitter.addListener('onMessageReceived', async (event) => {
      const { senderAddress, payload } = event; // Payload here is Base64 ciphertext
      
      try {
        if (!handshakeDone) {
          console.warn("Received a message but handshake not completed yet!");
          return;
        }
        
        // Decrypt the ciphertext
        const plaintext = await CryptoModule.decryptMessage(payload);
        
        setMessages(prev => [...prev, {
          id: Math.random().toString(),
          sender: senderAddress,
          text: plaintext,
          isMine: false
        }]);
      } catch (e: any) {
        console.error("Failed to decrypt incoming message:", e);
      }
    });
    
    return () => subscription.remove();
  }, [handshakeDone]);

  const generateKeys = async () => {
    try {
      await CryptoModule.generateKeys();
      const base64Keys = await CryptoModule.exportPublicKeysBase64();
      setMyKeys(base64Keys);
    } catch (e: any) {
      Alert.alert("Key Gen Error", e.message);
    }
  };

  const startAdvertising = async () => {
    try {
      await BLEMeshModule.startAdvertising();
      Alert.alert("Success", "BLE Mesh Node Started!");
    } catch (e: any) {
      Alert.alert("Error", e.message);
    }
  };

  const initiateHandshake = async () => {
    if (!theirKeys) {
      Alert.alert("Error", "Please paste the opponent's public keys first.");
      return;
    }
    try {
      await CryptoModule.initiateHandshake(theirKeys);
      setHandshakeDone(true);
      Alert.alert("Secure Session Established", "Post-Quantum Handshake Complete! You can now chat securely.");
    } catch (e: any) {
      Alert.alert("Handshake Failed", e.message);
    }
  };

  const sendMessage = async () => {
    if (!inputText || !targetDevice) return;
    if (!handshakeDone) {
      Alert.alert("Error", "You must complete the key exchange handshake first!");
      return;
    }
    
    try {
      // 1. Encrypt the plaintext using the PQC Session
      const ciphertextBase64 = await CryptoModule.encryptMessage(inputText);
      
      // 2. Transmit the ciphertext chunks over BLE
      await BLEMeshModule.sendMessageToDevice(targetDevice, ciphertextBase64);
      
      setMessages(prev => [...prev, {
        id: Math.random().toString(),
        sender: 'Me',
        text: inputText,
        isMine: true
      }]);
      setInputText('');
    } catch (e: any) {
      Alert.alert("Send Error", e.message);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <View style={styles.header}>
        <Text style={styles.title}>PQC Mesh Chat</Text>
      </View>
      
      <ScrollView style={styles.content}>
        
        {/* Step 1: My Identity */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>1. My Identity</Text>
          <Button title="Generate PQC Keys" onPress={generateKeys} />
          {myKeys ? (
            <View style={{marginTop: 12}}>
              <Text style={styles.label}>Copy these keys and give them to your friend:</Text>
              <TextInput style={styles.keyBox} multiline value={myKeys} editable={false} />
            </View>
          ) : null}
        </View>

        {/* Step 2: Key Exchange */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>2. Key Exchange</Text>
          <Text style={styles.label}>Paste your friend's keys here:</Text>
          <TextInput 
            style={[styles.keyBox, { height: 60 }]} 
            multiline 
            placeholder='{"x25519":"...","kyber":"..."}' 
            value={theirKeys}
            onChangeText={setTheirKeys}
          />
          <View style={{marginTop: 8}}>
            <Button title="Initiate Secure Handshake" onPress={initiateHandshake} color="#8B5CF6" />
          </View>
          {handshakeDone && <Text style={styles.successText}>✓ Session Secured</Text>}
        </View>

        {/* Step 3: Mesh Node */}
        <View style={styles.card}>
           <Text style={styles.sectionTitle}>3. Start Mesh Network</Text>
          <Button title="Start BLE GATT Server & Advertising" onPress={startAdvertising} color="#10B981" />
        </View>

        {/* Chat Interface */}
        <View style={styles.chatArea}>
          <Text style={styles.sectionTitle}>Encrypted Chat</Text>
          {messages.map(m => (
            <View key={m.id} style={[styles.messageBubble, m.isMine ? styles.myMessage : styles.theirMessage]}>
              <Text style={styles.messageSender}>{m.sender}</Text>
              <Text style={styles.messageText}>{m.text}</Text>
            </View>
          ))}
        </View>
      </ScrollView>

      {/* Input Footer */}
      <View style={styles.inputArea}>
        <TextInput 
          style={styles.input} 
          placeholder="Target BLE MAC (e.g. 00:11:22...)" 
          value={targetDevice}
          onChangeText={setTargetDevice}
        />
        <View style={styles.row}>
          <TextInput 
            style={[styles.input, {flex: 1, marginBottom: 0, marginRight: 8}]} 
            placeholder="Type secret message..." 
            value={inputText}
            onChangeText={setInputText}
          />
          <Button title="Send" onPress={sendMessage} />
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F3F4F6' },
  header: { padding: 16, backgroundColor: '#1F2937', alignItems: 'center' },
  title: { fontSize: 20, fontWeight: 'bold', color: '#F9FAFB' },
  content: { padding: 16, flex: 1 },
  card: { marginBottom: 16, padding: 16, backgroundColor: '#FFFFFF', borderRadius: 8, elevation: 2 },
  sectionTitle: { fontSize: 16, fontWeight: 'bold', marginBottom: 12, color: '#111827' },
  label: { fontSize: 12, color: '#4B5563', marginBottom: 4 },
  keyBox: { fontFamily: 'monospace', fontSize: 10, color: '#6B7280', backgroundColor: '#F9FAFB', padding: 8, borderRadius: 4, borderWidth: 1, borderColor: '#E5E7EB', maxHeight: 80 },
  successText: { color: '#10B981', fontWeight: 'bold', marginTop: 8, textAlign: 'center' },
  chatArea: { marginTop: 8, paddingBottom: 40 },
  messageBubble: { padding: 12, borderRadius: 12, marginBottom: 8, maxWidth: '85%' },
  myMessage: { backgroundColor: '#3B82F6', alignSelf: 'flex-end', borderBottomRightRadius: 2 },
  theirMessage: { backgroundColor: '#E5E7EB', alignSelf: 'flex-start', borderBottomLeftRadius: 2 },
  messageSender: { fontSize: 10, color: '#D1D5DB', marginBottom: 4 },
  messageText: { fontSize: 16, color: '#FFFFFF' },
  inputArea: { padding: 16, backgroundColor: '#FFFFFF', borderTopWidth: 1, borderColor: '#E5E7EB' },
  row: { flexDirection: 'row', alignItems: 'center' },
  input: { borderWidth: 1, borderColor: '#D1D5DB', borderRadius: 6, padding: 10, marginBottom: 8, backgroundColor: '#F9FAFB' }
});

export default App;
