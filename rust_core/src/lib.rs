uniffi::setup_scaffolding!();

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hkdf::Hkdf;
use pqcrypto_kyber::kyber768::{
    keypair, PublicKey as KyberPublicKey,
};
use pqcrypto_dilithium::dilithium3::{
    keypair as sign_keypair,
};
use pqcrypto_traits::kem::{PublicKey as KemPK, SecretKey as KemSK};
use pqcrypto_traits::sign::{PublicKey as SignPK, SecretKey as SignSK};
use rand_core::{OsRng, RngCore};
use sha2::Sha256;
use x25519_dalek::{StaticSecret, PublicKey as X25519PublicKey};
use std::sync::{Arc, Mutex};
use std::fmt;

use serde::{Serialize, Deserialize};

#[derive(Serialize, Deserialize)]
struct PublicKeysPayload {
    x25519: String,
    kyber: String,
}

#[derive(Serialize, Deserialize)]
struct PrivateKeysPayload {
    x25519_sk: String,
    x25519_pk: String,
    kyber_sk: String,
    kyber_pk: String,
    dilithium_sk: String,
    dilithium_pk: String,
}

#[derive(uniffi::Error, Debug)]
pub enum CryptoError {
    InvalidKeyLength,
    DecapsulationFailed,
    EncryptionFailed,
    DecryptionFailed,
    SignatureVerificationFailed,
}

impl fmt::Display for CryptoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl std::error::Error for CryptoError {}

// Identity Keys
#[derive(uniffi::Object)]
pub struct IdentityKeys {
    x25519_sk: [u8; 32],
    x25519_pk: [u8; 32],
    kyber_sk: Vec<u8>,
    kyber_pk: Vec<u8>,
    dilithium_sk: Vec<u8>,
    dilithium_pk: Vec<u8>,
}

#[uniffi::export]
impl IdentityKeys {
    #[uniffi::constructor]
    pub fn generate() -> Arc<Self> {
        // X25519
        let x25519_secret = StaticSecret::random_from_rng(OsRng);
        let x25519_public = X25519PublicKey::from(&x25519_secret);
        
        // Kyber (KEM)
        let (kyber_pk, kyber_sk) = keypair();
        
        // Dilithium (Signature)
        let (dilithium_pk, dilithium_sk) = sign_keypair();
        
        Arc::new(Self {
            x25519_sk: x25519_secret.to_bytes(),
            x25519_pk: x25519_public.to_bytes(),
            kyber_sk: kyber_sk.as_bytes().to_vec(),
            kyber_pk: kyber_pk.as_bytes().to_vec(),
            dilithium_sk: dilithium_sk.as_bytes().to_vec(),
            dilithium_pk: dilithium_pk.as_bytes().to_vec(),
        })
    }
    
    pub fn get_x25519_public_key(&self) -> Vec<u8> {
        self.x25519_pk.to_vec()
    }
    
    pub fn get_kyber_public_key(&self) -> Vec<u8> {
        self.kyber_pk.clone()
    }
    
    pub fn get_dilithium_public_key(&self) -> Vec<u8> {
        self.dilithium_pk.clone()
    }
    
    pub fn sign_data(&self, message: Vec<u8>) -> Result<Vec<u8>, CryptoError> {
        use pqcrypto_dilithium::dilithium3::{detached_sign, SecretKey as DilithiumSK};
        use pqcrypto_traits::sign::DetachedSignature;
        let sk = DilithiumSK::from_bytes(&self.dilithium_sk)
            .map_err(|_| CryptoError::InvalidKeyLength)?;
        let sig = detached_sign(&message, &sk);
        Ok(sig.as_bytes().to_vec())
    }
    
    pub fn export_public_keys_base64(&self) -> String {
        use base64::engine::general_purpose::STANDARD;
        use base64::Engine;
        
        let payload = PublicKeysPayload {
            x25519: STANDARD.encode(&self.x25519_pk),
            kyber: STANDARD.encode(&self.kyber_pk),
        };
        
        serde_json::to_string(&payload).unwrap_or_default()
    }

    pub fn export_private_keys_base64(&self) -> String {
        use base64::engine::general_purpose::STANDARD;
        use base64::Engine;

        let payload = PrivateKeysPayload {
            x25519_sk: STANDARD.encode(&self.x25519_sk),
            x25519_pk: STANDARD.encode(&self.x25519_pk),
            kyber_sk: STANDARD.encode(&self.kyber_sk),
            kyber_pk: STANDARD.encode(&self.kyber_pk),
            dilithium_sk: STANDARD.encode(&self.dilithium_sk),
            dilithium_pk: STANDARD.encode(&self.dilithium_pk),
        };

        serde_json::to_string(&payload).unwrap_or_default()
    }
}

