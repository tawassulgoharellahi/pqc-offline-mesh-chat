# PQC Offline Mesh Chat

A serverless, off-grid peer-to-peer (P2P) Bluetooth Low Energy (BLE) mesh messaging application secured by **Post-Quantum Cryptography (PQC)**.

---

## Overview

Traditional mobile messaging applications depend on central servers and classical public-key cryptography (RSA/ECC), rendering them vulnerable to future quantum computing decryption ("Harvest Now, Decrypt Later") and internet infrastructure outages.

**PQC Offline Mesh Chat** shifts the paradigm to an ad-hoc, serverless BLE mesh network. Devices communicate directly over Bluetooth Low Energy, acting as both secure communication endpoints and zero-knowledge relay nodes. Encrypted messages hop across intermediate devices without requiring cellular towers, internet access, or central servers.

```
[ Alice ] ----(BLE Direct)----> [ Relay Node ] ----(BLE Direct)----> [ Bob ]
    |                                                                   |
    +================ End-to-End PQC Encrypted Payload =================+
```

---

## Technical Architecture & Cryptographic Engine

### Cryptographic Stack (Rust Core)
- **Hybrid Key Exchange**: Combines **X25519** Elliptic Curve Diffie-Hellman with **ML-KEM-768 (Kyber-768)** for quantum-resistant session secret derivation via HKDF-SHA256.
- **Post-Quantum Digital Signatures**: Implements **ML-DSA (Dilithium-3)** for authenticating node public keys and verifying message origin integrity.
- **Symmetric Encryption**: Uses **AES-256-GCM** authenticated symmetric encryption for all post-handshake messaging payloads.
- **Payload Compression**: Integrates **LZ4** compression (`lz4_flex`) for payloads exceeding 100 bytes, drastically reducing BLE fragment counts.

### Network & Hardware Integration (Android & React Native)
- **Zero-Knowledge Mesh Relaying**: Intermediate nodes forward routing headers (TTL, message ID, destination) without access to encrypted payload contents.
- **GATT Chunking Protocol**: Segmentation and Reassembly (SAR) engine divides payloads into MTU-friendly chunks over BLE GATT characteristics.
- **24/7 Foreground Service**: Native Android service (`BLEMeshService`) with `START_STICKY` keepalive to maintain BLE advertising, scanning, and GATT listening when the app is minimized or running in the background.
- **Encrypted Session Persistence**: `SharedPreferences` storage retains identity keys, master secrets, and paired target MAC addresses across app restarts.
- **GATT Cache Recovery**: Uses reflection-based `gatt.refresh()` call on GATT status 133 error, combined with non-blocking watchdog timeouts and Bluetooth state broadcast receivers.

---

## Project Roadmap & Implementation Phases

### Phase 1: Cryptographic Foundation [COMPLETED]
- Rust core integration with ML-KEM-768 (Kyber), Dilithium-3, X25519, and AES-256-GCM.
- UniFFI scaffolding for cross-language JNA/Kotlin bindings.

### Phase 2: BLE & Wire Protocol [COMPLETED]
- Custom GATT chunking SAR engine handling large PQC payloads over 250-byte MTU buffers.
- Message header protocol tracking TTL, 16-byte message ID, destination MAC, and sender node ID.

### Phase 3: Mesh Networking & Multi-Hop Discovery [COMPLETED]
- Continuous BLE scanning (`BluetoothLeScanner`) and background advertising (`BluetoothLeAdvertiser`).
- Automatic intermediate relay routing with duplicate message suppression.

### Phase 4: App Integration & UI [COMPLETED]
- React Native dark interface designed for offline mobile usage.
- Single-scan 2D QR code identity key exchange workflow.
- Disconnect button and dynamic handshake status indicators.

### Phase 5: Store-and-Forward Outbox [COMPLETED]
- Outbox store-and-forward queue for messages addressed to temporarily offline peers.
- Debounced auto-flush (max once per 2 seconds) upon peer rediscovery.

### Phase 6: Advanced BLE Stability & GATT Lifecycle [COMPLETED]
- GATT lock with 10-second watchdog timeout.
- Reflection-based `gatt.refresh()` execution on GATT status 133 retry loops.
- BroadcastReceiver listening to `BluetoothAdapter.ACTION_STATE_CHANGED` for automatic BT ON/OFF recovery.

### Phase 7: 24/7 Background Persistence & Foreground Service [COMPLETED]
- Native `BLEMeshService` Android Foreground Service with `START_STICKY` flag.
- Persistent status bar notification ("PQC Mesh Node Active") ensuring continuous background operation.

