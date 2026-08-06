package com.pqcmeshchat

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import uniffi.rust_core.*
import android.util.Base64
import org.json.JSONObject

class CryptoModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        var identityKeys: IdentityKeys? = null
        var chatSession: ChatSession? = null
    }

    override fun getName(): String {
        return "CryptoModule"
    }

    @ReactMethod
    fun generateKeys(promise: Promise) {
        try {
            identityKeys = IdentityKeys.generate()
            promise.resolve("Keys Generated Successfully")
        } catch (e: Exception) {
            promise.reject("KEY_GEN_FAILED", e)
        }
    }

    @ReactMethod
    fun exportPublicKeysBase64(promise: Promise) {
        try {
            val keys = identityKeys
            if (keys == null) {
                promise.reject("NO_KEYS", "Keys have not been generated yet")
                return
            }
            val base64Json = keys.exportPublicKeysBase64()
            promise.resolve(base64Json)
        } catch (e: Exception) {
            promise.reject("EXPORT_KEYS_FAILED", e)
        }
    }

    @ReactMethod
    fun initiateHandshake(theirPublicKeysJson: String, promise: Promise) {
        try {
            val keys = identityKeys
            if (keys == null) {
                promise.reject("NO_KEYS", "Identity keys have not been generated yet")
                return
            }
            
            val json = JSONObject(theirPublicKeysJson)
            val theirX25519Base64 = json.getString("x25519")
            val theirKyberBase64 = json.getString("kyber")
            
            val theirX25519Bytes = Base64.decode(theirX25519Base64, Base64.DEFAULT)
            val theirKyberBytes = Base64.decode(theirKyberBase64, Base64.DEFAULT)

            // Derive master key
            val masterKey = performHybridHandshake(keys, theirX25519Bytes, theirKyberBytes)
            
            // Initialize chat session
            chatSession = ChatSession(masterKey)
            
            promise.resolve("Handshake successful, secure session established")
        } catch (e: Exception) {
            promise.reject("HANDSHAKE_FAILED", e)
        }
    }
    
    @ReactMethod
    fun encryptMessage(plaintext: String, promise: Promise) {
        try {
            val session = chatSession
            if (session == null) {
                promise.reject("NO_SESSION", "Secure session not established")
                return
            }
            
            val ciphertextBytes = session.encryptMessage(plaintext)
            val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
            promise.resolve(ciphertextBase64)
        } catch (e: Exception) {
            promise.reject("ENCRYPTION_FAILED", e)
        }
    }

    @ReactMethod
    fun decryptMessage(ciphertextBase64: String, promise: Promise) {
        try {
            val session = chatSession
            if (session == null) {
                promise.reject("NO_SESSION", "Secure session not established")
                return
            }
            
            val ciphertextBytes = Base64.decode(ciphertextBase64, Base64.DEFAULT)
            val plaintext = session.decryptMessage(ciphertextBytes)
            promise.resolve(plaintext)
        } catch (e: Exception) {
            promise.reject("DECRYPTION_FAILED", e)
        }
    }
}
