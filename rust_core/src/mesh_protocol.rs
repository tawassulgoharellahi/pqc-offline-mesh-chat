use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use sha2::{Sha256, Digest};
use rand_core::{OsRng, RngCore};

#[derive(uniffi::Error, Debug)]
pub enum ProtocolError {
    InvalidChunkSize,
    InvalidMessageId,
    MessageReassemblyIncomplete,
}

impl std::fmt::Display for ProtocolError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}
impl std::error::Error for ProtocolError {}

/// A single chunk of a larger message, ready to be transmitted over BLE GATT.
#[derive(uniffi::Record)]
pub struct MeshChunk {
    pub ttl: u8,
    pub msg_id: Vec<u8>, // 16 bytes
    pub chunk_index: u16,
    pub total_chunks: u16,
    pub payload: Vec<u8>,
}

#[uniffi::export]
pub fn serialize_chunk(chunk: &MeshChunk) -> Vec<u8> {
    let mut data = Vec::with_capacity(1 + 16 + 2 + 2 + chunk.payload.len());
    data.push(chunk.ttl);
    data.extend_from_slice(&chunk.msg_id);
    data.extend_from_slice(&chunk.chunk_index.to_be_bytes());
    data.extend_from_slice(&chunk.total_chunks.to_be_bytes());
    data.extend_from_slice(&chunk.payload);
    data
}

#[uniffi::export]
pub fn deserialize_chunk(data: Vec<u8>) -> Result<MeshChunk, ProtocolError> {
    if data.len() < 21 {
        return Err(ProtocolError::InvalidChunkSize);
    }
    let ttl = data[0];
    let msg_id = data[1..17].to_vec();
    let chunk_index = u16::from_be_bytes([data[17], data[18]]);
    let total_chunks = u16::from_be_bytes([data[19], data[20]]);
    let payload = data[21..].to_vec();

    Ok(MeshChunk {
        ttl,
        msg_id,
        chunk_index,
        total_chunks,
        payload,
    })
}

#[derive(uniffi::Object)]
pub struct ChunkingEngine {
    mtu_size: u16,
    seen_messages: Mutex<HashSet<Vec<u8>>>,
}

#[uniffi::export]
impl ChunkingEngine {
    #[uniffi::constructor]
    pub fn new(mtu_size: u16) -> Arc<Self> {
        Arc::new(Self {
            mtu_size,
            seen_messages: Mutex::new(HashSet::new()),
        })
    }

    /// Splits a full encrypted payload into MTU-sized chunks.
    pub fn split_message(&self, payload: Vec<u8>, ttl: u8) -> Vec<MeshChunk> {
        // Generate a 16-byte Message ID
        let mut hasher = Sha256::new();
        hasher.update(&payload);
        
        // Let's add some randomness to the hash to ensure uniqueness even if payload is identical
        let mut random_bytes = [0u8; 8];
        OsRng.fill_bytes(&mut random_bytes);
        hasher.update(&random_bytes);
        
        let hash_result = hasher.finalize();
        let msg_id = hash_result[0..16].to_vec();

        // Mark as seen so we don't bounce our own message
        self.seen_messages.lock().unwrap().insert(msg_id.clone());

        // Calculate chunk payload capacity: MTU - header_size
        // Header size = 1 (ttl) + 16 (msg_id) + 2 (index) + 2 (total) = 21 bytes
        let header_size = 21;
        let chunk_payload_capacity = (self.mtu_size as usize).saturating_sub(header_size);
        if chunk_payload_capacity == 0 {
            // MTU too small
            return vec![];
        }

        let total_chunks = (payload.len() + chunk_payload_capacity - 1) / chunk_payload_capacity;
        let mut chunks = Vec::with_capacity(total_chunks);

        for i in 0..total_chunks {
            let start = i * chunk_payload_capacity;
            let end = std::cmp::min(start + chunk_payload_capacity, payload.len());
            let chunk_payload = payload[start..end].to_vec();

            chunks.push(MeshChunk {
                ttl,
                msg_id: msg_id.clone(),
                chunk_index: i as u16,
                total_chunks: total_chunks as u16,
                payload: chunk_payload,
            });
        }

        chunks
    }

    /// Checks if we have already seen this message ID.
    pub fn is_message_seen(&self, msg_id: Vec<u8>) -> bool {
        self.seen_messages.lock().unwrap().contains(&msg_id)
    }

    /// Marks a message as seen.
    pub fn mark_message_seen(&self, msg_id: Vec<u8>) {
        self.seen_messages.lock().unwrap().insert(msg_id);
    }
}

/// A buffer to hold incoming chunks until the full message is received.
#[derive(uniffi::Object)]
pub struct ReassemblyBuffer {
    // msg_id -> (total_chunks, Vec<Option<Payload>>)
    buffers: Mutex<std::collections::HashMap<Vec<u8>, (u16, Vec<Option<Vec<u8>>>)>>,
}

#[uniffi::export]
impl ReassemblyBuffer {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            buffers: Mutex::new(std::collections::HashMap::new()),
        })
    }

    /// Adds a chunk to the buffer. Returns the full reassembled payload if complete, else None.
    pub fn add_chunk(&self, chunk: MeshChunk) -> Result<Option<Vec<u8>>, ProtocolError> {
        let mut buffers = self.buffers.lock().unwrap();

        let (total, parts) = buffers.entry(chunk.msg_id.clone()).or_insert_with(|| {
            (chunk.total_chunks, vec![None; chunk.total_chunks as usize])
        });

        if chunk.chunk_index >= *total {
            return Err(ProtocolError::InvalidChunkSize); // Invalid index
        }

        parts[chunk.chunk_index as usize] = Some(chunk.payload);

        // Check if all parts are present
        if parts.iter().all(|p| p.is_some()) {
            let mut full_payload = Vec::new();
            for part in parts.iter() {
                full_payload.extend_from_slice(part.as_ref().unwrap());
            }
            
            // We can remove it from the buffer now
            buffers.remove(&chunk.msg_id);
            
            return Ok(Some(full_payload));
        }

        Ok(None)
    }
}