### Phase 8: Encrypted Session & Peer Persistence [COMPLETED]
- Persistent storage for identity keys, master secrets, and target MAC addresses in Android `SharedPreferences`.
- Automatic session restoration on app launch without requiring QR re-scanning.

### Phase 9: LZ4 Payload Compression [COMPLETED]
- `lz4_flex` payload compression for data > 100 bytes, reducing BLE chunk counts by up to 60%.
- Standalone `compress_data` and `decompress_data` UniFFI exports.

### Phase 10: Security Hardening & Binary Wire Protocol [COMPLETED]
- **Triple-Tap Emergency Panic Wipe**: Rapidly tapping the header title 3 times within 1.5 seconds triggers an emergency wipe that zeroizes identity keys, master secrets, mesh queues, and UI logs.
- **ML-DSA Signatures**: Dilithium-3 signature generation (`sign_data`) and verification (`verify_signature`).
- **Compact Binary Wire Envelope**: Compact fixed-header binary encoding (`[0x50, 0x51]` magic header) replacing JSON string envelope overhead.

---

## Repository Structure

```
offline-pqc/
├── rust_core/              # High-Performance Rust Cryptographic Engine
│   ├── src/
│   │   ├── lib.rs          # Kyber-768, Dilithium-3, X25519, AES-256-GCM & LZ4
│   │   ├── mesh_protocol.rs# SAR Chunking, Reassembly & Binary Envelope
│   │   └── tests.rs        # Comprehensive unit test suite (6 passing tests)
│   ├── Cargo.toml          # Rust dependencies & UniFFI config
│   └── src/bin/            # UniFFI CLI bindgen helper
└── PqcMeshChat/            # React Native Android Application
    ├── App.tsx             # Main UI Layer & Gesture Handlers
    └── android/            # Android Native Project (NDK, JNI, Kotlin)
        └── app/src/main/
            ├── AndroidManifest.xml # Permissions & Foreground Service declaration
            ├── java/com/pqcmeshchat/
            │   ├── CryptoModule.kt   # React Native bridge for Rust engine
            │   ├── BLEMeshModule.kt  # Android BLE Advertiser & Scanner
            │   ├── BLEMeshService.kt # 24/7 Foreground Service
            │   └── uniffi/rust_core/ # UniFFI generated Kotlin bindings
            └── jniLibs/              # Cross-compiled C-shared (.so) libraries
                ├── arm64-v8a/
                ├── armeabi-v7a/
                ├── x86/
                └── x86_64/
```

---

## Building & Deployment

### Prerequisites
1. **Rust Toolchain**: `rustup` with targets added:
   `rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android`
2. **Cargo NDK**: `cargo install cargo-ndk`
3. **Android NDK (v26+)** & **Android SDK**
4. **Node.js** & **React Native CLI**

### 1. Run Rust Unit Tests
```bash
cd rust_core
cargo test
```

### 2. Generate UniFFI Kotlin Bindings
```bash
cd rust_core
cargo build
cargo run --bin uniffi-bindgen generate --library target/debug/librust_core.dylib --language kotlin --out-dir ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core
cp ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core/uniffi/rust_core/rust_core.kt ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core/rust_core.kt
rm -rf ../PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/uniffi/rust_core/uniffi
```

### 3. Cross-Compile Native Android Libraries (`.so`)
```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/26.1.10909125"
cd rust_core
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../PqcMeshChat/android/app/src/main/jniLibs build --release
```

### 4. Build Android Release APK
```bash
cd PqcMeshChat/android
./gradlew assembleRelease
```
The compiled APK will be located at:
`PqcMeshChat/android/app/build/outputs/apk/release/app-release.apk`

---

## Manual Verification & Testing Guide

### Emergency Panic Wipe Test
1. Tap **3 times** on the top header title **"PQC Offline Chat"** within 1.5 seconds.
2. Confirm the prompt: `WIPE EVERYTHING`.
3. Verify that all messages, keys, stored master secrets, and mesh state are zeroized instantly.

### Session Auto-Restore Test
1. Pair two devices by scanning the QR code once.
2. Close the app completely on both devices via Recent Apps.
3. Re-open the app — verify that the green lock banner (`🔒 Kyber-768 + AES-256 Encrypted Session Active`) restores automatically without re-scanning.

---

## License

Distributed under the MIT License. See `LICENSE` for details.
