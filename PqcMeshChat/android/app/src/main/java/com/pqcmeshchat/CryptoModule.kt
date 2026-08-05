package com.pqcmeshchat

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import uniffi.rust_core.*

class CryptoModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        var identityKeys: IdentityKeys? = null
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
    fun getPublicKeys(promise: Promise) {
        try {
            val keys = identityKeys
            if (keys == null) {
                promise.reject("NO_KEYS", "Keys have not been generated yet")
                return
            }
            val kyber = keys.getKyberPublicKey().joinToString("") { "%02x".format(it) }
            val x25519 = keys.getX25519PublicKey().joinToString("") { "%02x".format(it) }
            promise.resolve("Kyber: ${kyber.take(16)}...\nX25519: ${x25519.take(16)}...")
        } catch (e: Exception) {
            promise.reject("GET_KEYS_FAILED", e)
        }
    }
}
