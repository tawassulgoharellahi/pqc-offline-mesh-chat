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

    #[test]
    fn test_lz4_standalone_compression() {
        // Small payload <= 100 bytes (should remain raw with 0x00 header)
        let small_input = b"Small payload test".to_vec();
        let compressed_small = compress_data(small_input.clone());
        assert_eq!(compressed_small[0], 0x00);
        let decompressed_small = decompress_data(compressed_small).unwrap();
        assert_eq!(decompressed_small, small_input);

        // Large repetitive payload > 100 bytes (should compress with 0x01 header)
        let large_input = "Post-Quantum Cryptography Mesh Network LZ4 Compression Test ".repeat(20).into_bytes();
        assert!(large_input.len() > 100);
        let compressed_large = compress_data(large_input.clone());
        assert_eq!(compressed_large[0], 0x01);
        assert!(compressed_large.len() < large_input.len()); // Verify size reduction!
        
        let decompressed_large = decompress_data(compressed_large).unwrap();
        assert_eq!(decompressed_large, large_input);
    }

    #[test]
    fn test_large_message_encryption_and_lz4() {
        let alice_keys = IdentityKeys::generate();
        let bob_keys = IdentityKeys::generate();

        let master_key = perform_hybrid_handshake(
            alice_keys.clone(),
            bob_keys.get_x25519_public_key(),
            bob_keys.get_kyber_public_key(),
        ).unwrap();

        let session = ChatSession::new(master_key).unwrap();
        let large_msg = "Hello World! This is a long post-quantum encrypted message over BLE. ".repeat(15);
        
        let encrypted = session.encrypt_message(large_msg.clone()).unwrap();
        let decrypted = session.decrypt_message(encrypted).unwrap();
        
        assert_eq!(decrypted, large_msg);
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
