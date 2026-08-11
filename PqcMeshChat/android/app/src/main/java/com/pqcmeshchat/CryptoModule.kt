package com.pqcmeshchat

import android.content.Context
import android.content.SharedPreferences
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import uniffi.rust_core.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class CryptoModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val PREFS_NAME = "pqc_crypto_prefs"
        private const val KEY_PRIVATE_IDENTITY = "identity_private_keys"
        private const val KEY_MASTER_KEY = "session_master_key"
        private const val KEY_TARGET_MAC = "session_target_mac"

        var identityKeys: IdentityKeys? = null
        var chatSession: ChatSession? = null
    }

    private val prefs: SharedPreferences by lazy {
        reactApplicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getName(): String {
        return "CryptoModule"
    }

    @ReactMethod
    fun generateKeys(promise: Promise) {
        try {
            if (identityKeys == null) {
                val savedPrivateKeysB64 = prefs.getString(KEY_PRIVATE_IDENTITY, null)
                if (!savedPrivateKeysB64.isNullOrEmpty()) {
                    try {
                        identityKeys = importIdentityKeys(savedPrivateKeysB64)
                        android.util.Log.i("CryptoModule", "Restored existing PQC identity keys from storage")
                    } catch (e: Exception) {
                        android.util.Log.w("CryptoModule", "Failed to import saved keys, generating new pair: ${e.message}")
                        identityKeys = null
                    }
                }

                if (identityKeys == null) {
                    val newKeys = IdentityKeys.generate()
                    identityKeys = newKeys
                    val privateKeysB64 = newKeys.exportPrivateKeysBase64()
                    prefs.edit().putString(KEY_PRIVATE_IDENTITY, privateKeysB64).apply()
                    android.util.Log.i("CryptoModule", "Generated and stored new PQC identity keys")
                }
            }
            promise.resolve("Keys Generated Successfully")
        } catch (e: Exception) {
            promise.reject("KEY_GEN_FAILED", e)
        }
    }

    @ReactMethod
    fun resetSession(promise: Promise) {
        try {
            chatSession = null
            prefs.edit().remove(KEY_MASTER_KEY).remove(KEY_TARGET_MAC).apply()
            
            val newKeys = IdentityKeys.generate()
            identityKeys = newKeys
            val privateKeysB64 = newKeys.exportPrivateKeysBase64()
            prefs.edit().putString(KEY_PRIVATE_IDENTITY, privateKeysB64).apply()
            
            val newKeysJson = newKeys.exportPublicKeysBase64()
            android.util.Log.i("CryptoModule", "Session purged and new identity keys stored successfully")
            promise.resolve(newKeysJson)
        } catch (e: Exception) {
            promise.reject("RESET_FAILED", e)
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
            val keyFingerprint = Base64.encodeToString(masterKey.take(6).toByteArray(), Base64.NO_WRAP)
            android.util.Log.i("CryptoModule", "Derived PQC MasterKey fingerprint: $keyFingerprint")
            
            // Initialize chat session
            chatSession = ChatSession(masterKey)
            
            // Save master key to SharedPreferences
            val masterKeyB64 = Base64.encodeToString(masterKey, Base64.NO_WRAP)
            prefs.edit().putString(KEY_MASTER_KEY, masterKeyB64).apply()
            
            promise.resolve("Handshake successful, secure session established")
        } catch (e: Exception) {
            promise.reject("HANDSHAKE_FAILED", e)
        }
    }

    @ReactMethod
    fun setTargetDevice(targetMac: String, promise: Promise) {
        try {
            prefs.edit().putString(KEY_TARGET_MAC, targetMac).apply()
            promise.resolve("Target MAC saved")
        } catch (e: Exception) {
            promise.reject("SET_TARGET_FAILED", e)
        }
    }

    @ReactMethod
    fun restoreSession(promise: Promise) {
        try {
            val masterKeyB64 = prefs.getString(KEY_MASTER_KEY, null)
            val targetMac = prefs.getString(KEY_TARGET_MAC, null)

            val result = Arguments.createMap()
            if (!masterKeyB64.isNullOrEmpty() && !targetMac.isNullOrEmpty()) {
                val masterKeyBytes = Base64.decode(masterKeyB64, Base64.DEFAULT)
                chatSession = ChatSession(masterKeyBytes)
                result.putBoolean("restored", true)
                result.putString("targetMac", targetMac)
                android.util.Log.i("CryptoModule", "Restored existing PQC session for peer $targetMac")
            } else {
                result.putBoolean("restored", false)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            android.util.Log.w("CryptoModule", "Session restore error: ${e.message}")
            val result = Arguments.createMap()
            result.putBoolean("restored", false)
            promise.resolve(result)
        }
    }

    @ReactMethod
    fun clearSession(promise: Promise) {
        try {
            chatSession = null
            prefs.edit().remove(KEY_MASTER_KEY).remove(KEY_TARGET_MAC).apply()
            android.util.Log.i("CryptoModule", "Session and target MAC cleared")
            promise.resolve("Session cleared")
        } catch (e: Exception) {
            promise.reject("CLEAR_FAILED", e)
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

    @ReactMethod
    fun compressData(base64Input: String, promise: Promise) {
        try {
            val inputBytes = Base64.decode(base64Input, Base64.DEFAULT)
            val compressedBytes = compressData(inputBytes)
            val compressedBase64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
            promise.resolve(compressedBase64)
        } catch (e: Exception) {
            promise.reject("COMPRESS_FAILED", e)
        }
    }

    @ReactMethod
    fun decompressData(base64Input: String, promise: Promise) {
        try {
            val inputBytes = Base64.decode(base64Input, Base64.DEFAULT)
            val decompressedBytes = decompressData(inputBytes)
            val decompressedBase64 = Base64.encodeToString(decompressedBytes, Base64.NO_WRAP)
            promise.resolve(decompressedBase64)
        } catch (e: Exception) {
            promise.reject("DECOMPRESS_FAILED", e)
        }
    }

    @ReactMethod
    fun emergencyWipe(promise: Promise) {
        try {
            // Zeroize in-memory keys
            chatSession = null
            identityKeys = null

            // Purge SharedPreferences completely
            prefs.edit().clear().apply()

            // Also clear any background-persisted pending messages
            reactApplicationContext.getSharedPreferences(
                BLEMeshModule.PENDING_MSGS_PREFS, Context.MODE_PRIVATE
            ).edit().clear().apply()

            // Generate fresh keys for clean restart
            val newKeys = IdentityKeys.generate()
            identityKeys = newKeys
            val privateKeysB64 = newKeys.exportPrivateKeysBase64()
            prefs.edit().putString(KEY_PRIVATE_IDENTITY, privateKeysB64).apply()

            val newKeysJson = newKeys.exportPublicKeysBase64()
            android.util.Log.i("CryptoModule", "EMERGENCY WIPE EXECUTED: All keys, secrets, and preferences zeroized")
            promise.resolve(newKeysJson)
        } catch (e: Exception) {
            promise.reject("WIPE_FAILED", e)
        }
    }

    /**
     * Returns all messages that arrived while the app was backgrounded and then
     * clears the store atomically so they are never shown twice.
     * Each element: { sender: String, payload: String, timestamp: Long }
     */
    @ReactMethod
    fun getPendingMessages(promise: Promise) {
        try {
            val pendingPrefs = reactApplicationContext.getSharedPreferences(
                BLEMeshModule.PENDING_MSGS_PREFS, Context.MODE_PRIVATE
            )
            val raw = pendingPrefs.getString(BLEMeshModule.PENDING_MSGS_KEY, "[]") ?: "[]"
            // Clear immediately so messages are never double-shown
            pendingPrefs.edit().remove(BLEMeshModule.PENDING_MSGS_KEY).apply()

            val arr = JSONArray(raw)
            val result = Arguments.createArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val map = Arguments.createMap()
                map.putString("sender", obj.optString("sender", ""))
                map.putString("payload", obj.optString("payload", ""))
                map.putDouble("timestamp", obj.optLong("timestamp", 0L).toDouble())
                result.pushMap(map)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.resolve(Arguments.createArray())
        }
    }

    @ReactMethod
    fun signData(messageBase64: String, promise: Promise) {
        try {
            val keys = identityKeys
            if (keys == null) {
                promise.reject("NO_KEYS", "Identity keys not initialized")
                return
            }
            val messageBytes = Base64.decode(messageBase64, Base64.DEFAULT)
            val sigBytes = keys.signData(messageBytes)
            val sigBase64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)
            promise.resolve(sigBase64)
        } catch (e: Exception) {
            promise.reject("SIGN_FAILED", e)
        }
    }

    @ReactMethod
    fun verifySignature(pubKeyBase64: String, messageBase64: String, signatureBase64: String, promise: Promise) {
        try {
            val pubKeyBytes = Base64.decode(pubKeyBase64, Base64.DEFAULT)
            val messageBytes = Base64.decode(messageBase64, Base64.DEFAULT)
            val sigBytes = Base64.decode(signatureBase64, Base64.DEFAULT)

            val isValid = verifySignature(pubKeyBytes, messageBytes, sigBytes)
            promise.resolve(isValid)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }
}
