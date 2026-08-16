<div align="center">

# 🔒 PQC Offline Mesh Chat
### Serverless, Decentralized, Post-Quantum Encrypted Bluetooth Mesh Messenger for Android

[![NIST PQC Standard](https://img.shields.io/badge/NIST%20PQC-ML--KEM%20%7C%20ML--DSA-blue.svg)](https://csrc.nist.gov/projects/post-quantum-cryptography)
[![Rust Core](https://img.shields.io/badge/Language-Rust%20%7C%20Kotlin%20%7C%20TypeScript-orange.svg)](https://www.rust-lang.org/)
[![Bluetooth Mesh](https://img.shields.io/badge/Network-BLE%205.0%20Mesh%20%2F%20GATT-green.svg)](https://www.bluetooth.com/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%20--%2014%2B-red.svg)](https://developer.android.com)

<p align="center">
  <b>Communicate off-grid without Cellular, Wi-Fi, or Central Servers.</b><br>
  Built with NIST-standardized <b>Kyber-768 (ML-KEM)</b>, <b>Dilithium-3 (ML-DSA)</b>, and an autonomous <b>Bluetooth Low Energy (BLE) Multi-Hop Store-and-Forward Mesh</b>.
</p>

</div>

---

## 📑 Table of Contents
- [Threat Model & Motivation](#-threat-model--motivation)
- [Key Features](#-key-features)
- [Cryptographic Architecture](#-cryptographic-architecture)
- [Mesh Networking & Data Mule Architecture](#-mesh-networking--data-mule-architecture)
- [System Architecture](#-system-architecture)
- [Repository Structure](#-repository-structure)
- [Prerequisites & Build Guide](#-prerequisites--build-guide)
- [Operational Guide](#-operational-guide)
- [Battery & Background Optimization](#-battery--background-optimization)
- [Security & Threat Defense Summary](#-security--threat-defense-summary)
- [License](#-license)

---

## 🛡️ Threat Model & Motivation

Traditional mobile messaging applications (Signal, WhatsApp, Telegram) depend on centralized internet infrastructure and classical public-key cryptography (RSA, ECDH). This creates two fundamental vulnerabilities:

1. **"Harvest Now, Decrypt Later" (HNDL)**: Adversaries can intercept and store classical encrypted traffic today, waiting for quantum computers to derive private keys using Shor's Algorithm.
2. **Infrastructure Failure & Censorship**: Natural disasters, grid blackouts, or state-level internet shutdowns immediately disable centralized messengers.

**PQC Offline Mesh Chat** solves both threats by providing an ad-hoc, peer-to-peer (P2P) mesh network that operates **100% offline**, secured by quantum-resistant mathematical lattices.

---

## ✨ Key Features

* **⚛️ Hybrid Post-Quantum Key Exchange**: Combines classical **X25519 ECDH** with NIST-standardized **Kyber-768 (ML-KEM)** via compiled native Rust core bindings (`uniffi` / JNI).
* **✍️ Post-Quantum Digital Signatures**: Implements **Dilithium-3 (ML-DSA)** for public key authentication and message integrity.
* **📡 Zero-Knowledge Multi-Hop Relay**: Intermediate nodes automatically route encrypted ciphertext across hops without having access to decryption keys.
* **🚚 Delay-Tolerant "Data Mule" Store-and-Forward**: Messages addressed to out-of-range peers are queued and automatically delivered when an intermediate device travels into range—even if the sender is powered off.
* **📷 Single QR Code Out-of-Band Pairing**: Scan your peer's QR code **once** to exchange public keys and BLE node identities over GATT (`KEY_REQ` / `KEY_RESP`).
* **⚡ 24/7 Foreground Service**: Persistent native Android daemon (`BLEMeshService`) keeps the BLE GATT server alive in the background and with the screen locked.
* **✓✓ Real-Time Encrypted Double Ticks**: WhatsApp-style delivery acknowledgments (`ACK`) routed through the mesh both in the active UI and natively in the background.
* **🚨 Triple-Tap Emergency Panic Wipe**: Triple-tapping the header triggers native RAM zeroization, purges session keys, wipes outboxes, and restores a clean state.
* **🗜️ LZ4 Wire Compression**: Automatic payload compression for packets $> 100$ bytes to optimize BLE MTU throughput.

---

## 🔐 Cryptographic Architecture

```
                       ┌─────────────────────────────────────┐
                       │           Hybrid Handshake          │
                       │   (X25519 ECDH  +  Kyber-768 KEM)   │
                       └──────────────────┬──────────────────┘
                                          │
                                   HKDF-SHA256 (Salt, Info="mesh-chat-v1")
                                          │
                                          ▼
                       ┌─────────────────────────────────────┐
                       │     256-bit Master Session Key      │
                       └──────────────────┬──────────────────┘
                                          │
                                  AES-256-GCM + LZ4
                                          │
                                          ▼
                       ┌─────────────────────────────────────┐
                       │ End-to-End Quantum-Safe Ciphertext  │
                       └─────────────────────────────────────┘
```

1. **Identity & Key Generation**: Each device generates an `IdentityKeys` bundle containing an X25519 keypair, Kyber-768 keypair, and Dilithium-3 signature keypair.
2. **Deterministic KDF**: Shared secrets from X25519 and Kyber decapsulation are combined deterministically with lexical public key ordering and expanded via `HKDF-SHA256`.
3. **Payload Security**: Every chat message is compressed via `lz4_flex`, encrypted with **AES-256-GCM** using a cryptographically secure random 12-byte nonce (`OsRng`), and signed.

---

## 🌐 Mesh Networking & Data Mule Architecture

### 1. Direct Multi-Hop Routing
Packets contain routing metadata (`type`, `dest`, `sender`, `msgId`, `ttl`) and the opaque ciphertext.
* When an intermediate phone receives a packet:
  * If `dest == myNodeId`: Decrypts and notifies the user.
  * If `dest != myNodeId` and `ttl > 1`: Decrements `ttl - 1`, adds to `relayQueue`, and forwards over BLE GATT.

```mermaid
graph LR
    A[📱 Xiaomi<br>Sender] -->|BLE Hop 1| B[📱 Node B<br>Relay]
    B -->|BLE Hop 2| C[📱 Node C<br>Relay]
    C -->|BLE Hop 3| D[📱 Infinix<br>Recipient]
```

### 2. Delay-Tolerant "Data Mule" (Store-and-Forward)
If the destination device is miles away or out of range, intermediate devices act as physical carriers ("Data Mules"):

```mermaid
sequenceDiagram
    autonumber
    actor A as 📱 Xiaomi (Sender)
    actor M as 🚗 Data Mule (Carrier)
    actor B as 📱 Infinix (Recipient)

    A->>M: 1. Hands off encrypted packet over BLE (dest = Infinix)
    Note over M: 2. Mule stores packet in relayQueue
    Note over A: 🔴 Xiaomi powers off / goes offline
    Note over M: 3. Mule physically travels 10 km to Infinix area
    M->>B: 4. Mule discovers Infinix & delivers packet over BLE
    Note over B: 5. Infinix decrypts with pre-shared Kyber session! 🎉
    B->>M: 6. Infinix sends encrypted ACK (dest = Xiaomi)
    Note over M: 7. Mule carries ACK back when in Xiaomi range
```

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────────────────┐
│              WhatsApp-Styled React Native UI            │
│                 (TypeScript / App.tsx)                  │
└───────────┬─────────────────────────────────┬───────────┘
            │                                 │
   JNI Native Module                 JNI Native Module
            │                                 │
┌───────────▼───────────┐         ┌───────────▼───────────┐
│     CryptoModule      │         │     BLEMeshModule     │
│       (Kotlin)        │         │       (Kotlin)        │
└───────────┬───────────┘         └───────────┬───────────┘
            │                                 │
       UniFFI / JNI                      UniFFI / JNI
            │                                 │
┌───────────▼─────────────────────────────────▼───────────┐
│                       Rust Core                         │
│  (Kyber-768, Dilithium-3, X25519, AES-256-GCM, LZ4)     │
└─────────────────────────────────────────────────────────┘
```

---

## 📁 Repository Structure

```
offline-pqc/
├── rust_core/                     # High-Performance Native Rust Cryptographic Core
│   ├── Cargo.toml                 # Cargo dependencies (pqcrypto, aes-gcm, lz4_flex)
│   ├── src/
│   │   ├── lib.rs                 # PQC key generation, KEM, symmetric ratchets, memory zeroization
│   │   ├── mesh_protocol.rs       # Binary wire serialization & SAR chunking
│   │   └── tests.rs               # Rust test suite
│   └── src/bin/uniffi-bindgen.rs  # UniFFI CLI code generator
│
└── PqcMeshChat/                   # React Native Android Mobile Application
    ├── App.tsx                    # React Native UI, Camera QR Scanner, Lifecycle handlers
    └── android/
        └── app/src/main/
            ├── AndroidManifest.xml
            ├── java/com/pqcmeshchat/
            │   ├── MainActivity.kt
            │   ├── MainApplication.kt
            │   ├── CryptoModule.kt   # React Native JNI bridge for Rust Core
            │   ├── BLEMeshModule.kt  # Android BLE Advertiser, Scanner, GATT Server & Outbox
            │   ├── BLEMeshService.kt # 24/7 Foreground Service Keepalive Daemon
            │   └── uniffi/rust_core/ # Auto-generated Kotlin-Rust bindings
            └── jniLibs/              # Precompiled Android shared libraries (.so)
                ├── arm64-v8a/
                ├── armeabi-v7a/
                ├── x86/
                └── x86_64/
```

---

## 🛠️ Prerequisites & Build Guide

### Prerequisites
1. **Rust Toolchain**: `rustup` with Android targets:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
   ```
2. **Cargo NDK**: `cargo install cargo-ndk`
3. **Android Studio & NDK**: Android SDK 34+ and NDK version `26.1.10909125`.
4. **Node.js**: Node 18+ and Yarn/npm.

---

### Step 1: Compile Rust Cryptographic Engine & Bindings
```bash
# 1. Run unit tests
cd rust_core
cargo test

# 2. Build UniFFI Kotlin Bindings
cargo run --bin uniffi-bindgen generate --library target/debug/librust_core.dylib --language kotlin --out-dir ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core
cp ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core/uniffi/rust_core/rust_core.kt ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core/rust_core.kt
rm -rf ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core/uniffi

# 3. Cross-Compile .so libraries for all Android architectures
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../PqcMeshChat/android/app/src/main/jniLibs build --release
```

---

### Step 2: Build the Android Release APK
```bash
cd ../PqcMeshChat
npm install

cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

The compiled APK will be at:
`PqcMeshChat/android/app/build/outputs/apk/release/app-release.apk`

---

### Step 3: Install via ADB
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 📱 Operational Guide

1. **Startup**: Open the app on two or more Android devices. BLE Mesh advertising and peer discovery start automatically.
2. **Pairing (Single QR Scan)**:
   * Device A taps the **QR Icon** in the top header to display its public key QR code.
   * Device B taps the **Camera Icon** and scans Device A's screen.
   * Device B derives the shared key and transmits a `KEY_REQ` packet back to Device A over BLE.
   * **Both devices turn green ("Connected Peer") simultaneously**.
3. **Messaging**:
   * Send messages instantly. If the peer is temporarily unreachable, messages queue in the **Outbox** and auto-flush on reconnection.
   * Delivered messages transition from single tick (`✓`) to **double ticks (`✓✓`)** upon receipt of an encrypted ACK.
4. **Emergency Panic Wipe**:
   * **Triple-tap the top header** to immediately wipe all private keys, zeroize RAM, clear outboxes, and return to an uninitialized state.

---

## 🔋 Battery & Background Optimization

Because Android OS aggressive battery managers (Doze Mode) can suspend background BLE radios after 30 minutes, follow these recommended settings:

* **Xiaomi (HyperOS / MIUI)**:
  * App Info $\rightarrow$ Battery Saver $\rightarrow$ Select **"No Restrictions"**.
  * Enable **"Autostart"**.
  * Lock the app in the Recent Apps screen (tap the 🔒 lock icon).
* **Infinix / Transsion (XOS)**:
  * App Info $\rightarrow$ Battery $\rightarrow$ Select **"Unrestricted"**.
* **Samsung (OneUI) / Google Pixel (AOSP)**:
  * App Info $\rightarrow$ App Battery Usage $\rightarrow$ Select **"Unrestricted"**.

---

## 🔒 Security & Threat Defense Summary

| Threat / Attack Vector | Mitigation in PQC Mesh Chat |
| :--- | :--- |
| **Quantum Computing (Harvest Now, Decrypt Later)** | NIST **Kyber-768 (ML-KEM)** lattice-based key encapsulation. |
| **Man-in-the-Middle (MITM) Attacks** | Out-of-band QR code verification + **Dilithium-3 (ML-DSA)** signatures. |
| **Untrusted Intermediate Relay Nodes** | End-to-end **AES-256-GCM** encryption; relay nodes only inspect routing envelopes. |
| **Replay Attacks** | In-memory & native deduplication table (`seenMsgIds`) drops duplicate packet IDs. |
| **Physical Device Seizure** | **Triple-Tap Panic Wipe** zeroizes native RAM buffers and deletes persistent keys. |
| **Network Outages & Internet Censorship** | **100% Offline** peer-to-peer Bluetooth Low Energy mesh routing. |

---

## 📄 License
This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