#[uniffi::export]
pub fn import_identity_keys(base64_json: String) -> Result<Arc<IdentityKeys>, CryptoError> {
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;

    let payload: PrivateKeysPayload = serde_json::from_str(&base64_json).map_err(|_| CryptoError::InvalidKeyLength)?;

    let mut x25519_sk = [0u8; 32];
    let decoded_x25519_sk = STANDARD.decode(&payload.x25519_sk).map_err(|_| CryptoError::InvalidKeyLength)?;
    if decoded_x25519_sk.len() != 32 { return Err(CryptoError::InvalidKeyLength); }
    x25519_sk.copy_from_slice(&decoded_x25519_sk);

    let mut x25519_pk = [0u8; 32];
    let decoded_x25519_pk = STANDARD.decode(&payload.x25519_pk).map_err(|_| CryptoError::InvalidKeyLength)?;
    if decoded_x25519_pk.len() != 32 { return Err(CryptoError::InvalidKeyLength); }
    x25519_pk.copy_from_slice(&decoded_x25519_pk);

    Ok(Arc::new(IdentityKeys {
        x25519_sk,
        x25519_pk,
        kyber_sk: STANDARD.decode(&payload.kyber_sk).map_err(|_| CryptoError::InvalidKeyLength)?,
        kyber_pk: STANDARD.decode(&payload.kyber_pk).map_err(|_| CryptoError::InvalidKeyLength)?,
        dilithium_sk: STANDARD.decode(&payload.dilithium_sk).map_err(|_| CryptoError::InvalidKeyLength)?,
        dilithium_pk: STANDARD.decode(&payload.dilithium_pk).map_err(|_| CryptoError::InvalidKeyLength)?,
    }))
}

// Hybrid Handshake
#[uniffi::export]
pub fn perform_hybrid_handshake(
    my_keys: Arc<IdentityKeys>,
    their_x25519_pk: Vec<u8>,
    their_kyber_pk: Vec<u8>,
) -> Result<Vec<u8>, CryptoError> {
    if their_x25519_pk.len() != 32 {
        return Err(CryptoError::InvalidKeyLength);
    }
    
    // X25519 ECDH
    let my_x25519_sk = x25519_dalek::StaticSecret::from(my_keys.x25519_sk);
    let their_x25519_public = X25519PublicKey::from(<[u8; 32]>::try_from(their_x25519_pk.clone()).unwrap());
    
    let dh_shared_secret = my_x25519_sk.diffie_hellman(&their_x25519_public);
    
    // Validate their Kyber PK
    let _kyber_pk = KyberPublicKey::from_bytes(&their_kyber_pk).map_err(|_| CryptoError::InvalidKeyLength)?;

    // Combine secrets deterministically
    let mut combined_material = Vec::new();
    combined_material.extend_from_slice(dh_shared_secret.as_bytes());
    
    if my_keys.kyber_pk < their_kyber_pk {
        combined_material.extend_from_slice(&my_keys.kyber_pk);
        combined_material.extend_from_slice(&their_kyber_pk);
    } else {
        combined_material.extend_from_slice(&their_kyber_pk);
        combined_material.extend_from_slice(&my_keys.kyber_pk);
    }
    
    let mut okm = [0u8; 32];
    let hkdf = Hkdf::<Sha256>::new(None, &combined_material);
    hkdf.expand(b"mesh-chat-v1", &mut okm).unwrap();
    
    Ok(okm.to_vec())
}

#[uniffi::export]
pub fn restore_chat_session(master_key: Vec<u8>) -> Result<Arc<ChatSession>, CryptoError> {
    if master_key.len() != 32 {
        return Err(CryptoError::InvalidKeyLength);
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&master_key);
    Ok(Arc::new(ChatSession {
        current_key: Mutex::new(key),
    }))
}

// Symmetric Ratchet
#[derive(uniffi::Object)]
pub struct ChatSession {
    current_key: Mutex<[u8; 32]>,
}

#[uniffi::export]
impl ChatSession {
    pub fn export_master_key(&self) -> Vec<u8> {
        let key = self.current_key.lock().unwrap();
        key.to_vec()
    }

