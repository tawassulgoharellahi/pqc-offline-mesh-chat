use crate::*;
    #[test]
    fn test_hybrid_handshake() {
        let alice_keys = IdentityKeys::generate();
        let bob_keys = IdentityKeys::generate();

        // Alice acts as the initiator, deriving secret for Bob.
        let master_key_alice = perform_hybrid_handshake(
            alice_keys.clone(),
            bob_keys.get_x25519_public_key(),
            bob_keys.get_kyber_public_key(),
        ).unwrap();

        assert_eq!(master_key_alice.len(), 32);
        
        // Let's test the ratcheting using the generated key!
        let alice_session = ChatSession::new(master_key_alice.clone()).unwrap();
        
        let encrypted = alice_session.encrypt_message("Hello Bob!".to_string()).unwrap();
        
        // Normally Bob would derive the same key on his side, but we mock it here
        // using Alice's master key since our handshake function only implements one side for this PoC.
        let bob_session = ChatSession::new(master_key_alice).unwrap();
        
        let decrypted = bob_session.decrypt_message(encrypted).unwrap();
        assert_eq!(decrypted, "Hello Bob!");
    }

    use crate::mesh_protocol::*;

    #[test]
    fn test_chunking_and_reassembly() {
        let engine = ChunkingEngine::new(500); // 500 byte MTU
        let dummy_payload = vec![0x42; 2500]; // 2500 bytes of data
        
        let chunks = engine.split_message(dummy_payload.clone(), 10);
        
        // 2500 bytes / (500 - 21) = 2500 / 479 = 5.21 -> 6 chunks
        assert_eq!(chunks.len(), 6);
        
        let buffer = ReassemblyBuffer::new();
        let mut reassembled = None;
        
        for chunk in chunks {
            // Serialize and deserialize to test wire format
            let data = serialize_chunk(&chunk);
            let parsed_chunk = deserialize_chunk(data).unwrap();
            
            if let Some(payload) = buffer.add_chunk(parsed_chunk).unwrap() {
                reassembled = Some(payload);
            }
        }
        
        assert_eq!(reassembled.unwrap(), dummy_payload);
    }
