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
  Modal,
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
  time: string;
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
  const [showPeersModal, setShowPeersModal] = useState<boolean>(false);

  // QR Scanning & Display Modals
  const [isScanning, setIsScanning] = useState<boolean>(false);
  const [showMyQrModal, setShowMyQrModal] = useState<boolean>(false);
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

  const performHandshakeWithKeys = async (keysJsonString: string) => {
    try {
      if (!keysJsonString) return;
      await CryptoModule.initiateHandshake(keysJsonString);
      updateHandshakeState(true);
      console.log("Handshake success!");
    } catch (e: any) {
      console.error("Handshake error:", e.message);
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
      setQrPayload(JSON.stringify({ m: mac, k: base64Keys }));

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
        const plaintext = await CryptoModule.decryptMessage(payload);
        const now = new Date();
        const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;
        
        setMessages(prev => [...prev, {
          id: Math.random().toString(),
          sender: senderAddress || 'Peer',
          text: plaintext,
          isMine: false,
          time: timeStr
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
      setShowPeersModal(true);
      await BLEMeshModule.startPeerDiscovery();
    } catch (err: any) {
      Alert.alert("Discovery Error", err.message);
    }
  };

  const connectToPeer = async (peerAddress: string) => {
    try {
      setTargetDevice(peerAddress);
      setShowPeersModal(false);
      Alert.alert("Connecting & Pairing", `Requesting PQC Key Exchange from ${peerAddress} over BLE...`);
      await BLEMeshModule.requestPqcKeysOverBle(peerAddress, myMac);
    } catch (err: any) {
      console.warn("BLE connect notice:", err);
    }
  };

  const sendMessage = async () => {
    if (!inputText) return;
    if (!targetDevice) {
      Alert.alert("No Peer Selected", "Please scan a peer's QR code or select a peer to start messaging!");
      return;
    }
    if (!handshakeDone) {
      Alert.alert("Encryption Notice", "Exchanging PQC keys with your peer... Please tap Retry if needed.");
      return;
    }
    
    try {
      const ciphertextBase64 = await CryptoModule.encryptMessage(inputText);
      await BLEMeshModule.sendMessageToDevice(targetDevice, ciphertextBase64, myMac);
      
      const now = new Date();
      const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;

      setMessages(prev => [...prev, {
        id: Math.random().toString(),
        sender: 'Me',
        text: inputText,
        isMine: true,
        time: timeStr
      }]);
      setInputText('');
    } catch (e: any) {
      Alert.alert("Send Error", e.message);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#075E54" />

      {/* WhatsApp Style Top Header Bar */}
      <View style={styles.header}>
        <View style={styles.headerLeft}>
          <Text style={styles.headerTitle}>PQC Offline Chat</Text>
          <View style={styles.statusRow}>
            <View style={styles.activeDot} />
            <Text style={styles.headerSubtitle}>
              Node: {myMac ? myMac.slice(-10) : "Active"} • Kyber-768
            </Text>
          </View>
        </View>

        <View style={styles.headerRight}>
          <TouchableOpacity style={styles.iconBtn} onPress={() => setShowMyQrModal(true)}>
            <Text style={styles.iconText}>🔳 QR</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.iconBtnAction} onPress={() => setIsScanning(true)}>
            <Text style={styles.iconTextAction}>📷 Scan</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Active Peer Status Banner */}
      <View style={styles.peerBanner}>
        {targetDevice ? (
          <View style={styles.bannerRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.peerText}>Connected Peer: {targetDevice}</Text>
              <Text style={styles.pqcStatus}>
                {handshakeDone ? "🔒 Kyber-768 + AES-256 Encrypted Session Active" : "⌛ Exchanging PQC keys..."}
              </Text>
            </View>
            {!handshakeDone && (
              <TouchableOpacity style={styles.retryBtn} onPress={() => connectToPeer(targetDevice)}>
                <Text style={styles.retryText}>Retry Key</Text>
              </TouchableOpacity>
            )}
          </View>
        ) : (
          <TouchableOpacity style={styles.pairNoticeRow} onPress={() => setIsScanning(true)}>
            <Text style={styles.pairNoticeText}>
              💡 Scan partner's QR Code or tap "Discover Peers" to connect
            </Text>
          </TouchableOpacity>
        )}
      </View>

      {/* WhatsApp Chat Body & Input Container */}
      <KeyboardAvoidingView 
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        style={{ flex: 1 }}
        keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
      >
        <ScrollView 
          style={styles.chatBackground}
          contentContainerStyle={{ padding: 12, paddingBottom: 24 }}
          showsVerticalScrollIndicator={false}
        >
          {messages.length === 0 ? (
            <View style={styles.emptyContainer}>
              <View style={styles.securityBadge}>
                <Text style={styles.securityTitle}>🔒 Post-Quantum Encrypted</Text>
                <Text style={styles.securityDesc}>
                  Messages are end-to-end encrypted using Kyber-768 & AES-256-GCM. No internet or cell towers required.
                </Text>
              </View>
            </View>
          ) : (
            messages.map(msg => (
              <View key={msg.id} style={[styles.msgBubble, msg.isMine ? styles.myMsg : styles.theirMsg]}>
                {!msg.isMine && <Text style={styles.senderTag}>{msg.sender}</Text>}
                <Text style={msg.isMine ? styles.myMsgText : styles.theirMsgText}>{msg.text}</Text>
                <View style={styles.msgFooter}>
                  <Text style={msg.isMine ? styles.myTimeText : styles.theirTimeText}>{msg.time}</Text>
                  {msg.isMine && <Text style={styles.lockIcon}> 🔒</Text>}
                </View>
              </View>
            ))
          )}
        </ScrollView>

        {/* WhatsApp Footer Input Bar */}
        <View style={styles.footerInputContainer}>
          <TextInput
            style={styles.textInput}
            placeholder="Type encrypted message..."
            placeholderTextColor="#8696A0"
            value={inputText}
            onChangeText={setInputText}
            multiline
          />
          <TouchableOpacity 
            style={[styles.sendCircleBtn, { backgroundColor: inputText.trim() ? '#128C7E' : '#9CA3AF' }]} 
            onPress={sendMessage}
            disabled={!inputText.trim()}
          >
            <Text style={styles.sendIcon}>➤</Text>
          </TouchableOpacity>
        </View>
      </KeyboardAvoidingView>

      {/* Modal: My Identity QR Code */}
      <Modal visible={showMyQrModal} animationType="slide" transparent={true}>
        <View style={styles.modalOverlay}>
          <View style={styles.qrModalContent}>
            <Text style={styles.modalTitle}>Pair via QR Code</Text>
            <Text style={styles.modalSubtitle}>Show this QR code to partner phone to pair with instant PQC encryption</Text>
            
            {qrPayload ? (
              <View style={styles.qrWrapper}>
                <QRCode value={qrPayload} size={220} />
              </View>
            ) : (
              <Text style={{ fontStyle: 'italic', color: '#6B7280' }}>Generating QR Code...</Text>
            )}

            <Text style={styles.nodeIdBadge}>My Node: {myMac}</Text>

            <TouchableOpacity style={styles.modalCloseBtn} onPress={() => setShowMyQrModal(false)}>
              <Text style={styles.modalCloseText}>Done</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Modal: Nearby Discovered Peers */}
      <Modal visible={showPeersModal} animationType="slide" transparent={true}>
        <View style={styles.modalOverlay}>
          <View style={styles.peersModalContent}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Text style={styles.modalTitle}>Nearby Discovered Peers</Text>
              <TouchableOpacity onPress={() => setShowPeersModal(false)}>
                <Text style={{ fontSize: 18, color: '#6B7280', fontWeight: 'bold' }}>✕</Text>
              </TouchableOpacity>
            </View>

            {discoveredPeers.length === 0 ? (
              <View style={{ padding: 20, alignItems: 'center' }}>
                <Text style={{ color: '#6B7280', fontStyle: 'italic' }}>Searching for nearby PQC nodes over BLE...</Text>
              </View>
            ) : (
              discoveredPeers.map(peer => (
                <View key={peer.address} style={styles.peerCard}>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontWeight: 'bold', fontSize: 14, color: '#111827' }}>{peer.name}</Text>
                    <Text style={{ fontSize: 11, color: '#6B7280' }}>MAC: {peer.address} | RSSI: {peer.rssi} dBm</Text>
                  </View>
                  <TouchableOpacity style={styles.connectBtn} onPress={() => connectToPeer(peer.address)}>
                    <Text style={styles.connectBtnText}>Connect</Text>
                  </TouchableOpacity>
                </View>
              ))
            )}

            <TouchableOpacity style={styles.modalCloseBtn} onPress={() => setShowPeersModal(false)}>
              <Text style={styles.modalCloseText}>Close</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Full-Screen Camera Scanner Modal */}
      {isScanning && (
        <View style={StyleSheet.absoluteFill}>
          <Camera
            style={StyleSheet.absoluteFill}
            scanBarcode={true}
            allowedBarcodeTypes={['qr']}
            showFrame={false}
            onReadCode={async (event: any) => {
              const scannedValue = event.nativeEvent?.codeStringValue;
              if (scannedValue) {
                setIsScanning(false);
                try {
                  const data = JSON.parse(scannedValue);
                  if (data.m) {
                    const matchedPeer = discoveredPeers.find(p => p.address === data.m || p.name.includes(data.m));
                    const resolvedMac = matchedPeer ? matchedPeer.address : (data.m.includes(':') ? data.m : (discoveredPeers[0]?.address || data.m));
                    
                    setTargetDevice(resolvedMac);

                    if (data.k) {
                      setTheirKeys(data.k);
                      await performHandshakeWithKeys(data.k);
                      await BLEMeshModule.requestPqcKeysOverBle(resolvedMac, myMac);
                      Alert.alert("QR Pair Successful! 🔒", `Established Kyber-768 PQC session with peer!`);
                    } else {
                      connectToPeer(resolvedMac);
                    }
                  }
                } catch (e) {
                  Alert.alert("QR Read Notice", scannedValue);
                }
              }
            }}
          />

          <View style={styles.overlay}>
            <View style={styles.viewfinder} />
            <Text style={styles.scanInstruction}>Center partner's QR code inside box to pair</Text>
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
    backgroundColor: '#075E54',
  },
  header: {
    backgroundColor: '#075E54',
    paddingHorizontal: 16,
    paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight || 28) + 8 : 12,
    paddingBottom: 12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    elevation: 4,
  },
  headerLeft: {
    flex: 1,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#FFFFFF',
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 2,
  },
  activeDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#25D366',
    marginRight: 6,
  },
  headerSubtitle: {
    fontSize: 11,
    color: '#E0F2F1',
  },
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  iconBtn: {
    backgroundColor: 'rgba(255, 255, 255, 0.15)',
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 16,
    marginRight: 8,
  },
  iconText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '600',
  },
  iconBtnAction: {
    backgroundColor: '#25D366',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 16,
  },
  iconTextAction: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: 'bold',
  },
  peerBanner: {
    backgroundColor: '#128C7E',
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  bannerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  peerText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
    fontSize: 13,
  },
  pqcStatus: {
    color: '#B2DFDB',
    fontSize: 11,
    marginTop: 1,
  },
  retryBtn: {
    backgroundColor: '#25D366',
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderRadius: 12,
  },
  retryText: {
    color: '#FFFFFF',
    fontSize: 11,
    fontWeight: 'bold',
  },
  pairNoticeRow: {
    alignItems: 'center',
  },
  pairNoticeText: {
    color: '#E0F2F1',
    fontSize: 12,
    fontStyle: 'italic',
  },
  chatBackground: {
    flex: 1,
    backgroundColor: '#ECE5DD',
  },
  emptyContainer: {
    alignItems: 'center',
    marginTop: 40,
    paddingHorizontal: 20,
  },
  securityBadge: {
    backgroundColor: '#FCF8E3',
    borderColor: '#FBEED5',
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    alignItems: 'center',
  },
  securityTitle: {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#8A6D3B',
    marginBottom: 4,
  },
  securityDesc: {
    fontSize: 11,
    color: '#8A6D3B',
    textAlign: 'center',
  },
  msgBubble: {
    padding: 10,
    borderRadius: 14,
    marginBottom: 8,
    maxWidth: '82%',
    elevation: 1,
  },
  myMsg: {
    backgroundColor: '#DCF8C6',
    alignSelf: 'flex-end',
    borderBottomRightRadius: 2,
  },
  theirMsg: {
    backgroundColor: '#FFFFFF',
    alignSelf: 'flex-start',
    borderBottomLeftRadius: 2,
  },
  myMsgText: {
    color: '#111827',
    fontSize: 14,
  },
  theirMsgText: {
    color: '#111827',
    fontSize: 14,
  },
  senderTag: {
    fontSize: 10,
    fontWeight: 'bold',
    color: '#075E54',
    marginBottom: 2,
  },
  msgFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    marginTop: 4,
  },
  myTimeText: {
    fontSize: 9,
    color: '#6B7280',
  },
  theirTimeText: {
    fontSize: 9,
    color: '#9CA3AF',
  },
  lockIcon: {
    fontSize: 9,
  },
  footerInputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#F0F0F0',
    paddingHorizontal: 10,
    paddingTop: 10,
    paddingBottom: Platform.OS === 'android' ? 50 : 20,
    marginBottom: Platform.OS === 'android' ? 10 : 0,
    borderTopWidth: 1,
    borderTopColor: '#E0E0E0',
  },
  textInput: {
    flex: 1,
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    paddingHorizontal: 16,
    paddingVertical: 8,
    fontSize: 14,
    color: '#111827',
    maxHeight: 100,
    marginRight: 8,
    elevation: 1,
  },
  sendCircleBtn: {
    width: 42,
    height: 42,
    borderRadius: 21,
    alignItems: 'center',
    justifyContent: 'center',
    elevation: 2,
  },
  sendIcon: {
    color: '#FFFFFF',
    fontSize: 16,
    marginLeft: 2,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  qrModalContent: {
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    padding: 24,
    alignItems: 'center',
    width: '90%',
    elevation: 5,
  },
  peersModalContent: {
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    padding: 20,
    width: '90%',
    maxHeight: '80%',
    elevation: 5,
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#111827',
  },
  modalSubtitle: {
    fontSize: 12,
    color: '#6B7280',
    textAlign: 'center',
    marginTop: 4,
    marginBottom: 16,
  },
  qrWrapper: {
    padding: 16,
    backgroundColor: '#FFFFFF',
    borderRadius: 12,
    elevation: 3,
    marginBottom: 16,
  },
  nodeIdBadge: {
    fontSize: 11,
    color: '#075E54',
    fontWeight: 'bold',
    backgroundColor: '#E0F2F1',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 12,
    marginBottom: 16,
  },
  modalCloseBtn: {
    backgroundColor: '#075E54',
    paddingVertical: 10,
    paddingHorizontal: 24,
    borderRadius: 20,
  },
  modalCloseText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
    fontSize: 14,
  },
  peerCard: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#F3F4F6',
  },
  connectBtn: {
    backgroundColor: '#128C7E',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 14,
  },
  connectBtnText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
    fontSize: 12,
  },
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.6)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  viewfinder: {
    width: 250,
    height: 250,
    borderWidth: 2,
    borderColor: '#25D366',
    borderRadius: 16,
    backgroundColor: 'transparent',
  },
  scanInstruction: {
    color: '#FFFFFF',
    fontSize: 13,
    marginTop: 16,
    fontWeight: '600',
  },
  closeBtn: {
    marginTop: 24,
    backgroundColor: '#EF4444',
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 20,
  },
  closeBtnText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
  },
});
