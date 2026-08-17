import React, { useState, useEffect, useRef } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
  TextInput,
  Pressable,
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
  useWindowDimensions,
} from 'react-native';
import { Camera } from 'react-native-camera-kit';
import QRCode from 'react-native-qrcode-svg';
import Svg, { Path, Rect, Circle } from 'react-native-svg';

const { CryptoModule, BLEMeshModule } = NativeModules;
const bleEmitter = BLEMeshModule ? new NativeEventEmitter(BLEMeshModule) : null;

// Custom SVG Icons
const QrIcon = ({ size = 20, color = "#FFFFFF" }: { size?: number; color?: string }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Rect x="3" y="3" width="7" height="7" rx="1.5" stroke={color} strokeWidth="2" />
    <Rect x="5" y="5" width="3" height="3" fill={color} />
    <Rect x="14" y="3" width="7" height="7" rx="1.5" stroke={color} strokeWidth="2" />
    <Rect x="16" y="5" width="3" height="3" fill={color} />
    <Rect x="3" y="14" width="7" height="7" rx="1.5" stroke={color} strokeWidth="2" />
    <Rect x="5" y="16" width="3" height="3" fill={color} />
    <Rect x="14" y="14" width="3" height="3" fill={color} />
    <Rect x="18" y="14" width="3" height="3" fill={color} />
    <Rect x="14" y="18" width="7" height="3" fill={color} />
  </Svg>
);

const CameraIcon = ({ size = 20, color = "#FFFFFF" }: { size?: number; color?: string }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Path 
      d="M 4 8 C 4 6.9 4.9 6 6 6 L 8 6 L 9.5 4.5 C 9.8 4.2 10.4 4 11 4 L 13 4 C 13.6 4 14.2 4.2 14.5 4.5 L 16 6 L 18 6 C 19.1 6 20 6.9 20 8 L 20 17 C 20 18.1 19.1 19 18 19 L 6 19 C 4.9 19 4 18.1 4 17 Z" 
      stroke={color} 
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round" 
    />
    <Circle cx="12" cy="12" r="3.5" stroke={color} strokeWidth="2" />
  </Svg>
);

const SendIcon = ({ size = 20, color = "#FFFFFF" }: { size?: number; color?: string }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Path d="M 2.01 21 L 23 12 L 2.01 3 L 2 10 L 17 12 L 2 14 Z" fill={color} />
  </Svg>
);

const RefreshIcon = ({ size = 20, color = "#FFFFFF" }: { size?: number; color?: string }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Path 
      d="M 17.65 6.35 A 8 8 0 1 0 19.73 13 M 17.65 6.35 V 2 M 17.65 6.35 H 22" 
      stroke={color} 
      strokeWidth="2.2" 
      strokeLinecap="round" 
      strokeLinejoin="round"
    />
  </Svg>
);

interface Message {
  id: string;
  sender: string;
  text: string;
  isMine: boolean;
  time: string;
  status?: 'sent' | 'queued';
}

interface Peer {
  address: string;
  name: string;
  rssi: number;
}

