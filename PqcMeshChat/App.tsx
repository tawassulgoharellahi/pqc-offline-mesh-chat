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

interface Peer {
  address: string;
  name: string;
  rssi: number;
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
  
  const [discoveredPeers, setDiscoveredPeers] = useState<Peer[]>([]);
  const [isDiscovering, setIsDiscovering] = useState<boolean>(false);

  const [messages, setMessages] = useState<Message[]>([]);
  const [relayedCount, setRelayedCount] = useState<number>(0);
  const [inputText, setInputText] = useState('');
  
  const [handshakeDone, setHandshakeDone] = useState(false);
  const [isScanning, setIsScanning] = useState(false);
  const [meshActive, setMeshActive] = useState(false);

  const requestAndroidPermissions = async () => {
    if (Platform.OS === 'android') {
      try {
        await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.CAMERA,
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
        ]);
      } catch (err) {
        console.warn("Permissions error:", err);
      }
    }
  };

  const performHandshakeWithKeys = async (keysToUse: string) => {
    try {
      await CryptoModule.initiateHandshake(keysToUse);
      setHandshakeDone(true);
      Alert.alert("Session Secured! 🔒", "Post-Quantum Handshake complete!");
    } catch (e: any) {
      console.error("Handshake error:", e.message);
      Alert.alert("Handshake Failed", e.message);
    }
  };

  const initKeysAndAdvertising = async () => {
    try {
      await requestAndroidPermissions();
      await CryptoModule.generateKeys();
      const base64Keys = await CryptoModule.exportPublicKeysBase64();
      const mac = await BLEMeshModule.getMacAddress();

      setMyKeys(base64Keys);
      setMyMac(mac);
      setQrPayload(JSON.stringify({ m: mac }));

      await BLEMeshModule.startAdvertising();
      setMeshActive(true);
    } catch (err) {
      console.warn("Auto init notice:", err);
    }
  };

  useEffect(() => {
    initKeysAndAdvertising();

    const recvSub = bleEmitter.addListener('onMessageReceived', async (event) => {
      const { senderAddress, payload } = event;
      
      try {
        if (!handshakeDone) {
          console.warn("Received message before handshake!");
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
        console.error("Decrypt error:", e);
      }
    });

    const handshakeSub = bleEmitter.addListener('onHandshakeKeysReceived', async (event) => {
      const { senderAddress, keys } = event;
      setTheirKeys(keys);
      if (senderAddress) setTargetDevice(senderAddress);
      await performHandshakeWithKeys(keys);
    });

    const peerSub = bleEmitter.addListener('onPeerDiscovered', (peer: Peer) => {
      setDiscoveredPeers(prev => {
        if (prev.some(p => p.address === peer.address)) return prev;
        return [...prev, peer];
      });
    });

    const relaySub = bleEmitter.addListener('onMessageRelayed', (event) => {
      setRelayedCount(prev => prev + 1);
    });
    
    return () => {
      recvSub.remove();
      handshakeSub.remove();
      peerSub.remove();
      relaySub.remove();
    };
  }, [handshakeDone]);

  const startDiscovery = async () => {
    try {
      setDiscoveredPeers([]);
      setIsDiscovering(true);
      await BLEMeshModule.startPeerDiscovery();
      Alert.alert("Scanning Nearby Peers", "Searching for nearby PQC Mesh Chat nodes over BLE...");
    } catch (err: any) {
      Alert.alert("Discovery Error", err.message);
    }
  };

  const connectToPeer = async (peerAddress: string) => {
    try {
      setTargetDevice(peerAddress);
      Alert.alert("Connecting", `Requesting PQC keys from ${peerAddress} over BLE...`);
      await BLEMeshModule.requestPqcKeysOverBle(peerAddress);
    } catch (err: any) {
      console.warn("BLE connect notice:", err);
    }
  };

  const sendMessage = async () => {
    if (!inputText) return;
    if (!targetDevice) {
      Alert.alert("Error", "No peer connected. Discover nearby peers or scan QR code first!");
      return;
    }
    if (!handshakeDone) {
      Alert.alert("Error", "You must exchange keys with your peer to establish a PQC session first!");
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

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom', 'left', 'right']}>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <View style={styles.header}>
        <Text style={styles.title}>PQC Offline Mesh Chat</Text>
      </View>
      
      <ScrollView style={styles.content}>
        
        {/* Step 1: My Identity & Status */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>1. My Local Node Identity</Text>
          <View style={[styles.statusBox, { backgroundColor: '#ECFDF5', borderColor: '#A7F3D0' }]}>
            <Text style={styles.statusText}>🔑 PQC Kyber-768 & Dilithium Keys: Active</Text>
            <Text style={styles.statusText}>📡 Mesh Node ID: {myMac || "Initializing..."}</Text>
          </View>

          {qrPayload ? (
            <View style={{marginTop: 12, alignItems: 'center'}}>
              <Text style={styles.label}>My Scannable QR Code:</Text>
              <View style={{ padding: 10, backgroundColor: 'white', borderRadius: 8, elevation: 3 }}>
                <QRCode 
                  value={qrPayload} 
                  size={220} 
                  ecl="L" 
                  quietZone={8} 
                />
              </View>
            </View>
          ) : null}
        </View>

        {/* Step 2: Peer Discovery & Key Exchange */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>2. Discover & Pair Nearby Peers</Text>
          
          <View style={{ flexDirection: 'row', gap: 8, marginBottom: 12 }}>
            <View style={{ flex: 1 }}>
              <Button title="🔎 Discover Peers (BLE)" onPress={startDiscovery} color="#3B82F6" />
            </View>
            <View style={{ flex: 1 }}>
              <Button title="📷 Scan QR Code" onPress={() => setIsScanning(true)} color="#8B5CF6" />
            </View>
          </View>

          {/* Discovered Peers List */}
          {discoveredPeers.length > 0 && (
            <View style={{ marginTop: 8, marginBottom: 12 }}>
              <Text style={styles.label}>Discovered Nearby Nodes:</Text>
              {discoveredPeers.map(p => (
                <View key={p.address} style={styles.peerRow}>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontSize: 13, fontWeight: 'bold', color: '#1F2937' }}>{p.name}</Text>
                    <Text style={{ fontSize: 11, color: '#6B7280' }}>{p.address} (RSSI: {p.rssi}dBm)</Text>
                  </View>
                  <Button title="Connect & Pair" onPress={() => connectToPeer(p.address)} color="#10B981" />
                </View>
              ))}
            </View>
          )}

          {/* Connection Status */}
          {targetDevice ? (
            <View style={[styles.statusBox, { backgroundColor: handshakeDone ? '#ECFDF5' : '#FFFBEB', borderColor: handshakeDone ? '#A7F3D0' : '#FDE68A' }]}>
              <Text style={styles.statusText}>✓ Paired Peer: {targetDevice}</Text>
              {handshakeDone ? (
                <Text style={[styles.statusText, { color: '#059669', marginTop: 2 }]}>🔒 Kyber-768 Session: Encrypted & Ready</Text>
              ) : (
                <Text style={[styles.statusText, { color: '#D97706', marginTop: 2 }]}>⌛ Exchanging PQC keys over BLE...</Text>
              )}
            </View>
          ) : (
            <Text style={styles.placeholderText}>Tap "Discover Peers" or "Scan QR Code" to pair with a nearby phone.</Text>
          )}

          {/* Collapsible Manual Key Accordion */}
          <TouchableOpacity onPress={() => setShowManualSection(!showManualSection)} style={{ marginTop: 10 }}>
            <Text style={{ fontSize: 11, color: '#6B7280', textDecorationLine: 'underline' }}>
              {showManualSection ? "Hide Manual Key Copy/Paste" : "Option: Manual Key Copy/Paste (Advanced)"}
            </Text>
          </TouchableOpacity>

          {showManualSection && (
            <View style={{ marginTop: 8, padding: 8, backgroundColor: '#F9FAFB', borderRadius: 6, borderWidth: 1, borderColor: '#E5E7EB' }}>
              <Text style={styles.label}>My Key String (Copy):</Text>
              <TextInput style={styles.keyBox} multiline value={myKeys} editable={false} selectTextOnFocus />
              
              <Text style={[styles.label, { marginTop: 8 }]}>Paste Friend's Key String:</Text>
              <TextInput 
                style={[styles.keyBox, { height: 45 }]} 
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

        {/* Step 3: Mesh Network Status */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>3. Gossip Mesh Status</Text>
          <View style={[styles.statusBox, { backgroundColor: '#EFF6FF', borderColor: '#BFDBFE', marginBottom: 0 }]}>
            <Text style={[styles.statusText, { color: '#1E40AF' }]}>
              {meshActive ? "📡 BLE GATT & Mesh Routing: Active" : "📡 Initializing Mesh..."}
            </Text>
            <Text style={[styles.statusText, { color: '#1E40AF', marginTop: 2 }]}>
              📦 Multi-hop Relayed Packets: {relayedCount}
            </Text>
          </View>
        </View>

        {/* Encrypted Chat */}
        <View style={styles.chatArea}>
          <Text style={styles.sectionTitle}>Post-Quantum Encrypted Messages</Text>
          {messages.length === 0 ? (
            <Text style={styles.placeholderText}>No messages exchanged yet.</Text>
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

      {/* Camera Full Screen Overlay */}
      {isScanning && (
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
                  Alert.alert("QR Code Scanned!", `Target node (${extractedMac}) found. Initiating PQC key exchange over BLE...`);
                  try {
                    await BLEMeshModule.requestPqcKeysOverBle(extractedMac);
                  } catch (err: any) {
                    console.warn("BLE Request notice:", err);
                  }
                }
              }
            }}
            showFrame={false}
          />
          <View style={styles.viewFinderOverlay} pointerEvents="none">
            <View style={styles.viewFinderSquare} />
          </View>
          <View style={styles.cancelScanArea}>
            <Button title="Cancel Scan" onPress={() => setIsScanning(false)} color="#EF4444" />
          </View>
        </View>
      )}

      {/* Chat Footer */}
      <View style={styles.inputArea}>
        <View style={styles.row}>
          <TextInput 
            style={[styles.input, {flex: 1, marginRight: 8}]} 
            placeholder="Type encrypted message..." 
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
  title: { fontSize: 18, fontWeight: 'bold', color: '#F9FAFB' },
  content: { padding: 16, flex: 1 },
  card: { marginBottom: 16, padding: 16, backgroundColor: '#FFFFFF', borderRadius: 8, elevation: 2 },
  sectionTitle: { fontSize: 15, fontWeight: 'bold', marginBottom: 10, color: '#111827' },
  label: { fontSize: 12, color: '#4B5563', marginBottom: 4, fontWeight: '500' },
  keyBox: { fontFamily: 'monospace', fontSize: 10, color: '#6B7280', backgroundColor: '#FFFFFF', padding: 6, borderRadius: 4, borderWidth: 1, borderColor: '#D1D5DB', maxHeight: 60 },
  statusBox: { backgroundColor: '#ECFDF5', padding: 10, borderRadius: 6, borderWidth: 1, borderColor: '#A7F3D0', marginBottom: 8 },
  statusText: { fontSize: 12, color: '#065F46', fontWeight: '600' },
  peerRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#F3F4F6', padding: 8, borderRadius: 6, marginBottom: 6 },
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
  cancelScanArea: { position: 'absolute', bottom: 40, alignSelf: 'center', backgroundColor: 'rgba(0,0,0,0.5)', padding: 8, borderRadius: 8 },
  viewFinderOverlay: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, justifyContent: 'center', alignItems: 'center' },
  viewFinderSquare: { width: 250, height: 250, borderWidth: 2, borderColor: '#3B82F6', borderRadius: 16, backgroundColor: 'transparent' }
});

export default function AppWrapper() {
  return (
    <SafeAreaProvider>
      <App />
    </SafeAreaProvider>
  );
}
