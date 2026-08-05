uniffi::setup_scaffolding!();

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hkdf::Hkdf;
use pqcrypto_kyber::kyber768::{
    keypair, encapsulate, PublicKey as KyberPublicKey,
};
use pqcrypto_dilithium::dilithium3::{
    keypair as sign_keypair,
};
use pqcrypto_traits::kem::{PublicKey as KemPK, SecretKey as KemSK, SharedSecret as KemSS};
use pqcrypto_traits::sign::{PublicKey as SignPK, SecretKey as SignSK};
use rand_core::{OsRng, RngCore};
use sha2::Sha256;
use x25519_dalek::{StaticSecret, PublicKey as X25519PublicKey};
use std::sync::{Arc, Mutex};
use std::fmt;

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
    
    // We would encapsulate a shared secret to their kyber PK.
    // X25519 ECDH
    let my_x25519_sk = x25519_dalek::StaticSecret::from(my_keys.x25519_sk);
    let their_x25519_public = X25519PublicKey::from(<[u8; 32]>::try_from(their_x25519_pk).unwrap());
    
    let dh_shared_secret = my_x25519_sk.diffie_hellman(&their_x25519_public);
    
    let kyber_pk = KyberPublicKey::from_bytes(&their_kyber_pk).map_err(|_| CryptoError::InvalidKeyLength)?;
    let (shared_secret, ciphertext) = encapsulate(&kyber_pk);
    
    // In a real protocol, you'd send the ciphertext back. For this demo we're just creating a master key structure.
    // Combine both secrets.
    let hkdf = Hkdf::<Sha256>::new(None, &dh_shared_secret.as_bytes().to_vec());
    let mut okm = [0u8; 32];
    let mut combined_material = Vec::new();
    combined_material.extend_from_slice(dh_shared_secret.as_bytes());
    combined_material.extend_from_slice(shared_secret.as_bytes());
    
    let hkdf = Hkdf::<Sha256>::new(None, &combined_material);
    hkdf.expand(b"mesh-chat-v1", &mut okm).unwrap();
    
    Ok(okm.to_vec())
}

// Symmetric Ratchet
#[derive(uniffi::Object)]
pub struct ChatSession {
    current_key: Mutex<[u8; 32]>,
}

#[uniffi::export]
impl ChatSession {
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
        let mut key_guard = self.current_key.lock().unwrap();
        let cipher = Aes256Gcm::new(aes_gcm::Key::<Aes256Gcm>::from_slice(&*key_guard));
        
        let mut nonce_bytes = [0u8; 12];
        OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);
        
        let ciphertext = cipher.encrypt(nonce, plaintext.as_bytes())
            .map_err(|_| CryptoError::EncryptionFailed)?;
            
        // Ratchet the key
        let hkdf = Hkdf::<Sha256>::new(None, &*key_guard);
        let mut next_key = [0u8; 32];
        hkdf.expand(b"ratchet", &mut next_key).unwrap();
        *key_guard = next_key;
        
        // Output format: Nonce (12) || Ciphertext
        let mut output = nonce_bytes.to_vec();
        output.extend(ciphertext);
        Ok(output)
    }
    
    pub fn decrypt_message(&self, encrypted_data: Vec<u8>) -> Result<String, CryptoError> {
        if encrypted_data.len() < 12 {
            return Err(CryptoError::DecryptionFailed);
        }
        
        let mut key_guard = self.current_key.lock().unwrap();
        let cipher = Aes256Gcm::new(aes_gcm::Key::<Aes256Gcm>::from_slice(&*key_guard));
        
        let nonce = Nonce::from_slice(&encrypted_data[0..12]);
        let ciphertext = &encrypted_data[12..];
        
        let plaintext = cipher.decrypt(nonce, ciphertext)
            .map_err(|_| CryptoError::DecryptionFailed)?;
            
        // Ratchet the key
        let hkdf = Hkdf::<Sha256>::new(None, &*key_guard);
        let mut next_key = [0u8; 32];
        hkdf.expand(b"ratchet", &mut next_key).unwrap();
        *key_guard = next_key;
        
        String::from_utf8(plaintext).map_err(|_| CryptoError::DecryptionFailed)
    }
}
mod tests;
