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
  Alert,
  TouchableOpacity,
  PermissionsAndroid,
  Platform
} from 'react-native';
import { SafeAreaView, SafeAreaProvider } from 'react-native-safe-area-context';
import QRCode from 'react-native-qrcode-svg';
import { Camera } from 'react-native-camera-kit';

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
  const [manualKeyInput, setManualKeyInput] = useState<string>('');
  const [showManualSection, setShowManualSection] = useState<boolean>(false);
  
  const [messages, setMessages] = useState<Message[]>([]);
  const [relayedCount, setRelayedCount] = useState<number>(0);
  const [inputText, setInputText] = useState('');
  
  const [handshakeDone, setHandshakeDone] = useState(false);
  const [isScanning, setIsScanning] = useState(false);
  const [meshActive, setMeshActive] = useState(false);

  const performHandshakeWithKeys = async (keysToUse: string) => {
    try {
      await CryptoModule.initiateHandshake(keysToUse);
      setHandshakeDone(true);
      Alert.alert("Success", "Post-Quantum Secure Session Established!");
    } catch (e: any) {
      console.error("Handshake error:", e.message);
      Alert.alert("Handshake Failed", e.message);
    }
  };

  const startScanning = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Camera Permission',
            message: 'App needs camera access to scan QR codes for secure PQC key exchange.',
            buttonNeutral: 'Ask Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'OK',
          }
        );
        if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
          Alert.alert("Permission Denied", "Camera permission is required to scan QR codes.");
          return;
        }
      } catch (err) {
        console.warn("Camera permission request error:", err);
      }
    }
    setIsScanning(true);
  };

  useEffect(() => {
    const initBle = async () => {
      try {
        await BLEMeshModule.startAdvertising();
        setMeshActive(true);
      } catch (err) {
        console.warn("BLE auto-start notice:", err);
      }
    };
    initBle();

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

    const handshakeSub = bleEmitter.addListener('onHandshakeKeysReceived', async (event) => {
      const { senderAddress, keys } = event;
      setTheirKeys(keys);
      if (senderAddress) setTargetDevice(senderAddress);
      await performHandshakeWithKeys(keys);
    });

    const relaySub = bleEmitter.addListener('onMessageRelayed', (event) => {
      setRelayedCount(prev => prev + 1);
    });
    
    return () => {
      recvSub.remove();
      handshakeSub.remove();
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
      setQrPayload(JSON.stringify({ m: mac }));
    } catch (e: any) {
      Alert.alert("Key Gen Error", e.message);
    }
  };

  const sendMessage = async () => {
    if (!inputText) return;
    if (!targetDevice) {
      Alert.alert("Error", "Target MAC address not found. Please scan partner's QR code first!");
      return;
    }
    if (!handshakeDone) {
      Alert.alert("Error", "You must scan your partner's QR code to establish a secure PQC session first!");
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
    return (
      <View style={StyleSheet.absoluteFill}>
        <Camera
          style={StyleSheet.absoluteFill}
          scanBarcode={true}
          allowedBarcodeTypes={['qr']}
          onReadCode={async (event: any) => {
            const scannedValue = event.nativeEvent.codeStringValue;
            if (scannedValue) {
              let extractedMac = '';

              try {
                const parsed = JSON.parse(scannedValue);
                if (parsed.m) extractedMac = parsed.m;
                else if (parsed.mac) extractedMac = parsed.mac;
              } catch (e) {
                extractedMac = scannedValue;
              }

              if (extractedMac) {
                setTargetDevice(extractedMac);
                setIsScanning(false);
                Alert.alert("QR Code Scanned!", `Target device (${extractedMac}) found. Exchanging PQC keys...`);
                try {
                  await BLEMeshModule.requestPqcKeysOverBle(extractedMac);
                } catch (err: any) {
                  console.warn("BLE Key Request notice:", err);
                }
              }
            }
          }}
          showFrame={true}
          laserColor="#3B82F6"
          frameColor="#FFFFFF"
        />
        <View style={styles.cancelScanArea}>
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
              <View style={{ padding: 12, backgroundColor: 'white', borderRadius: 8, elevation: 4 }}>
                <QRCode 
                  value={qrPayload} 
                  size={260} 
                  ecl="L" 
                  quietZone={10} 
                />
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
          
          {targetDevice || theirKeys ? (
            <View style={styles.statusBox}>
              <Text style={styles.statusText}>✓ Partner Device: {targetDevice || 'Auto-Detected'}</Text>
              {handshakeDone ? (
                <Text style={[styles.statusText, { color: '#059669', marginTop: 4 }]}>🔒 Post-Quantum Handshake: Complete</Text>
              ) : (
                <Text style={[styles.statusText, { color: '#D97706', marginTop: 4 }]}>⌛ Exchanging PQC keys over BLE...</Text>
              )}
            </View>
          ) : (
            <Text style={styles.placeholderText}>Scan partner's QR code to automatically establish secure PQC session.</Text>
          )}

          {/* Manual Fallback Toggle */}
          <TouchableOpacity onPress={() => setShowManualSection(!showManualSection)} style={{ marginTop: 8 }}>
            <Text style={{ fontSize: 11, color: '#6B7280', textDecorationLine: 'underline' }}>
              {showManualSection ? "Hide Manual Key Exchange" : "Option: Manual Key Copy/Paste (Advanced)"}
            </Text>
          </TouchableOpacity>

          {showManualSection && (
            <View style={{ marginTop: 8, padding: 8, backgroundColor: '#F9FAFB', borderRadius: 6, borderWidth: 1, borderColor: '#E5E7EB' }}>
              <Text style={styles.label}>My Key String:</Text>
              <TextInput style={styles.keyBox} multiline value={myKeys} editable={false} selectTextOnFocus />
              
              <Text style={[styles.label, { marginTop: 8 }]}>Paste Friend's Key String:</Text>
              <TextInput 
                style={[styles.keyBox, { height: 50 }]} 
                multiline 
                placeholder='{"x25519":"...","kyber":"..."}' 
                value={manualKeyInput}
                onChangeText={setManualKeyInput}
              />
              <View style={{ marginTop: 8 }}>
                <Button title="Perform Manual Handshake" onPress={() => performHandshakeWithKeys(manualKeyInput)} color="#8B5CF6" />
              </View>
            </View>
          )}
        </View>

        {/* Step 3: Mesh Status */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>3. Mesh Network Status</Text>
          <View style={[styles.statusBox, { backgroundColor: '#EFF6FF', borderColor: '#BFDBFE', marginBottom: 0 }]}>
            <Text style={[styles.statusText, { color: '#1E40AF' }]}>
              {meshActive ? "📡 BLE GATT Server & Advertising: Active" : "📡 Initializing BLE Mesh Node..."}
            </Text>
            <Text style={[styles.statusText, { color: '#1E40AF', marginTop: 4 }]}>
              📦 Packets Relayed via Mesh: {relayedCount}
            </Text>
          </View>
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
  label: { fontSize: 12, color: '#4B5563', marginBottom: 4, fontWeight: '500' },
  keyBox: { fontFamily: 'monospace', fontSize: 10, color: '#6B7280', backgroundColor: '#FFFFFF', padding: 6, borderRadius: 4, borderWidth: 1, borderColor: '#D1D5DB', maxHeight: 60 },
  statusBox: { backgroundColor: '#ECFDF5', padding: 10, borderRadius: 6, borderWidth: 1, borderColor: '#A7F3D0', marginBottom: 8 },
  statusText: { fontSize: 12, color: '#065F46', fontWeight: '600' },
  placeholderText: { fontSize: 12, color: '#9CA3AF', fontStyle: 'italic', marginVertical: 4 },
  chatArea: { marginTop: 8, paddingBottom: 40 },
  messageBubble: { padding: 12, borderRadius: 12, marginBottom: 8, maxWidth: '85%' },
  myMessage: { backgroundColor: '#3B82F6', alignSelf: 'flex-end', borderBottomRightRadius: 2 },
  theirMessage: { backgroundColor: '#E5E7EB', alignSelf: 'flex-start', borderBottomLeftRadius: 2 },
  messageSender: { fontSize: 10, color: '#D1D5DB', marginBottom: 4 },
  messageText: { fontSize: 16, color: '#FFFFFF' },
  inputArea: { padding: 16, backgroundColor: '#FFFFFF', borderTopWidth: 1, borderColor: '#E5E7EB' },
  row: { flexDirection: 'row', alignItems: 'center' },
  input: { borderWidth: 1, borderColor: '#D1D5DB', borderRadius: 6, padding: 10, backgroundColor: '#F9FAFB' },
  cancelScanArea: { position: 'absolute', bottom: 40, alignSelf: 'center', backgroundColor: 'rgba(0,0,0,0.5)', padding: 8, borderRadius: 8 }
});

export default function AppWrapper() {
  return (
    <SafeAreaProvider>
      <App />
    </SafeAreaProvider>
  );
}
