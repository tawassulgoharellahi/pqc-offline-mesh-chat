# PQC Mesh Chat 🔒📱

An offline, decentralized, end-to-end encrypted mobile chat application powered by **Post-Quantum Cryptography (PQC)** and **Bluetooth Low Energy (BLE) Mesh Networking**.

---

## 🌟 Key Features

* 🔐 **Hybrid Post-Quantum Cryptography (PQC)**: Combines classical **X25519** ECDH with NIST-standardized **Kyber / ML-KEM** via compiled Rust core native bindings (`uniffi` / `JNA`).
* 📡 **Offline BLE Mesh Networking**: Fully offline peer-to-peer communication using Android Bluetooth Low Energy GATT Server & Advertising capabilities with custom MTU chunking and packet reassembly.
* 📷 **Seamless QR Code Key & MAC Exchange**: Scan your partner's QR code to automatically exchange PQC public key bundles and Bluetooth MAC addresses out-of-band—zero manual typing required.
* 🔒 **Authenticated End-to-End Encryption**: All messages are encrypted locally using **AES-256-GCM** derived from the post-quantum shared secret before being chunked and transmitted over BLE.

---

## 🏗️ Technical Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     React Native UI                     │
│               (TypeScript / App.tsx)                    │
└───────────┬─────────────────────────────────┬───────────┘
            │                                 │
   JNI / React Native               JNI / React Native
      Native Module                    Native Module
            │                                 │
┌───────────▼───────────┐         ┌───────────▼───────────┐
│     CryptoModule      │         │     BLEMeshModule     │
│       (Kotlin)        │         │       (Kotlin)        │
└───────────┬───────────┘         └───────────┬───────────┘
            │                                 │
      UniFFI / JNA                      UniFFI / JNA
            │                                 │
┌───────────▼─────────────────────────────────▼───────────┐
│                       Rust Core                         │
│   (Kyber / ML-KEM, X25519, AES-256-GCM, Chunking Engine)│
└─────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites

* **Node.js** >= 18
* **Android SDK** (API Level 34+)
* **JDK** (Java 17 or Java 21)
* **Android Device** with Bluetooth LE support and Camera permissions enabled

---

## 🛠️ Building & Installation

### 1. Clone & Install Dependencies

```bash
git clone https://github.com/tawassulgoharellahi/pqc-offline-mesh-chat.git
cd pqc-offline-mesh-chat
npm install
```

### 2. Build Release APK

To compile the native C++/Rust bindings and generate a Release APK:

```bash
cd android
./gradlew assembleRelease
```

The output APK will be located at:
`android/app/build/outputs/apk/release/app-release.apk`

### 3. Install on Device via ADB

Connect your Android phone via USB with USB Debugging enabled, then run:

```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

---

## 📖 User Workflow & Usage

1. **Step 1: Generate PQC Keys**
   * Open the app on both devices and tap **"Generate PQC Keys"**.
   * Device 1 will display a unified QR Code containing its public key bundle and Bluetooth MAC address.

2. **Step 2: Automated Out-of-Band Key & MAC Exchange**
   * On Device 2, tap **"Scan Partner's QR Code"** and scan the QR code displayed on Device 1.
   * Device 2 automatically stores Device 1's public keys and MAC address in background state.
   * Repeat the scan in reverse so Device 1 scans Device 2's QR code.
   * Tap **"Initiate Secure Handshake"** to derive the Post-Quantum Shared Secret.

3. **Step 3: Start Mesh Node**
   * Tap **"Start BLE GATT Server & Advertising"** on both devices.

4. **Step 4: Offline Encrypted Chat**
   * Type your secret message in the input box at the bottom and tap **"Send"**.
   * The message is encrypted locally using the PQC session keys, split into BLE chunks, transmitted over the air, reassembled by the receiver, and decrypted.

---

## 🛡️ Security Considerations

* **Post-Quantum Resilience**: Protects against future quantum computer decryption ("harvest now, decrypt later" attacks).
* **Air-Gapped & Offline**: No Internet, Wi-Fi, or central servers are required.
* **Privacy-First**: No persistent tracking or hardcoded identifiers.

---

## 📄 License

This project is licensed under the MIT License.
