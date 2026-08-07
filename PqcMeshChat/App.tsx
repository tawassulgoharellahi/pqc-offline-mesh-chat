import React, { useState, useEffect, useRef } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
  TextInput,
  TouchableOpacity,
  FlatList,
  Alert,
  NativeModules,
  NativeEventEmitter,
  useColorScheme,
  PermissionsAndroid,
  Platform,
  ScrollView,
  KeyboardAvoidingView,
} from 'react-native';
import { Camera } from 'react-native-camera-kit';
import QRCode from 'react-native-qrcode-svg';

const { CryptoModule, BLEMeshModule } = NativeModules;
const bleEmitter = BLEMeshModule ? new NativeEventEmitter(BLEMeshModule) : null;

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

export default function App() {
  const isDarkMode = useColorScheme() === 'dark';
  
  const [myKeys, setMyKeys] = useState<string>('');
  const [myMac, setMyMac] = useState<string>('');
  const [theirKeys, setTheirKeys] = useState<string>('');
  const [manualKeyInput, setManualKeyInput] = useState<string>('');
  const [targetDevice, setTargetDevice] = useState<string>('');
  const [handshakeDone, setHandshakeDone] = useState<boolean>(false);
  const handshakeDoneRef = useRef<boolean>(false);

  const [inputText, setInputText] = useState<string>('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [relayedCount, setRelayedCount] = useState<number>(0);
  const [meshActive, setMeshActive] = useState<boolean>(false);

  // Peer Discovery State
  const [discoveredPeers, setDiscoveredPeers] = useState<Peer[]>([]);
  const [isDiscovering, setIsDiscovering] = useState<boolean>(false);

  // QR Scanning State
  const [isScanning, setIsScanning] = useState<boolean>(false);
  const [qrPayload, setQrPayload] = useState<string>('');
  const [showManualSection, setShowManualSection] = useState<boolean>(false);

  const updateHandshakeState = (done: boolean) => {
    handshakeDoneRef.current = done;
    setHandshakeDone(done);
  };

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
      updateHandshakeState(true);
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
      try {
        await BLEMeshModule.startPeerDiscovery();
      } catch (e) {
        console.warn("Auto scan notice:", e);
      }
      setMeshActive(true);
    } catch (err) {
      console.warn("Auto init notice:", err);
    }
  };

  useEffect(() => {
    initKeysAndAdvertising();

    const recvSub = bleEmitter?.addListener('onMessageReceived', async (event) => {
      const { senderAddress, payload } = event;
      
      try {
        if (!handshakeDoneRef.current) {
          console.warn("Received message before handshake done!");
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

    const handshakeSub = bleEmitter?.addListener('onHandshakeKeysReceived', async (event) => {
      const { senderAddress, keys } = event;
      setTheirKeys(keys);
      if (senderAddress) setTargetDevice(senderAddress);
      await performHandshakeWithKeys(keys);
    });

    const peerSub = bleEmitter?.addListener('onPeerDiscovered', (peer: Peer) => {
      setDiscoveredPeers(prev => {
        if (prev.some(p => p.address === peer.address)) return prev;
        return [...prev, peer];
      });
    });

    const relaySub = bleEmitter?.addListener('onMessageRelayed', (event) => {
      setRelayedCount(prev => prev + 1);
    });
    
    return () => {
      recvSub?.remove();
      handshakeSub?.remove();
      peerSub?.remove();
      relaySub?.remove();
    };
  }, []);

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
      Alert.alert("Connecting & Pairing", `Requesting 2-Way PQC Key Exchange from ${peerAddress} over BLE...`);
      await BLEMeshModule.requestPqcKeysOverBle(peerAddress, myMac);
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
      await BLEMeshModule.sendMessageToDevice(targetDevice, ciphertextBase64, myMac);
      
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
        <Text style={styles.title}>PQC Offline Mesh Chat</Text>
      </View>
      
      <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 80 }}>
        
        {/* Step 1: My Identity & Status */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>1. My Local Node Identity</Text>
          <View style={[styles.statusBox, { backgroundColor: '#ECFDF5', borderColor: '#A7F3D0' }]}>
            <Text style={styles.statusText}>🔑 PQC Kyber-768 & Dilithium Keys: Active</Text>
            <Text style={styles.statusText}>📡 Mesh Node ID: {myMac || "Initializing..."}</Text>
          </View>
          
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 10 }}>
            <TouchableOpacity 
              style={[styles.smallBtn, { backgroundColor: '#2563EB' }]} 
              onPress={startDiscovery}
            >
              <Text style={styles.btnText}>🔎 Discover Peers (BLE)</Text>
            </TouchableOpacity>

            <TouchableOpacity 
              style={[styles.smallBtn, { backgroundColor: '#7C3AED' }]} 
              onPress={() => setIsScanning(true)}
            >
              <Text style={styles.btnText}>📷 Scan QR Code</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Discovered Peers Section */}
        {discoveredPeers.length > 0 && (
          <View style={styles.card}>
            <Text style={styles.sectionTitle}>Nearby Discovered Peers ({discoveredPeers.length})</Text>
            {discoveredPeers.map(peer => (
              <View key={peer.address} style={styles.peerRow}>
                <View style={{ flex: 1 }}>
                  <Text style={{ fontWeight: '600', color: '#1F2937' }}>{peer.name}</Text>
                  <Text style={{ fontSize: 11, color: '#6B7280' }}>MAC: {peer.address} | RSSI: {peer.rssi} dBm</Text>
                </View>
                <TouchableOpacity 
                  style={[styles.smallBtn, { backgroundColor: '#059669', paddingHorizontal: 12 }]}
                  onPress={() => connectToPeer(peer.address)}
                >
                  <Text style={styles.btnText}>Connect & Pair</Text>
                </TouchableOpacity>
              </View>
            ))}
          </View>
        )}

        {/* Step 2: Connected Peer Status */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>2. Active Connected Peer</Text>
          {targetDevice ? (
            <View style={[styles.statusBox, { backgroundColor: handshakeDone ? '#ECFDF5' : '#FEF3C7', borderColor: handshakeDone ? '#A7F3D0' : '#FDE68A' }]}>
              <Text style={styles.statusText}>📱 Target Peer: {targetDevice}</Text>
              {handshakeDone ? (
                <Text style={[styles.statusText, { color: '#059669', marginTop: 2 }]}>🔒 Kyber-768 Session: Encrypted & Ready</Text>
              ) : (
                <Text style={[styles.statusText, { color: '#D97706', marginTop: 2 }]}>⌛ Exchanging PQC keys over BLE...</Text>
              )}
            </View>
          ) : (
            <Text style={styles.placeholderText}>Tap "Discover Peers" or "Scan QR Code" to pair with a nearby phone.</Text>
          )}

          {targetDevice && !handshakeDone && (
            <TouchableOpacity 
              style={[styles.button, { backgroundColor: '#D97706', marginTop: 10 }]} 
              onPress={() => connectToPeer(targetDevice)}
            >
              <Text style={styles.buttonText}>⚡ Retry Key Exchange Over BLE</Text>
            </TouchableOpacity>
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
                <TouchableOpacity style={[styles.button, { backgroundColor: '#059669' }]} onPress={() => performHandshakeWithKeys(manualKeyInput)}>
                  <Text style={styles.buttonText}>Establish Manual Handshake</Text>
                </TouchableOpacity>
              </View>
            </View>
          )}
        </View>

        {/* Step 3: QR Code Generator */}
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>3. Pair via QR Code</Text>
          {qrPayload ? (
            <View style={{ alignItems: 'center', marginVertical: 8 }}>
              <QRCode value={qrPayload} size={150} />
              <Text style={{ fontSize: 11, color: '#6B7280', marginTop: 6 }}>Show this QR code to partner phone to connect instantly</Text>
            </View>
          ) : (
            <Text style={styles.placeholderText}>Generating identity QR code...</Text>
          )}
        </View>

        {/* Step 4: Encrypted PQC Chat Area */}
        <KeyboardAvoidingView 
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
          style={styles.card}
        >
          <Text style={styles.sectionTitle}>4. PQC Encrypted Chat (Kyber-768 + AES-256)</Text>
          
          <ScrollView 
            style={styles.chatBox}
            showsVerticalScrollIndicator={false}
            nestedScrollEnabled={true}
          >
            {messages.length === 0 ? (
              <Text style={styles.placeholderText}>No messages yet. Send a post-quantum encrypted message!</Text>
            ) : (
              messages.map(msg => (
                <View key={msg.id} style={[styles.msgBubble, msg.isMine ? styles.myMsg : styles.theirMsg]}>
                  <Text style={styles.msgText}>{msg.text}</Text>
                  <Text style={styles.msgSender}>{msg.sender}</Text>
                </View>
              ))
            )}
          </ScrollView>

          <View style={styles.inputContainer}>
            <TextInput
              style={styles.input}
              placeholder="Type encrypted message..."
              placeholderTextColor="#9CA3AF"
              value={inputText}
              onChangeText={setInputText}
            />
            <TouchableOpacity style={styles.sendButton} onPress={sendMessage}>
              <Text style={styles.sendText}>Send</Text>
            </TouchableOpacity>
          </View>
        </KeyboardAvoidingView>

      </ScrollView>

      {/* Full-Screen Camera Scanner Modal */}
      {isScanning && (
        <View style={StyleSheet.absoluteFill}>
          <Camera
            style={StyleSheet.absoluteFill}
            scanBarcode={true}
            allowedBarcodeTypes={['qr']}
            showFrame={false}
            onReadCode={(event: any) => {
              const scannedValue = event.nativeEvent?.codeStringValue;
              if (scannedValue) {
                setIsScanning(false);
                try {
                  const data = JSON.parse(scannedValue);
                  if (data.m) {
                    setTargetDevice(data.m);
                    connectToPeer(data.m);
                    Alert.alert("QR Code Paired! 🎉", `Connected to device MAC: ${data.m}. Exchanging PQC Keys over BLE...`);
                  }
                } catch (e) {
                  Alert.alert("Scan Result", `Scanned value: ${scannedValue}`);
                }
              }
            }}
          />

          {/* Viewfinder Frame */}
          <View style={styles.overlay}>
            <View style={styles.viewfinder} />
            <Text style={styles.scanInstruction}>Center partner's QR code inside the box</Text>
            <TouchableOpacity style={styles.closeBtn} onPress={() => setIsScanning(false)}>
              <Text style={styles.closeBtnText}>Cancel Scan</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
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
    fontSize: 18,
    fontWeight: 'bold',
    color: '#F9FAFB',
  },
  content: {
    padding: 12,
  },
  card: {
    backgroundColor: '#FFFFFF',
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: '#374151',
    marginBottom: 8,
  },
  statusBox: {
    padding: 8,
    borderRadius: 6,
    borderWidth: 1,
  },
  statusText: {
    fontSize: 12,
    color: '#065F46',
    fontWeight: '500',
  },
  placeholderText: {
    fontSize: 12,
    color: '#9CA3AF',
    fontStyle: 'italic',
  },
  button: {
    paddingVertical: 8,
    borderRadius: 6,
    alignItems: 'center',
  },
  buttonText: {
    color: '#FFFFFF',
    fontWeight: '600',
    fontSize: 13,
  },
  smallBtn: {
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 6,
  },
  btnText: {
    color: '#FFFFFF',
    fontSize: 11,
    fontWeight: '600',
  },
  peerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: '#F3F4F6',
  },
  label: {
    fontSize: 11,
    fontWeight: '600',
    color: '#4B5563',
  },
  keyBox: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#D1D5DB',
    borderRadius: 4,
    padding: 6,
    fontSize: 10,
    height: 40,
    marginTop: 4,
  },
  chatBox: {
    minHeight: 80,
    maxHeight: 140,
    backgroundColor: '#F9FAFB',
    borderRadius: 6,
    padding: 8,
    marginBottom: 8,
  },
  msgBubble: {
    padding: 8,
    borderRadius: 8,
    marginBottom: 6,
    maxWidth: '80%',
  },
  myMsg: {
    backgroundColor: '#DBEAFE',
    alignSelf: 'flex-end',
  },
  theirMsg: {
    backgroundColor: '#E5E7EB',
    alignSelf: 'flex-start',
  },
  msgText: {
    fontSize: 13,
    color: '#1F2937',
  },
  msgSender: {
    fontSize: 9,
    color: '#6B7280',
    marginTop: 2,
  },
  inputContainer: {
    flexDirection: 'row',
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#D1D5DB',
    borderRadius: 6,
    paddingHorizontal: 10,
    fontSize: 13,
    backgroundColor: '#FFFFFF',
    height: 40,
  },
  sendButton: {
    backgroundColor: '#2563EB',
    justifyContent: 'center',
    paddingHorizontal: 16,
    borderRadius: 6,
    marginLeft: 8,
  },
  sendText: {
    color: '#FFFFFF',
    fontWeight: '600',
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  viewfinder: {
    width: 240,
    height: 240,
    borderWidth: 3,
    borderColor: '#00FF00',
    borderRadius: 12,
    backgroundColor: 'transparent',
  },
  scanInstruction: {
    color: '#FFFFFF',
    marginTop: 16,
    fontSize: 14,
    fontWeight: '600',
  },
  closeBtn: {
    marginTop: 24,
    backgroundColor: '#EF4444',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 8,
  },
  closeBtnText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
  },
});