export default function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const { width: windowWidth, height: windowHeight } = useWindowDimensions();
  
  // Responsive layout bounds
  const maxContentWidth = Math.min(windowWidth, 640);
  const isSmallScreen = windowWidth < 360;

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
  const scrollViewRef = useRef<ScrollView>(null);

  // Peer Discovery State
  const [discoveredPeers, setDiscoveredPeers] = useState<Peer[]>([]);
  const [isDiscovering, setIsDiscovering] = useState<boolean>(false);
  const [showPeersModal, setShowPeersModal] = useState<boolean>(false);
  const [isBtEnabled, setIsBtEnabled] = useState<boolean>(true);

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
        const permissions: any[] = [
          PermissionsAndroid.PERMISSIONS.CAMERA,
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
        ];
        if (Platform.Version >= 31) {
          if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN) permissions.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN);
          if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT) permissions.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT);
          if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE) permissions.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE);
        }
        if (Platform.Version >= 33 && PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS) {
          permissions.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
        }
        await PermissionsAndroid.requestMultiple(permissions);
      } catch (err) {
        console.warn("Permissions error:", err);
      }
    }
  };

  const performHandshakeWithKeys = async (keysJsonString: string, targetMacOverride?: string) => {
    try {
      if (!keysJsonString) return;
      await CryptoModule.initiateHandshake(keysJsonString);
      const macToSave = targetMacOverride || targetDevice;
      if (macToSave) {
        await CryptoModule.setTargetDevice(macToSave);
      }
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

      // Restore existing PQC chat session if present
      try {
        const sessionInfo = await CryptoModule.restoreSession();
        if (sessionInfo && sessionInfo.restored && sessionInfo.targetMac) {
          setTargetDevice(sessionInfo.targetMac);
          updateHandshakeState(true);
          console.log("Restored active PQC session for peer:", sessionInfo.targetMac);

          // Load messages that arrived while the app was closed.
          // getPendingMessages() reads-then-clears atomically so they never show twice.
          try {
            const pending = await CryptoModule.getPendingMessages();
            if (pending && pending.length > 0) {
              const restored: Message[] = [];
              for (const item of pending) {
                try {
                  let text = item.payload;
                  try {
                    text = await CryptoModule.decryptMessage(item.payload);
                  } catch (_) {}
                  const d = new Date(item.timestamp);
                  const timeStr = `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
                  restored.push({
                    id: `pending_${item.timestamp}_${Math.random()}`,
                    sender: item.sender || 'Peer',
                    text,
                    isMine: false,
                    time: timeStr,
                  });
                } catch (_) {}
              }
              if (restored.length > 0) {
                setMessages(prev => [...restored, ...prev]);
                setTimeout(() => scrollViewRef.current?.scrollToEnd({ animated: false }), 120);
              }
            }
          } catch (pendErr) {
            console.warn("Pending messages load error:", pendErr);
          }
        }
      } catch (sessErr) {
        console.warn("Session restore check:", sessErr);
      }

      await BLEMeshModule.startAdvertising();
      try {
        await BLEMeshModule.startPeerDiscovery();
      } catch (e) {
        console.warn("Auto scan notice:", e);
      }
      setMeshActive(true);
      
      try {
        const btActive = await BLEMeshModule.isBluetoothEnabled();
        setIsBtEnabled(btActive);
      } catch (e) {}

      try {
        await BLEMeshModule.startForegroundService();
      } catch (e) {
        console.warn("Foreground service start notice:", e);
      }
    } catch (err) {
      console.warn("Auto init notice:", err);
    }
  };

  useEffect(() => {
    initKeysAndAdvertising();

    const recvSub = bleEmitter?.addListener('onMessageReceived', async (event) => {
      const { senderAddress, payload, msgId } = event;
      
      try {
        let displayPayload = payload;
        let wasDecrypted = false;
        try {
          displayPayload = await CryptoModule.decryptMessage(payload);
          wasDecrypted = true;
        } catch (decErr) {
          console.warn("PQC Decrypt fallback:", decErr);
        }

        if (wasDecrypted && displayPayload.startsWith("ACK:")) {
            const ackedMsgId = displayPayload.substring(4);
            console.log("🟢 [UI] Received ACK for msgId:", ackedMsgId);
            await BLEMeshModule.deleteOutboxMessage(ackedMsgId);
            setMessages(prev => prev.map(msg => msg.id === ackedMsgId ? { ...msg, status: 'acked' } : msg));
            return;
        }

        if (wasDecrypted && msgId) {
            try {
                console.log("🔵 [UI] Queuing ACK for msgId:", msgId);
                const ackPlaintext = "ACK:" + msgId;
                const ackCiphertext = await CryptoModule.encryptMessage(ackPlaintext);
                const localMac = await BLEMeshModule.getMacAddress();
                
                // Wait 1.75 seconds before sending the ACK to ensure the sender 
                // is ready to receive and not busy sending more chunks.
                setTimeout(() => {
                    BLEMeshModule.sendMessageToDevice(senderAddress || targetDevice, ackCiphertext, localMac, `ACK_${msgId}`);
                }, 1750);
            } catch (ackErr) {
                console.warn("Failed to queue ACK:", ackErr);
            }
        }

        const now = new Date();
        const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;
        
        setMessages(prev => [...prev, {
          id: msgId || Math.random().toString(),
          sender: senderAddress || 'Peer',
          text: displayPayload,
          isMine: false,
          time: timeStr
        }]);

        setTimeout(() => {
          scrollViewRef.current?.scrollToEnd({ animated: true });
        }, 80);
      } catch (e: any) {
        console.error("Message receive error:", e);
      }
    });

    const handshakeSub = bleEmitter?.addListener('onHandshakeKeysReceived', async (event) => {
      const { senderAddress, keys } = event;
      setTheirKeys(keys);
      if (senderAddress) {
        setTargetDevice(senderAddress);
        await CryptoModule.setTargetDevice(senderAddress);
      }
      await performHandshakeWithKeys(keys, senderAddress);
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

    const transmittedSub = bleEmitter?.addListener('onMessageTransmitted', (event) => {
      const { msgId } = event;
      if (msgId) {
        setMessages(prev => prev.map(msg => (msg.id === msgId && msg.status === 'queued') ? { ...msg, status: 'transmitted' } : msg));
      }
    });

    const deliveredSub = bleEmitter?.addListener('onMessageDelivered', (event) => {
      const { msgId } = event;
      if (msgId) {
        setMessages(prev => prev.map(msg => msg.id === msgId ? { ...msg, status: 'transmitted' } : msg));
      }
    });

    const btSub = bleEmitter?.addListener('onBluetoothStateChanged', (event) => {
      setIsBtEnabled(event.enabled);
    });
    
    return () => {
      recvSub?.remove();
      transmittedSub?.remove();
      handshakeSub?.remove();
      peerSub?.remove();
      relaySub?.remove();
      deliveredSub?.remove();
      btSub?.remove();
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
      await CryptoModule.setTargetDevice(peerAddress);
      setShowPeersModal(false);
      Alert.alert("Connecting & Pairing", `Requesting PQC Key Exchange from ${peerAddress} over BLE...`);
      await BLEMeshModule.requestPqcKeysOverBle(peerAddress, myMac);
    } catch (err: any) {
      console.warn("BLE connect notice:", err);
    }
  };

  const disconnectPeer = async () => {
    updateHandshakeState(false);
    setTargetDevice('');
    try {
      await CryptoModule.clearSession();
      await BLEMeshModule.resetMeshState();
    } catch (e) {
      console.warn("Error clearing session:", e);
    }
  };

  const sendMessage = async () => {
    const textToSend = inputText.trim();
    if (!textToSend) return;
    if (!targetDevice) {
      Alert.alert("No Peer Selected", "Please scan a peer's QR code or select a peer to start messaging!");
      return;
    }
    if (!handshakeDone) {
      Alert.alert("Encryption Notice", "Exchanging PQC keys with your peer... Please tap Retry if needed.");
      return;
    }

    const msgId = Math.random().toString(36).substring(2, 11) + '_' + Date.now();
    const now = new Date();
    const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;

    // 1. Clear input & render message bubble INSTANTLY (0ms lag!)
    setInputText('');
    setMessages(prev => [...prev, {
      id: msgId,
      sender: 'Me',
      text: textToSend,
      isMine: true,
      time: timeStr,
      status: 'queued'
    }]);

    setTimeout(() => {
      scrollViewRef.current?.scrollToEnd({ animated: true });
    }, 40);

    // 2. Perform encryption & BLE transmission in background
    try {
      const ciphertextBase64 = await CryptoModule.encryptMessage(textToSend);
      const res = await BLEMeshModule.sendMessageToDevice(targetDevice, ciphertextBase64, myMac, msgId);
      if (res === 'DELIVERED') {
        setMessages(prev => prev.map(msg => msg.id === msgId ? { ...msg, status: 'transmitted' } : msg));
      }
    } catch (e: any) {
      console.warn("Background transmission notice:", e.message);
    }
  };

  const tapCountRef = useRef<number>(0);
  const lastTapTimeRef = useRef<number>(0);

  const handleHeaderTap = async () => {
    const now = Date.now();
    if (now - lastTapTimeRef.current > 1500) {
      tapCountRef.current = 1;
    } else {
      tapCountRef.current += 1;
    }
    lastTapTimeRef.current = now;

    if (tapCountRef.current >= 3) {
      tapCountRef.current = 0;
      Alert.alert(
        "Emergency Panic Wipe",
        "Perform Emergency Panic Wipe? All PQC identity keys, master secrets, and message logs will be zeroized immediately.",
        [
          { text: "Cancel", style: "cancel" },
          {
            text: "WIPE EVERYTHING",
            style: "destructive",
            onPress: async () => {
              try {
                updateHandshakeState(false);
                setTargetDevice('');
                setTheirKeys('');
                setInputText('');
                setMessages([]);
                setDiscoveredPeers([]);
                setShowMyQrModal(false);
                setShowPeersModal(false);
                setIsScanning(false);
                
                const newKeysJson = await CryptoModule.emergencyWipe();
                await BLEMeshModule.resetMeshState();
                
                const freshMac = await BLEMeshModule.getMacAddress();
                setMyMac(freshMac);
                if (newKeysJson) {
                  setMyKeys(newKeysJson);
                  setQrPayload(JSON.stringify({ m: freshMac, k: newKeysJson }));
                }
                Alert.alert("Emergency Wipe Completed", "All keys, session secrets, and message logs zeroized.");
              } catch (e: any) {
                Alert.alert("Wipe Notice", e.message);
              }
            }
          }
        ]
      );
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#075E54" />

      {/* Responsive Centered Outer Container */}
      <View style={[styles.mainWrapper, { maxWidth: maxContentWidth, alignSelf: 'center' }]}>
        
        {/* Navigation Header Bar */}
        <View style={styles.header}>
          <Pressable style={styles.headerLeft} onPress={handleHeaderTap}>
            <Text style={[styles.headerTitle, isSmallScreen && { fontSize: 17 }]}>PQC Offline Chat</Text>
            <View style={styles.statusRow}>
              <View style={styles.activeDot} />
              <Text style={styles.headerSubtitle}>
                Node: {myMac ? myMac.slice(-10) : "Active"} • Kyber-768
              </Text>
            </View>
          </Pressable>

          {/* Clean Icon-Only Top Buttons (No Text) */}
          <View style={styles.headerRight}>
            <Pressable 
              style={({ pressed }) => [styles.topIconCircle, pressed && styles.topIconCirclePressed]} 
              onPress={() => setShowMyQrModal(true)}
              android_ripple={{ color: 'rgba(255, 255, 255, 0.25)', borderless: true, radius: 20 }}
              hitSlop={8}
            >
              <QrIcon size={20} color="#FFFFFF" />
            </Pressable>

            <Pressable 
              style={({ pressed }) => [styles.topIconCircleAction, pressed && styles.topIconCircleActionPressed]} 
              onPress={() => setIsScanning(true)}
              android_ripple={{ color: 'rgba(255, 255, 255, 0.25)', borderless: true, radius: 20 }}
              hitSlop={8}
            >
              <CameraIcon size={20} color="#FFFFFF" />
            </Pressable>
          </View>
        </View>

        {/* Enforced Bluetooth Alert Bar */}
        {!isBtEnabled && (
          <Pressable 
            style={{ backgroundColor: '#DC2626', paddingVertical: 9, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', justifyContent: 'center' }}
            onPress={() => BLEMeshModule.requestEnableBluetooth()}
          >
            <Text style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: 13 }}>
              ⚠️ Bluetooth is OFF — Tap to Enable Mesh
            </Text>
          </Pressable>
        )}

        {/* Active Connected Peer Status Banner */}
        <View style={styles.peerBanner}>
          {targetDevice ? (
            <View style={styles.bannerRow}>
              <View style={{ flex: 1, paddingRight: 8 }}>
                <Text style={styles.peerText} numberOfLines={1}>Connected Peer: {targetDevice}</Text>
                <Text style={styles.pqcStatus}>
                  {handshakeDone ? "🔒 Persistent Kyber-768 PQC Link Active" : "⌛ Exchanging PQC keys..."}
                </Text>
              </View>
              <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center' }}>
                {!handshakeDone && (
                  <Pressable 
                    style={({ pressed }) => [styles.retryBtn, pressed && { opacity: 0.8 }]} 
                    onPress={() => connectToPeer(targetDevice)}
                    android_ripple={{ color: 'rgba(255, 255, 255, 0.3)' }}
                  >
                    <Text style={styles.retryText}>Retry</Text>
                  </Pressable>
                )}
                <Pressable 
                  style={({ pressed }) => [styles.disconnectBtn, pressed && { opacity: 0.8 }]} 
                  onPress={disconnectPeer}
                  android_ripple={{ color: 'rgba(255, 255, 255, 0.3)' }}
                >
                  <Text style={styles.disconnectText}>X</Text>
                </Pressable>
              </View>
            </View>
          ) : (
            <Pressable style={styles.pairNoticeRow} onPress={() => setIsScanning(true)}>
              <Text style={styles.pairNoticeText}>
                💡 Scan partner's QR Code or tap "Discover Peers" to connect
              </Text>
            </Pressable>
          )}
        </View>

        {/* WhatsApp Chat Body & Footer Input Container */}
        <KeyboardAvoidingView 
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
          style={{ flex: 1 }}
          keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
        >
          <ScrollView 
            ref={scrollViewRef}
            style={styles.chatBackground}
            contentContainerStyle={{ padding: 14, paddingBottom: 24 }}
            showsVerticalScrollIndicator={false}
            onContentSizeChange={() => scrollViewRef.current?.scrollToEnd({ animated: true })}
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
                    {msg.isMine && (
                      <Text style={msg.status === 'queued' ? styles.queuedStatusText : styles.lockIcon}>
                        {msg.status === 'queued' ? ' ⌛' : msg.status === 'transmitted' ? ' ✓' : <Text style={{marginLeft: 4, letterSpacing: -2}}>✓✓</Text>}
                      </Text>
                    )}
                  </View>
                </View>
              ))
            )}
          </ScrollView>

          {/* Authentic WhatsApp Footer Input Bar */}
          <View style={styles.footerInputContainer}>
            <TextInput
              style={styles.textInput}
              placeholder="Type encrypted message..."
              placeholderTextColor="#8696A0"
              value={inputText}
              onChangeText={setInputText}
              multiline
            />
            
            {/* Smooth WhatsApp Green (#00A884) Circular Send Button */}
            <Pressable 
              style={({ pressed }) => [
                styles.whatsappSendBtn, 
                pressed && { transform: [{ scale: 0.92 }], opacity: 0.9 }
              ]} 
              onPress={sendMessage}
              disabled={!inputText.trim()}
              android_ripple={{ color: 'rgba(255, 255, 255, 0.3)', borderless: true, radius: 24 }}
            >
              <SendIcon size={20} color="#FFFFFF" />
            </Pressable>
          </View>
        </KeyboardAvoidingView>

      </View>

      {/* Modal: My Identity QR Code */}
      <Modal 
        visible={showMyQrModal} 
        animationType="fade" 
        transparent={true}
        onShow={() => {
          try { BLEMeshModule.ensureAdvertising(); } catch (e) {}
        }}
      >
        <View style={styles.modalOverlay}>
          <View style={[styles.qrModalContent, { maxWidth: Math.min(windowWidth * 0.88, 420) }]}>
            <Text style={styles.modalTitle}>Pair via QR Code</Text>
            <Text style={styles.modalSubtitle}>Show this QR code to partner phone to pair with instant PQC encryption</Text>
            
            {qrPayload ? (
              <View style={styles.qrWrapper}>
                <QRCode value={qrPayload} size={Math.min(windowWidth * 0.55, 220)} />
              </View>
            ) : (
              <Text style={{ fontStyle: 'italic', color: '#6B7280', marginVertical: 30 }}>Generating QR Code...</Text>
            )}

            <Text style={styles.nodeIdBadge}>My Node: {myMac}</Text>

            <Pressable 
              style={({ pressed }) => [styles.modalCloseBtn, pressed && { opacity: 0.85, transform: [{ scale: 0.98 }] }]} 
              onPress={() => setShowMyQrModal(false)}
              android_ripple={{ color: 'rgba(255, 255, 255, 0.2)' }}
            >
              <Text style={styles.modalCloseText}>Done</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      {/* Modal: Nearby Discovered Peers */}
      <Modal visible={showPeersModal} animationType="slide" transparent={true}>
        <View style={styles.modalOverlay}>
          <View style={[styles.peersModalContent, { maxWidth: Math.min(windowWidth * 0.9, 460) }]}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
              <Text style={styles.modalTitle}>Nearby Discovered Peers</Text>
              <Pressable 
                onPress={() => setShowPeersModal(false)}
                hitSlop={12}
                style={({ pressed }) => [pressed && { opacity: 0.6 }]}
              >
                <Text style={{ fontSize: 20, color: '#64748B', fontWeight: 'bold' }}>✕</Text>
              </Pressable>
            </View>

            {discoveredPeers.length === 0 ? (
              <View style={{ padding: 24, alignItems: 'center' }}>
                <Text style={{ color: '#64748B', fontStyle: 'italic' }}>Searching for nearby PQC nodes over BLE...</Text>
              </View>
            ) : (
              discoveredPeers.map(peer => (
                <View key={peer.address} style={styles.peerCard}>
                  <View style={{ flex: 1, paddingRight: 8 }}>
                    <Text style={{ fontWeight: '700', fontSize: 14, color: '#0F172A' }}>{peer.name}</Text>
                    <Text style={{ fontSize: 11, color: '#64748B', marginTop: 2 }}>MAC: {peer.address} | RSSI: {peer.rssi} dBm</Text>
                  </View>
                  <Pressable 
                    style={({ pressed }) => [styles.connectBtn, pressed && { opacity: 0.85, transform: [{ scale: 0.96 }] }]} 
                    onPress={() => connectToPeer(peer.address)}
                    android_ripple={{ color: 'rgba(255, 255, 255, 0.2)' }}
                  >
                    <Text style={styles.connectBtnText}>Connect</Text>
                  </Pressable>
                </View>
              ))
            )}

            <Pressable 
              style={({ pressed }) => [styles.modalCloseBtn, { marginTop: 16 }, pressed && { opacity: 0.85 }]} 
              onPress={() => setShowPeersModal(false)}
              android_ripple={{ color: 'rgba(255, 255, 255, 0.2)' }}
            >
              <Text style={styles.modalCloseText}>Close</Text>
            </Pressable>
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
                let data: any = null;
                try {
                  data = JSON.parse(scannedValue);
                } catch (e1) {
                  try {
                    const unescaped = scannedValue.replace(/\\"/g, '"');
                    data = JSON.parse(unescaped);
                  } catch (e2) {
                    const mMatch = scannedValue.match(/"m"\s*:\s*"([^"]+)"/);
                    const kMatch = scannedValue.match(/"k"\s*:\s*"(.*)"/);
                    if (mMatch) {
                      data = {
                        m: mMatch[1],
                        k: kMatch ? kMatch[1].replace(/\\"/g, '"') : null
                      };
                    }
                  }
                }

                if (data && data.m) {
                  let resolvedMac = data.m;
                  const cleanNodeId = data.m.replace("NODE_", "").replace("_", "").toLowerCase();
                  const matchedPeer = discoveredPeers.find(p => 
                    p.address === data.m || 
                    p.name.toLowerCase().replace(" ", "").includes(cleanNodeId) || 
                    cleanNodeId.includes(p.name.toLowerCase().replace(" ", ""))
                  );
                  if (matchedPeer) {
                    resolvedMac = matchedPeer.address;
                  }
                  
                  setTargetDevice(resolvedMac);

                  if (data.k) {
                    const keysJsonString = typeof data.k === 'string' ? data.k : JSON.stringify(data.k);
                    setTheirKeys(keysJsonString);
                    await performHandshakeWithKeys(keysJsonString, resolvedMac);
                    
                    try {
                      const localMac = await BLEMeshModule.getMacAddress();
                      await BLEMeshModule.requestPqcKeysOverBle(resolvedMac, localMac);
                    } catch (e) {
                      console.warn("Error requesting keys back:", e);
                    }
                  }

                  try {
                    await connectToPeer(resolvedMac);
                  } catch (e) {
                    console.warn("BLE connect error:", e);
                  }

                  Alert.alert("QR Pair Successful! 🔒", "Established Kyber-768 PQC session with peer!");
                } else {
                  Alert.alert("QR Read Notice", scannedValue);
                }
              }
            }}
          />

          <View style={styles.overlay}>
            <View style={styles.viewfinder} />
            <Text style={styles.scanInstruction}>Center partner's QR code inside box to pair</Text>
            <Pressable 
              style={({ pressed }) => [styles.closeBtn, pressed && { opacity: 0.85, transform: [{ scale: 0.96 }] }]} 
              onPress={() => setIsScanning(false)}
            >
              <Text style={styles.closeBtnText}>Cancel Scan</Text>
            </Pressable>
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
  mainWrapper: {
    flex: 1,
    width: '100%',
    backgroundColor: '#075E54',
  },
  header: {
    backgroundColor: '#075E54',
    paddingHorizontal: 16,
    paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight ? StatusBar.currentHeight + 36 : 64) : 48,
    paddingBottom: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    elevation: 4,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 3,
  },
  headerLeft: {
    flex: 1,
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: '#FFFFFF',
    letterSpacing: 0.3,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 4,
  },
  activeDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#25D366',
    marginRight: 6,
  },
  headerSubtitle: {
    fontSize: 12,
    color: '#E0F2F1',
    fontWeight: '500',
  },
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  topIconCircle: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255, 255, 255, 0.18)',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.25)',
  },
  topIconCirclePressed: {
    opacity: 0.75,
    transform: [{ scale: 0.94 }],
  },
  topIconCircleAction: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: '#25D366',
    alignItems: 'center',
    justifyContent: 'center',
    elevation: 3,
  },
  topIconCircleActionPressed: {
    opacity: 0.85,
    transform: [{ scale: 0.94 }],
  },
  peerBanner: {
    backgroundColor: '#128C7E',
    paddingHorizontal: 16,
    paddingVertical: 10,
    elevation: 2,
  },
  bannerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  peerText: {
    color: '#FFFFFF',
    fontWeight: '600',
    fontSize: 12,
  },
  pqcStatus: {
    color: '#B2DFDB',
    fontSize: 11,
    marginTop: 2,
    fontWeight: '500',
  },
  retryBtn: {
    backgroundColor: '#25D366',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 14,
    minHeight: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  retryText: {
    color: '#FFFFFF',
    fontSize: 11,
    fontWeight: '700',
  },
  disconnectBtn: {
    backgroundColor: 'rgba(255, 60, 60, 0.2)',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: 'rgba(255, 60, 60, 0.4)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  disconnectText: {
    color: '#ff6b6b',
    fontWeight: '700',
    fontSize: 12,
  },
  pairNoticeRow: {
    alignItems: 'center',
    paddingVertical: 2,
  },
  pairNoticeText: {
    color: '#E0F2F1',
    fontSize: 12,
    fontStyle: 'italic',
    textAlign: 'center',
  },
  chatBackground: {
    flex: 1,
    backgroundColor: '#ECE5DD',
  },
  emptyContainer: {
    alignItems: 'center',
    marginTop: 40,
    paddingHorizontal: 16,
  },
  securityBadge: {
    backgroundColor: '#FCF8E3',
    borderColor: '#FBEED5',
    borderWidth: 1,
    borderRadius: 10,
    padding: 14,
    alignItems: 'center',
    elevation: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 2,
  },
  securityTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: '#8A6D3B',
    marginBottom: 4,
  },
  securityDesc: {
    fontSize: 11,
    color: '#8A6D3B',
    textAlign: 'center',
    lineHeight: 16,
  },
  msgBubble: {
    padding: 10,
    paddingHorizontal: 12,
    borderRadius: 14,
    marginBottom: 10,
    maxWidth: '82%',
    elevation: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.08,
    shadowRadius: 2,
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
    color: '#0F172A',
    fontSize: 14,
    lineHeight: 19,
  },
  theirMsgText: {
    color: '#0F172A',
    fontSize: 14,
    lineHeight: 19,
  },
  senderTag: {
    fontSize: 10,
    fontWeight: '700',
    color: '#075E54',
    marginBottom: 3,
  },
  msgFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    marginTop: 4,
  },
  myTimeText: {
    fontSize: 10,
    color: '#64748B',
  },
  theirTimeText: {
    fontSize: 10,
    color: '#94A3B8',
  },
  lockIcon: {
    fontSize: 10,
  },
  queuedStatusText: {
    fontSize: 10,
    color: '#EAB308',
    fontWeight: '600',
  },
  footerInputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#F0F2F5',
    paddingHorizontal: 10,
    paddingTop: 8,
    paddingBottom: Platform.OS === 'android' ? 56 : 20,
    borderTopWidth: 1,
    borderTopColor: '#E0E0E0',
  },
  textInput: {
    flex: 1,
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    paddingHorizontal: 18,
    paddingVertical: 10,
    fontSize: 15,
    color: '#111827',
    maxHeight: 110,
    marginRight: 8,
    elevation: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 2,
  },
  whatsappSendBtn: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: '#00A884',
    alignItems: 'center',
    justifyContent: 'center',
    elevation: 3,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.18,
    shadowRadius: 3,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.65)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  qrModalContent: {
    backgroundColor: '#FFFFFF',
    borderRadius: 20,
    padding: 24,
    alignItems: 'center',
    width: '92%',
    elevation: 8,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
  },
  peersModalContent: {
    backgroundColor: '#FFFFFF',
    borderRadius: 20,
    padding: 20,
    width: '92%',
    maxHeight: '80%',
    elevation: 8,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#0F172A',
  },
  modalSubtitle: {
    fontSize: 12,
    color: '#64748B',
    textAlign: 'center',
    marginTop: 4,
    marginBottom: 16,
    lineHeight: 17,
  },
  qrWrapper: {
    padding: 16,
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    elevation: 4,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#F1F5F9',
  },
  nodeIdBadge: {
    fontSize: 11,
    color: '#075E54',
    fontWeight: '700',
    backgroundColor: '#E0F2F1',
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 14,
    marginBottom: 18,
    overflow: 'hidden',
  },
  modalCloseBtn: {
    backgroundColor: '#075E54',
    paddingVertical: 12,
    paddingHorizontal: 28,
    borderRadius: 22,
    minWidth: 120,
    alignItems: 'center',
    elevation: 2,
  },
  modalCloseText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 14,
  },
  peerCard: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#F1F5F9',
  },
  connectBtn: {
    backgroundColor: '#128C7E',
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: 16,
    minHeight: 36,
    justifyContent: 'center',
  },
  connectBtnText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 12,
  },
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.75)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  viewfinder: {
    width: 250,
    height: 250,
    borderWidth: 3,
    borderColor: '#25D366',
    borderRadius: 20,
    backgroundColor: 'transparent',
  },
  scanInstruction: {
    color: '#FFFFFF',
    fontSize: 13,
    marginTop: 20,
    fontWeight: '600',
    letterSpacing: 0.2,
  },
  closeBtn: {
    marginTop: 28,
    backgroundColor: '#EF4444',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 22,
    elevation: 3,
  },
  closeBtnText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 13,
  },
});
