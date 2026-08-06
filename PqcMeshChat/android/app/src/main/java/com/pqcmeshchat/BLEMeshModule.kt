package com.pqcmeshchat

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import uniffi.rust_core.*
import java.util.UUID

class BLEMeshModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val SERVICE_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
    private val CHAR_UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
    private val parcelUuid = ParcelUuid(SERVICE_UUID)

    private val bluetoothManager: BluetoothManager = reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    
    // Hardcode 250 MTU for POC
    private val chunkingEngine = ChunkingEngine(250.toUShort())
    private val activeConnections = mutableMapOf<String, BluetoothDevice>()

    override fun getName(): String {
        return "BLEMeshModule"
    }

    @ReactMethod
    fun getMacAddress(promise: Promise) {
        try {
            var mac = bluetoothAdapter?.address
            if (mac == null || mac == "02:00:00:00:00:00") {
                val secureMac = android.provider.Settings.Secure.getString(
                    reactApplicationContext.contentResolver,
                    "bluetooth_address"
                )
                if (!secureMac.isNullOrEmpty()) {
                    mac = secureMac
                }
            }
            promise.resolve(mac ?: "02:00:00:00:00:00")
        } catch (e: Exception) {
            promise.resolve("02:00:00:00:00:00")
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun startAdvertising(promise: Promise) {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            promise.reject("BLE_UNAVAILABLE", "Bluetooth LE Advertiser not available")
            return
        }

        // 1. Start GATT Server
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
                
                if (characteristic.uuid == CHAR_UUID) {
                    try {
                        // Pass chunk to Rust
                        val chunk = deserializeChunk(value)
                        val reassemblyBuffer = ReassemblyBuffer() // Note: Should ideally be persistent
                        val reassembled = reassemblyBuffer.addChunk(chunk)
                        
                        if (reassembled != null) {
                            // We got the full message! Emit to React Native.
                            val map = Arguments.createMap()
                            map.putString("senderAddress", device.address)
                            
                            // Try to parse as String for this PoC (in reality it's encrypted ciphertext)
                            val messageStr = String(reassembled, Charsets.UTF_8)
                            map.putString("payload", messageStr)
                            
                            sendEvent("onMessageReceived", map)
                        }
                        
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    } catch (e: Exception) {
                        Log.e("BLEMeshModule", "Error processing chunk: ${e.message}")
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
                }
            }
        }

        gattServer = bluetoothManager.openGattServer(reactApplicationContext, serverCallback)
        
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)

        // 2. Start Advertising
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        promise.resolve("GATT Server & Advertising Started")
    }

    @ReactMethod
    fun stopAdvertising() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
    }
    
    @ReactMethod
    fun sendMessageToDevice(deviceAddress: String, message: String, promise: Promise) {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            promise.reject("INVALID_DEVICE", "Could not find device")
            return
        }
        
        // Use Rust chunking engine to split the payload
        val chunks = chunkingEngine.splitMessage(message.toByteArray(), 10.toUByte())
        
        if (chunks.isEmpty()) {
            promise.reject("CHUNKING_FAILED", "Failed to chunk message")
            return
        }

        var currentChunkIndex = 0

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestMtu(512) // Request maximum MTU
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeNextChunk(gatt)
                }
            }
            
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    currentChunkIndex++
                    writeNextChunk(gatt)
                } else {
                    Log.e("BLEMeshModule", "Failed to write chunk $currentChunkIndex")
                    gatt.disconnect()
                }
            }
            
            private fun writeNextChunk(gatt: BluetoothGatt) {
                if (currentChunkIndex < chunks.size) {
                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHAR_UUID)
                    
                    if (characteristic != null) {
                        val serializedChunk = serializeChunk(chunks[currentChunkIndex])
                        characteristic.value = serializedChunk
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(characteristic)
                    }
                } else {
                    Log.d("BLEMeshModule", "All chunks sent successfully!")
                    gatt.disconnect()
                }
            }
        }
        
        device.connectGatt(reactApplicationContext, false, gattCallback)
        promise.resolve("Message transmission started")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d("BLEMeshModule", "Advertising onStartSuccess")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BLEMeshModule", "Advertising onStartFailure: $errorCode")
        }
    }
}
