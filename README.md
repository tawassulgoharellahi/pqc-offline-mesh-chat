# PQC Offline Mesh Chat 🔒📱

A serverless, offline peer-to-peer (P2P) Bluetooth Low Energy (BLE) mesh messaging application secured by **Post-Quantum Cryptography (PQC)**.

---

## 🌟 Overview

Modern messaging applications rely on centralized servers and classical public-key infrastructure (like RSA or ECC), which are vulnerable to future quantum computing attacks ("Harvest Now, Decrypt Later"). 

**PQC Offline Mesh Chat** shifts the architecture to an ad-hoc, off-grid mesh network where smartphones communicate directly over Bluetooth Low Energy (BLE). Messages hop securely across intermediate devices without requiring internet access, cellular networks, or central servers.

```
[ Alice ] --(BLE Direct)--> [ Relay Device ] --(BLE Direct)--> [ Bob ]
    |                                                              |
    +================ End-to-End PQC Encrypted Payload ============+
```

---

## 🛡️ Key Cryptographic & Network Features

- **Hybrid Post-Quantum Key Exchange:** Combines classical **X25519** ECDH with **ML-KEM-768 (Kyber-768)** for quantum-resistant session secret derivation.
- **Post-Quantum Digital Signatures:** Uses **ML-DSA (Dilithium)** for authenticating identity public keys and message payloads.
- **Double Ratchet / Forward Secrecy:** **AES-256-GCM** symmetric ratcheting powered by HKDF-SHA256 ensuring perfect forward secrecy for every message transmitted.
- **Ad-Hoc BLE Mesh Networking:** Custom Android Kotlin Native Module using `BluetoothLeAdvertiser` and `BluetoothLeScanner` for connectionless payload broadcasting and GATT chunking.
- **UniFFI Cross-Language Architecture:** Zero-overhead Rust cryptographic core compiled directly into native `.so` Android binaries using `cargo-ndk` and UniFFI Kotlin bindings.

---

## 📁 Repository Structure

```
offline-pqc/
├── rust_core/              # High-performance Rust Cryptographic Engine
│   ├── src/
│   │   ├── lib.rs          # Kyber, Dilithium, X25519 & Ratchet logic
│   │   └── tests.rs        # Cryptographic unit test suite
│   ├── Cargo.toml          # Rust dependencies & UniFFI configuration
│   └── src/bin/            # UniFFI CLI bindgen helper
└── PqcMeshChat/            # React Native Cross-Platform Application
    ├── android/            # Android Native Project (NDK, JNI, Kotlin)
    │   └── app/src/main/
    │       ├── java/com/pqcmeshchat/
    │       │   ├── CryptoModule.kt   # React Native bridge to Rust core
    │       │   ├── BLEMeshModule.kt  # Android BLE Advertiser & Scanner
    │       │   └── uniffi/rust_core/ # UniFFI generated Kotlin bindings
    │       └── jniLibs/              # Compiled C-shared (.so) libraries (arm64, armv7, x86_64, i686)
    ├── App.tsx             # React Native UI Layer
    └── package.json
```

---

## 🚀 Getting Started

### Prerequisites

1. **Rust Toolchain:** `rustup` with Android targets (`aarch64-linux-android`, `x86_64-linux-android`, etc.)
2. **Node.js & React Native CLI**
3. **Android NDK (v28+)** & **Android Studio**

### Building the Cryptographic Core & Bindings

```bash
cd rust_core

# 1. Run Rust Unit Tests
cargo test

# 2. Generate Kotlin Bindings via UniFFI
cargo run --bin uniffi-bindgen generate --library target/debug/librust_core.dylib --language kotlin --out-dir out

# 3. Cross-compile Android Shared Libraries (.so)
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
cargo ndk -t aarch64-linux-android -t armv7-linux-androideabi -t i686-linux-android -t x86_64-linux-android -o ../PqcMeshChat/android/app/src/main/jniLibs build --release
```

### Running the App on Android

```bash
cd PqcMeshChat

# Install npm dependencies
npm install

# Build and deploy to connected Android physical device / emulator
npx react-native run-android
```

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for details.
