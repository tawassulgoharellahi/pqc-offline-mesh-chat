import React, { useState, useEffect } from 'react';
import {
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
  Alert
} from 'react-native';
import { SafeAreaView, SafeAreaProvider } from 'react-native-safe-area-context';
import QRCode from 'react-native-qrcode-svg';
import { Camera, useCameraDevice, useCodeScanner } from 'react-native-vision-camera';

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
  const [myMac, setMyMac] = useState<string>('');
  const [qrPayload, setQrPayload] = useState<string>('');
  
  const [theirKeys, setTheirKeys] = useState<string>('');
  const [targetDevice, setTargetDevice] = useState<string>('');
  
  const [messages, setMessages] = useState<Message[]>([]);
  const [relayedCount, setRelayedCount] = useState<number>(0);
  const [inputText, setInputText] = useState('');
  
  const [handshakeDone, setHandshakeDone] = useState(false);
  const [isScanning, setIsScanning] = useState(false);
  const [meshActive, setMeshActive] = useState(false);
  
  const device = useCameraDevice('back');

  const codeScanner = useCodeScanner({
    codeTypes: ['qr'],
    onCodeScanned: (codes) => {
      if (codes.length > 0 && codes[0].value) {
        const scannedValue = codes[0].value;
        try {
          const parsed = JSON.parse(scannedValue);
          if (parsed.keys) setTheirKeys(parsed.keys);
          else setTheirKeys(scannedValue);
          if (parsed.mac) setTargetDevice(parsed.mac);
        } catch (e) {
          setTheirKeys(scannedValue);
        }
        setIsScanning(false);
        Alert.alert("QR Code Scanned", "Partner's PQC keys & MAC address stored automatically!");
      }
    }
  });

  const startScanning = async () => {
    const permission = await Camera.requestCameraPermission();
    if (permission === 'granted') {
      setIsScanning(true);
    } else {
      Alert.alert("Permission Denied", "Camera permission is required to scan QR codes.");
    }
  };

  useEffect(() => {
    const recvSub = bleEmitter.addListener('onMessageReceived', async (event) => {
      const { senderAddress, payload } = event;
      
      try {
        if (!handshakeDone) {
          console.warn("Received a message but handshake not completed yet!");
          return;
        }
        
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

    const relaySub = bleEmitter.addListener('onMessageRelayed', (event) => {
      setRelayedCount(prev => prev + 1);
    });
    
    return () => {
      recvSub.remove();
      relaySub.remove();
    };
  }, [handshakeDone]);

  const generateKeys = async () => {
    try {
      await CryptoModule.generateKeys();
      const base64Keys = await CryptoModule.exportPublicKeysBase64();
      let mac = '';
      try {
        mac = await BLEMeshModule.getMacAddress();
      } catch (err) {
        console.warn("Could not retrieve MAC address:", err);
      }
      setMyKeys(base64Keys);
      setMyMac(mac);
      setQrPayload(JSON.stringify({ keys: base64Keys, mac }));
    } catch (e: any) {
      Alert.alert("Key Gen Error", e.message);
    }
  };

  const startAdvertising = async () => {
    try {
      await BLEMeshModule.startAdvertising();
      setMeshActive(true);
      Alert.alert("Success", "BLE Mesh Node Started!");
    } catch (e: any) {
      Alert.alert("Error", e.message);
    }
  };

  const initiateHandshake = async () => {
    if (!theirKeys) {
      Alert.alert("Error", "No partner keys available. Please scan partner's QR code first.");
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
    if (!inputText) return;
    if (!targetDevice) {
      Alert.alert("Error", "Target MAC address not found. Please scan partner's QR code first!");
      return;
    }
    if (!handshakeDone) {
      Alert.alert("Error", "You must complete the key exchange handshake first!");
      return;
    }
    
    try {
      const ciphertextBase64 = await CryptoModule.encryptMessage(inputText);
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

  if (isScanning) {
    if (device == null) return (
      <SafeAreaView style={styles.container}>
        <Text>No Camera Device Found</Text>
        <Button title="Back" onPress={() => setIsScanning(false)} />
      </SafeAreaView>
    );
    return (
      <View style={StyleSheet.absoluteFill}>
        <Camera
          style={StyleSheet.absoluteFill}
          device={device}
          isActive={true}
          codeScanner={codeScanner}
        />
        <View style={{ position: 'absolute', bottom: 50, alignSelf: 'center', backgroundColor: 'rgba(0,0,0,0.5)', padding: 10, borderRadius: 10 }}>
          <Button title="Cancel Scan" onPress={() => setIsScanning(false)} color="#EF4444" />
        </View>
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom', 'left', 'right']}>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <View style={styles.header}>
        <Text style={styles.title}>PQC Mesh Chat</Text>
      </View>
      
      <ScrollView style={styles.content}>
        
        {/* Step 1: My Identity */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>1. My Identity</Text>
          <Button title="Generate PQC Keys" onPress={generateKeys} />
          {qrPayload ? (
            <View style={{marginTop: 16, alignItems: 'center'}}>
              <Text style={styles.label}>Show this QR code to your friend:</Text>
              <View style={{ padding: 16, backgroundColor: 'white', borderRadius: 8, elevation: 4 }}>
                <QRCode value={qrPayload} size={240} />
              </View>
            </View>
          ) : null}
        </View>

        {/* Step 2: Key Exchange */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>2. Key Exchange</Text>
          <View style={{marginBottom: 12}}>
            <Button title="Scan Partner's QR Code" onPress={startScanning} color="#3B82F6" />
          </View>
          
          {theirKeys ? (
            <View style={styles.statusBox}>
              <Text style={styles.statusText}>✓ Partner Public Keys: Auto-Stored</Text>
              <Text style={styles.statusText}>✓ Partner MAC Address: {targetDevice || 'Auto-Detected'}</Text>
            </View>
          ) : (
            <Text style={styles.placeholderText}>Scan partner's QR code to automatically exchange keys & MAC address.</Text>
          )}

          <View style={{marginTop: 12}}>
            <Button title="Initiate Secure Handshake" onPress={initiateHandshake} color="#8B5CF6" />
          </View>
          {handshakeDone && <Text style={styles.successText}>✓ Session Secured</Text>}
        </View>

        {/* Step 3: Mesh Network */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>3. Start Mesh Network</Text>
          <Button title="Start BLE GATT Server & Advertising" onPress={startAdvertising} color="#10B981" />
          {meshActive && (
            <View style={[styles.statusBox, { marginTop: 12, backgroundColor: '#EFF6FF', borderColor: '#BFDBFE' }]}>
              <Text style={[styles.statusText, { color: '#1E40AF' }]}>📡 Gossip Relay Node: Active</Text>
              <Text style={[styles.statusText, { color: '#1E40AF' }]}>📦 Packets Relayed via Mesh: {relayedCount}</Text>
            </View>
          )}
        </View>

        {/* Chat Interface */}
        <View style={styles.chatArea}>
          <Text style={styles.sectionTitle}>Encrypted Chat</Text>
          {messages.length === 0 ? (
            <Text style={styles.placeholderText}>No messages yet.</Text>
          ) : (
            messages.map(m => (
              <View key={m.id} style={[styles.messageBubble, m.isMine ? styles.myMessage : styles.theirMessage]}>
                <Text style={styles.messageSender}>{m.sender}</Text>
                <Text style={styles.messageText}>{m.text}</Text>
              </View>
            ))
          )}
        </View>
      </ScrollView>

      {/* Input Footer */}
      <View style={styles.inputArea}>
        <View style={styles.row}>
          <TextInput 
            style={[styles.input, {flex: 1, marginRight: 8}]} 
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
  label: { fontSize: 13, color: '#4B5563', marginBottom: 8, fontWeight: '500' },
  statusBox: { backgroundColor: '#ECFDF5', padding: 10, borderRadius: 6, borderWidth: 1, borderColor: '#A7F3D0', marginBottom: 8 },
  statusText: { fontSize: 12, color: '#065F46', fontWeight: '600' },
  placeholderText: { fontSize: 12, color: '#9CA3AF', fontStyle: 'italic', marginVertical: 4 },
  successText: { color: '#10B981', fontWeight: 'bold', marginTop: 8, textAlign: 'center' },
  chatArea: { marginTop: 8, paddingBottom: 40 },
  messageBubble: { padding: 12, borderRadius: 12, marginBottom: 8, maxWidth: '85%' },
  myMessage: { backgroundColor: '#3B82F6', alignSelf: 'flex-end', borderBottomRightRadius: 2 },
  theirMessage: { backgroundColor: '#E5E7EB', alignSelf: 'flex-start', borderBottomLeftRadius: 2 },
  messageSender: { fontSize: 10, color: '#D1D5DB', marginBottom: 4 },
  messageText: { fontSize: 16, color: '#FFFFFF' },
  inputArea: { padding: 16, backgroundColor: '#FFFFFF', borderTopWidth: 1, borderColor: '#E5E7EB' },
  row: { flexDirection: 'row', alignItems: 'center' },
  input: { borderWidth: 1, borderColor: '#D1D5DB', borderRadius: 6, padding: 10, backgroundColor: '#F9FAFB' }
});

export default function AppWrapper() {
  return (
    <SafeAreaProvider>
      <App />
    </SafeAreaProvider>
  );
}
