package com.pqcmeshchat

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import uniffi.rust_core.*
import java.util.UUID

class BLEMeshModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val SERVICE_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
    private val CHAR_UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
    private val parcelUuid = ParcelUuid(SERVICE_UUID)

    private val bluetoothManager: BluetoothManager = reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val chunkingEngine = ChunkingEngine(250.toUShort())
    private val reassemblyBuffer = ReassemblyBuffer()

    private data class RelayPacket(
        val dest: String,
        val sender: String,
        val msgId: String,
        val ttl: Int,
        val payload: String
    )
    
    private val seenMsgIds = mutableSetOf<String>()
    private val relayQueue = mutableListOf<RelayPacket>()
    private val discoveredPeers = mutableMapOf<String, String>()

    override fun getName(): String {
        return "BLEMeshModule"
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun getMacAddress(promise: Promise) {
        try {
            val mac = bluetoothAdapter?.address
            if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
                promise.resolve(mac)
            } else {
                val nodeId = "NODE_" + (android.os.Build.MODEL.replace(" ", "") + "_" + android.os.Build.BOARD).take(12)
                promise.resolve(nodeId)
            }
        } catch (e: Exception) {
            promise.resolve("NODE_DEVICE")
        }
    }

    @ReactMethod
    fun getRelayedCount(promise: Promise) {
        promise.resolve(relayQueue.size)
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
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }

                        val chunk = deserializeChunk(value)
                        val reassembled = reassemblyBuffer.addChunk(chunk)
                        
                        if (reassembled != null) {
                            val rawString = String(reassembled, Charsets.UTF_8)
                            Log.i("BLEMeshModule", "Reassembled complete message payload: $rawString")
                            
                            var type = "MSG"
                            var dest = ""
                            var sender = device.address
                            var msgId = UUID.randomUUID().toString()
                            var ttl = 3
                            var payload = rawString
                            var keysData = ""

                            try {
                                val json = JSONObject(rawString)
                                if (json.has("type")) type = json.getString("type")
                                if (json.has("dest")) dest = json.getString("dest")
                                if (json.has("sender")) sender = json.getString("sender")
                                if (json.has("msgId")) msgId = json.getString("msgId")
                                if (json.has("ttl")) ttl = json.getInt("ttl")
                                if (json.has("payload")) payload = json.getString("payload")
                                if (json.has("keys")) keysData = json.getString("keys")
                            } catch (e: Exception) {
                                // Raw payload fallback
                            }

                            val isSenderMacValid = BluetoothAdapter.checkBluetoothAddress(sender)
                            val senderNode = if (isSenderMacValid) sender else device.address

                            if (type == "KEY_REQ") {
                                Log.i("BLEMeshModule", "Received KEY_REQ from $senderNode")
                                if (keysData.isNotEmpty()) {
                                    val map = Arguments.createMap()
                                    map.putString("senderAddress", senderNode)
                                    map.putString("keys", keysData)
                                    sendEvent("onHandshakeKeysReceived", map)
                                }

                                val myKeys = CryptoModule.identityKeys?.exportPublicKeysBase64() ?: ""
                                if (myKeys.isNotEmpty()) {
                                    val keyRespJson = JSONObject().apply {
                                        put("type", "KEY_RESP")
                                        put("sender", bluetoothAdapter?.address ?: "02:00:00:00:00:00")
                                        put("keys", myKeys)
                                    }.toString()
                                    sendMessageToDeviceInternal(device.address, keyRespJson, null)
                                }
                            } else if (type == "KEY_RESP") {
                                Log.i("BLEMeshModule", "Received KEY_RESP from $senderNode")
                                val map = Arguments.createMap()
                                map.putString("senderAddress", senderNode)
                                map.putString("keys", keysData)
                                sendEvent("onHandshakeKeysReceived", map)
                            } else {
                                if (seenMsgIds.contains(msgId)) {
                                    return
                                }
                                seenMsgIds.add(msgId)

                                val myMac = bluetoothAdapter?.address ?: "02:00:00:00:00:00"
                                val isForMe = dest.isEmpty() || 
                                              dest.equals(myMac, ignoreCase = true) || 
                                              myMac == "02:00:00:00:00:00" || 
                                              myMac.startsWith("NODE_") || 
                                              dest.startsWith("NODE_")

                                Log.i("BLEMeshModule", "Message received on GATT Server. isForMe=$isForMe, dest=$dest, myMac=$myMac")

                                if (isForMe) {
                                    val map = Arguments.createMap()
                                    map.putString("senderAddress", senderNode)
                                    map.putString("payload", payload)
                                    sendEvent("onMessageReceived", map)
                                } else if (ttl > 1) {
                                    val newTtl = ttl - 1
                                    val packet = RelayPacket(dest, senderNode, msgId, newTtl, payload)
                                    relayQueue.add(packet)

                                    val relayMap = Arguments.createMap()
                                    relayMap.putString("senderAddress", senderNode)
                                    relayMap.putString("destAddress", dest)
                                    relayMap.putInt("ttl", newTtl)
                                    sendEvent("onMessageRelayed", relayMap)

                                    attemptForwardQueuedMessages()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BLEMeshModule", "Error processing chunk: ${e.message}")
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
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(parcelUuid)
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        promise.resolve("GATT Server & Advertising Started")
    }

    @ReactMethod
    fun startPeerDiscovery(promise: Promise) {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            promise.reject("SCANNER_UNAVAILABLE", "Bluetooth LE Scanner unavailable")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(parcelUuid)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        discoveredPeers.clear()
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        promise.resolve("Peer Discovery Started")
    }

    @ReactMethod
    fun stopPeerDiscovery() {
        bleScanner?.stopScan(scanCallback)
    }

    @ReactMethod
    fun requestPqcKeysOverBle(deviceAddress: String, senderMacAddress: String, promise: Promise) {
        val myKeys = CryptoModule.identityKeys?.exportPublicKeysBase64() ?: ""
        val reqJson = JSONObject().apply {
            put("type", "KEY_REQ")
            put("sender", if (BluetoothAdapter.checkBluetoothAddress(senderMacAddress)) senderMacAddress else (bluetoothAdapter?.address ?: "02:00:00:00:00:00"))
            put("keys", myKeys)
        }.toString()
        sendMessageToDeviceInternal(deviceAddress, reqJson, promise)
    }

    @ReactMethod
    fun stopAdvertising() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
    }

    private fun attemptForwardQueuedMessages() {
        if (relayQueue.isEmpty()) return
        val iterator = relayQueue.iterator()
        while (iterator.hasNext()) {
            val packet = iterator.next()
            val targetMac = if (BluetoothAdapter.checkBluetoothAddress(packet.dest)) {
                packet.dest
            } else {
                discoveredPeers.entries.firstOrNull { it.value.equals(packet.dest, ignoreCase = true) }?.key
            }
            if (targetMac != null && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
                val device = bluetoothAdapter?.getRemoteDevice(targetMac)
                if (device != null) {
                    val envelope = JSONObject().apply {
                        put("type", "MSG")
                        put("dest", packet.dest)
                        put("sender", packet.sender)
                        put("msgId", packet.msgId)
                        put("ttl", packet.ttl)
                        put("payload", packet.payload)
                    }.toString()

                    val chunks = chunkingEngine.splitMessage(envelope.toByteArray(), packet.ttl.toUByte())
                    if (chunks.isNotEmpty()) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun sendMessageToDeviceInternal(deviceAddress: String, message: String, promise: Promise?) {
        try {
            stopPeerDiscovery()
        } catch (e: Exception) {
            Log.e("BLEMeshModule", "Error stopping discovery: ${e.message}")
        }

        val targetMac = if (BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
            deviceAddress
        } else {
            discoveredPeers.entries.firstOrNull { it.value.equals(deviceAddress, ignoreCase = true) }?.key ?: deviceAddress
        }

        if (!BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            promise?.reject("INVALID_ADDRESS", "'$deviceAddress' is not a valid Bluetooth MAC address.")
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(targetMac)
        if (device == null) {
            promise?.reject("INVALID_DEVICE", "Could not find device $targetMac")
            return
        }

        val chunks = chunkingEngine.splitMessage(message.toByteArray(), 5.toUByte())
        if (chunks.isEmpty()) {
            promise?.reject("CHUNKING_FAILED", "Failed to chunk message")
            return
        }

        var currentChunkIndex = 0
        var servicesDiscovered = false

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i("BLEMeshModule", "onConnectionStateChange: status=$status, newState=$newState")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("BLEMeshModule", "GATT connection error status=$status, closing connection...")
                    gatt.close()
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i("BLEMeshModule", "Connected to GATT server $targetMac")
                    val mtuReq = gatt.requestMtu(512)
                    if (!mtuReq) {
                        Log.i("BLEMeshModule", "requestMtu failed, discovering services immediately...")
                        gatt.discoverServices()
                    } else {
                        mainHandler.postDelayed({
                            if (!servicesDiscovered) {
                                Log.i("BLEMeshModule", "MTU timeout fallback, discovering services...")
                                gatt.discoverServices()
                            }
                        }, 300)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("BLEMeshModule", "Disconnected from GATT server $targetMac")
                    gatt.close()
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i("BLEMeshModule", "onMtuChanged: mtu=$mtu, status=$status")
                if (!servicesDiscovered) {
                    servicesDiscovered = true
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.i("BLEMeshModule", "onServicesDiscovered: status=$status")
                servicesDiscovered = true
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeNextChunk(gatt)
                } else {
                    Log.e("BLEMeshModule", "Service discovery failed with status $status")
                    gatt.disconnect()
                }
            }
            
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.i("BLEMeshModule", "onCharacteristicWrite: chunk=$currentChunkIndex/${chunks.size}, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    currentChunkIndex++
                    mainHandler.postDelayed({
                        writeNextChunk(gatt)
                    }, 25)
                } else {
                    Log.e("BLEMeshModule", "Failed to write chunk $currentChunkIndex, status=$status")
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
                        val success = gatt.writeCharacteristic(characteristic)
                        Log.i("BLEMeshModule", "Writing chunk $currentChunkIndex/${chunks.size} (success=$success)...")
                    } else {
                        Log.e("BLEMeshModule", "Target GATT Service or Characteristic not found on device!")
                        gatt.disconnect()
                    }
                } else {
                    Log.i("BLEMeshModule", "All ${chunks.size} chunks sent successfully!")
                    mainHandler.postDelayed({
                        gatt.disconnect()
                    }, 500)
                }
            }
        }
        
        Log.i("BLEMeshModule", "Connecting GATT to device $targetMac via TRANSPORT_LE...")
        device.connectGatt(reactApplicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        promise?.resolve("Message transmission started")
    }

    @ReactMethod
    fun sendMessageToDevice(deviceAddress: String, message: String, senderMacAddress: String, promise: Promise) {
        val envelope = JSONObject().apply {
            put("type", "MSG")
            put("dest", deviceAddress)
            put("sender", if (BluetoothAdapter.checkBluetoothAddress(senderMacAddress)) senderMacAddress else (bluetoothAdapter?.address ?: "02:00:00:00:00:00"))
            put("msgId", UUID.randomUUID().toString())
            put("ttl", 5)
            put("payload", message)
        }.toString()
        
        sendMessageToDeviceInternal(deviceAddress, envelope, promise)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val address = device.address
            val name = device.name ?: "PQC Node (${address.takeLast(5)})"

            if (!discoveredPeers.containsKey(address)) {
                discoveredPeers[address] = name
                val map = Arguments.createMap()
                map.putString("address", address)
                map.putString("name", name)
                map.putInt("rssi", result.rssi)
                sendEvent("onPeerDiscovered", map)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLEMeshModule", "BLE Scan Failed: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.i("BLEMeshModule", "Advertising onStartSuccess")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BLEMeshModule", "Advertising onStartFailure: $errorCode")
        }
    }
}
