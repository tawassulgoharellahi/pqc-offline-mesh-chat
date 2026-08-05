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