    #[uniffi::constructor]
    pub fn new(master_key: Vec<u8>) -> Result<Arc<Self>, CryptoError> {
        if master_key.len() != 32 {
            return Err(CryptoError::InvalidKeyLength);
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&master_key);
        Ok(Arc::new(Self {
            current_key: Mutex::new(key),
        }))
    }
    
    pub fn encrypt_message(&self, plaintext: String) -> Result<Vec<u8>, CryptoError> {
        let key_guard = self.current_key.lock().unwrap();
        let cipher = Aes256Gcm::new(aes_gcm::Key::<Aes256Gcm>::from_slice(&*key_guard));
        
        let mut nonce_bytes = [0u8; 12];
        OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);
        
        let plaintext_bytes = plaintext.as_bytes();
        let mut payload = Vec::new();
        if plaintext_bytes.len() > 100 {
            payload.push(0x01);
            let compressed = lz4_flex::compress_prepend_size(plaintext_bytes);
            payload.extend_from_slice(&compressed);
        } else {
            payload.push(0x00);
            payload.extend_from_slice(plaintext_bytes);
        }
        
        let ciphertext = cipher.encrypt(nonce, payload.as_slice())
            .map_err(|_| CryptoError::EncryptionFailed)?;
            
        // Output format: Nonce (12) || Ciphertext
        let mut output = nonce_bytes.to_vec();
        output.extend(ciphertext);
        Ok(output)
    }
    
    pub fn decrypt_message(&self, encrypted_data: Vec<u8>) -> Result<String, CryptoError> {
        if encrypted_data.len() < 12 {
            return Err(CryptoError::DecryptionFailed);
        }
        
        let key_guard = self.current_key.lock().unwrap();
        let cipher = Aes256Gcm::new(aes_gcm::Key::<Aes256Gcm>::from_slice(&*key_guard));
        
        let nonce = Nonce::from_slice(&encrypted_data[0..12]);
        let ciphertext = &encrypted_data[12..];
        
        let payload = cipher.decrypt(nonce, ciphertext)
            .map_err(|_| CryptoError::DecryptionFailed)?;
            
        if payload.is_empty() {
            return Err(CryptoError::DecryptionFailed);
        }
        
        let is_compressed = payload[0] == 0x01;
        let data = &payload[1..];
        
        let plaintext = if is_compressed {
            lz4_flex::decompress_size_prepended(data).map_err(|_| CryptoError::DecryptionFailed)?
        } else {
            data.to_vec()
        };
            
        String::from_utf8(plaintext).map_err(|_| CryptoError::DecryptionFailed)
    }
}

/// Standalone Dilithium-3 signature verification.
#[uniffi::export]
pub fn verify_signature(public_key: Vec<u8>, message: Vec<u8>, signature: Vec<u8>) -> bool {
    use pqcrypto_dilithium::dilithium3::{verify_detached_signature, PublicKey as DilithiumPK, DetachedSignature as DilithiumSig};
    use pqcrypto_traits::sign::{PublicKey, DetachedSignature};
    let pk = match DilithiumPK::from_bytes(&public_key) {
        Ok(k) => k,
        Err(_) => return false,
    };
    let sig = match DilithiumSig::from_bytes(&signature) {
        Ok(s) => s,
        Err(_) => return false,
    };
    verify_detached_signature(&sig, &message, &pk).is_ok()
}

/// Standalone LZ4 compression for raw byte buffers.
/// Adds a 1-byte header: 0x01 = LZ4 Compressed, 0x00 = Uncompressed Raw.
#[uniffi::export]
pub fn compress_data(data: Vec<u8>) -> Vec<u8> {
    if data.len() > 100 {
        let mut result = vec![0x01];
        let compressed = lz4_flex::compress_prepend_size(&data);
        result.extend_from_slice(&compressed);
        result
    } else {
        let mut result = vec![0x00];
        result.extend_from_slice(&data);
        result
    }
}

/// Standalone LZ4 decompression for raw byte buffers.
#[uniffi::export]
pub fn decompress_data(data: Vec<u8>) -> Result<Vec<u8>, CryptoError> {
    if data.is_empty() {
        return Err(CryptoError::DecryptionFailed);
    }
    if data[0] == 0x01 {
        lz4_flex::decompress_size_prepended(&data[1..]).map_err(|_| CryptoError::DecryptionFailed)
    } else if data[0] == 0x00 {
        Ok(data[1..].to_vec())
    } else {
        Err(CryptoError::DecryptionFailed)
    }
}

#[cfg(test)]
mod tests;
pub mod mesh_protocol;
