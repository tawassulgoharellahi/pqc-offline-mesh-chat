# PQC Offline Mesh Chat

An offline, decentralized, post-quantum encrypted Android mobile messaging application powered by **NIST-Standardized Post-Quantum Cryptography (PQC)** and **Bluetooth Low Energy (BLE) Mesh Networking**. Designed with a modern, responsive WhatsApp-inspired dark interface.

---

## Key Features

* **Hybrid Post-Quantum Cryptography (PQC)**: Combines classical **X25519** ECDH with NIST-standardized **Kyber-768 / ML-KEM** via compiled Rust core native bindings (`uniffi` / `JNA`).
* **Offline BLE Mesh Networking**: Fully offline peer-to-peer messaging using Android Bluetooth Low Energy GATT Server & Advertising capabilities with custom MTU chunking and packet reassembly.
* **Store-and-Forward Outbox Queue**: Messages sent while a peer is disconnected or out-of-range are automatically queued (`Outbox`) and flushed as soon as the BLE connection is re-established.
* **GATT Lifecycle & Dynamic MAC Recovery**: Gracefully handles Android Bluetooth toggles (ON/OFF) by auto-recreating destroyed GATT servers and resolving dynamic Random Private Address (RPA) rotations with non-blocking scans.
* **Single QR Code Out-of-Band Pairing**: Scan your peer's QR code **once** to exchange PQC public key bundles and MAC addresses out-of-band over BLE (`KEY_REQ` / `KEY_RESP`)—zero manual configuration required.
* **Authenticated End-to-End Encryption**: All messages are encrypted locally using **AES-256-GCM** derived from the post-quantum shared secret before being chunked and transmitted over BLE.
* **Modern WhatsApp-Inspired UI**: Responsive dark theme with 0ms instant sending UI feedback, delivery status indicators (`Outbox` -> `Sent`), node status badges, and embedded full-screen camera QR scanner.

---

## Technical Architecture

```
┌─────────────────────────────────────────────────────────┐
│              WhatsApp-Styled React Native UI            │
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
│   (Kyber-768/ML-KEM, X25519, AES-256-GCM, Chunk Engine) │
└─────────────────────────────────────────────────────────┘
```

---

## Getting Started

### Prerequisites

* **Node.js** >= 18
* **Android SDK** (API Level 34+)
* **JDK** (Java 17 or Java 21)
* **Android Device** with Bluetooth LE & Camera support

---

## Building & Installation

### 1. Clone & Install Dependencies

```bash
git clone https://github.com/tawassulgoharellahi/pqc-offline-mesh-chat.git
cd pqc-offline-mesh-chat
npm install
```

### 2. Build Release APK

To compile the native Rust/C++ PQC core and generate the Release APK:

```bash
cd android
./gradlew assembleRelease
```

The output APK will be generated at:
`android/app/build/outputs/apk/release/app-release.apk`

### 3. Install on Devices via ADB

Connect your Android devices via USB with USB Debugging enabled, then run:

```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

---

## User Workflow

1. **Open App**: Launch PQC Mesh Chat on both Android phones. Bluetooth advertising and background BLE mesh discovery start automatically.
2. **Single QR Scan Pairing**:
   * On Device 1, tap the **QR Icon** at the top right to display its pairing QR code.
   * On Device 2, tap the **Camera Icon** and scan Device 1's QR code.
   * The app automatically sends a `KEY_REQ` BLE packet back to Device 1, completing the Kyber-768 PQC hybrid handshake on both devices simultaneously.
3. **Send Encrypted Messages**:
   * Type your message in the chat input and tap **Send**.
   * Messages are encrypted end-to-end using post-quantum AES-256-GCM keys and delivered instantly over BLE mesh.

---

## Security Architecture

* **Quantum Resilience**: Defends against "harvest now, decrypt later" attacks using NIST-standardized Kyber-768.
* **Air-Gapped & Offline**: Functions completely without Internet, Wi-Fi, cell service, or central servers.
* **Forward Secrecy & Ephemeral State**: Identity key pairs are generated locally on device startup.

---

## License

This project is licensed under the MIT License.
