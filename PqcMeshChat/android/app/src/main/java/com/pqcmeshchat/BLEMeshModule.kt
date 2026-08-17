package com.pqcmeshchat

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import uniffi.rust_core.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BLEMeshModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val PENDING_MSGS_PREFS = "pqc_pending_messages"
        const val PENDING_MSGS_KEY = "pending_list"
    }

    private val SERVICE_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
    private val CHAR_UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
    private val parcelUuid = ParcelUuid(SERVICE_UUID)
    private val CHANNEL_ID = "pqc_messages_channel"

    private val bluetoothManager: BluetoothManager = reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val chunkingEngine = ChunkingEngine(250.toUShort())
    private val reassemblyBuffer = ReassemblyBuffer()

    // On-Disk Atomic FIFO Queue Database
    private val fifoDb: DiskQueueDatabase by lazy {
        DiskQueueDatabase.getInstance(reactApplicationContext)
    }

    private data class RelayPacket(
        val dest: String,
        val sender: String,
        val msgId: String,
        val ttl: Int,
        val payload: String
    )

    private val seenMsgIds = mutableSetOf<String>()
    private val relayQueue = mutableListOf<RelayPacket>()

    // Live Routing & Discovery Maps
    private val discoveredPeers = ConcurrentHashMap<String, String>() // MAC -> NodeId/Name
    private val nodeToMacMap = ConcurrentHashMap<String, String>()     // NodeId -> MAC

    // Persistent Long-Lived Connection Pool
    private val connectionPool = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectionStateMap = ConcurrentHashMap<String, Int>()

    private val isDrainingFifo = AtomicBoolean(false)

    private val fifoDrainRunnable = object : Runnable {
        override fun run() {
            triggerFifoDrain()
            mainHandler.postDelayed(this, 1500)
        }
    }

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.w("BLEMeshModule", "Bluetooth turned OFF!")
                    closeAllConnections()
                    try {
                        gattServer?.close()
                    } catch (e: Exception) {}
                    gattServer = null

                    val params = Arguments.createMap()
                    params.putBoolean("enabled", false)
                    sendEvent("onBluetoothStateChanged", params)
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i("BLEMeshModule", "Bluetooth turned ON: Restoring server, advertiser, scanner, and FIFO worker...")
                    val params = Arguments.createMap()
                    params.putBoolean("enabled", true)
                    sendEvent("onBluetoothStateChanged", params)

                    mainHandler.postDelayed({
                        startAdvertisingInternal()
                        startPeerDiscovery()
                        triggerFifoDrain()
                    }, 1200)
                }
            }
        }
    }

    init {
        createNotificationChannel()
        fifoDb.resetInFlightToPending()
        try {
            val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            reactApplicationContext.registerReceiver(bluetoothStateReceiver, filter)
        } catch (e: Exception) {
            Log.w("BLEMeshModule", "Error registering bluetoothStateReceiver: ${e.message}")
        }
        mainHandler.postDelayed(fifoDrainRunnable, 1500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PQC Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Offline PQC Encrypted Messages"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            val notificationManager = reactApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun isAppInForeground(): Boolean {
        try {
            val activityManager = reactApplicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val appProcesses = activityManager.runningAppProcesses ?: return false
            val packageName = reactApplicationContext.packageName
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w("BLEMeshModule", "Foreground check error: ${e.message}")
        }
        return false
    }

    private fun playInAppChimeSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(reactApplicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.volume = 0.35f
            }
            ringtone.play()
        } catch (e: Exception) {
            Log.w("BLEMeshModule", "Chime play error: ${e.message}")
        }
    }

    private fun showSystemNotification(sender: String) {
        val intent = Intent(reactApplicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            reactApplicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(reactApplicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New PQC Message")
            .setContentText("Message received from peer (${sender.takeLast(8)})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = NotificationManagerCompat.from(reactApplicationContext)
        try {
            notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
        } catch (e: SecurityException) {
            Log.w("BLEMeshModule", "Notification permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.w("BLEMeshModule", "Notification post error: ${e.message}")
        }
    }

    private fun persistPendingMessage(sender: String, payload: String) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences(PENDING_MSGS_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(PENDING_MSGS_KEY, "[]") ?: "[]"
            val arr = JSONArray(existing)
            val entry = JSONObject().apply {
                put("sender", sender)
                put("payload", payload)
                put("timestamp", System.currentTimeMillis())
            }
            arr.put(entry)
            prefs.edit().putString(PENDING_MSGS_KEY, arr.toString()).apply()
            Log.i("BLEMeshModule", "Persisted pending message from $sender (total ${arr.length()})")
        } catch (e: Exception) {
            Log.w("BLEMeshModule", "Failed to persist pending message: ${e.message}")
        }
    }

    private fun triggerMessageAudioNotification(sender: String) {
        mainHandler.post {
            if (isAppInForeground()) {
                playInAppChimeSound()
            } else {
                showSystemNotification(sender)
            }
        }
    }

    override fun getName(): String {
        return "BLEMeshModule"
    }

    private fun isValidTargetMac(mac: String?): Boolean {
        if (mac.isNullOrEmpty()) return false
        if (mac == "02:00:00:00:00:00" || mac.startsWith("02:00:00") || mac.startsWith("NODE_")) return false
        return BluetoothAdapter.checkBluetoothAddress(mac)
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private fun getLocalNodeId(): String {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("pqc_crypto_prefs", Context.MODE_PRIVATE)
            var nodeId = prefs.getString("NODE_ID", null)
            if (nodeId == null) {
                nodeId = "NODE_" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
                prefs.edit().putString("NODE_ID", nodeId).apply()
            }
            return nodeId
        } catch (e: Exception) {
            return "NODE_DEVICE"
        }
    }

    @ReactMethod
    fun getMacAddress(promise: Promise) {
        promise.resolve(getLocalNodeId())
    }

    @ReactMethod
    fun getRelayedCount(promise: Promise) {
        promise.resolve(relayQueue.size)
    }

    @ReactMethod
    fun isBluetoothEnabled(promise: Promise) {
        promise.resolve(bluetoothAdapter?.isEnabled == true)
    }

    @ReactMethod
    fun requestEnableBluetooth(promise: Promise) {
        try {
            if (bluetoothAdapter?.isEnabled != true) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                reactApplicationContext.startActivity(intent)
                promise.resolve(true)
            } else {
                promise.resolve(true)
            }
        } catch (e: Exception) {
            promise.reject("BT_ENABLE_ERROR", e.message)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
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

            if (characteristic.uuid == CHAR_UUID || characteristic.uuid.toString().contains("ff02", ignoreCase = true)) {
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }

                    val reassembled: ByteArray? = try {
                        val chunk = deserializeChunk(value)
                        reassemblyBuffer.addChunk(chunk)
                    } catch (e: Exception) {
                        // Direct single-packet fallback
                        value
                    }

                    if (reassembled != null) {
                        val rawString = String(reassembled, Charsets.UTF_8)
                        Log.i("BLEMeshModule", "Received complete payload: $rawString")

                        val livePeerMac = device.address
                        if (isValidTargetMac(livePeerMac)) {
                            discoveredPeers[livePeerMac] = livePeerMac
                            mainHandler.postDelayed({ triggerFifoDrain() }, 300)
                        }

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
                        } catch (e: Exception) {}

                        val senderNode = if (sender.isNotEmpty()) sender else device.address
                        if (senderNode.startsWith("NODE_") && isValidTargetMac(device.address)) {
                            nodeToMacMap[senderNode] = device.address
                        }

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
                                    put("sender", getLocalNodeId())
                                    put("keys", myKeys)
                                }.toString()
                                mainHandler.postDelayed({
                                    sendMessageDirect(device.address, keyRespJson, null)
                                }, 400)
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

                            val myMac = getLocalNodeId()
                            val isForMe = dest.isEmpty() || dest.equals(myMac, ignoreCase = true)

                            Log.i("BLEMeshModule", "Message received on GATT Server. isForMe=$isForMe, dest=$dest, myMac=$myMac")

                            if (isForMe) {
                                var isAck = false
                                try {
                                    val prefs = reactApplicationContext.getSharedPreferences("pqc_crypto_prefs", Context.MODE_PRIVATE)
                                    val masterKeyB64 = prefs.getString("session_master_key", null)
                                    if (!masterKeyB64.isNullOrEmpty()) {
                                        val masterKeyBytes = android.util.Base64.decode(masterKeyB64, android.util.Base64.DEFAULT)
                                        val bgSession = ChatSession(masterKeyBytes)
                                        val payloadBytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                                        val plaintext = bgSession.decryptMessage(payloadBytes)
                                        if (plaintext.startsWith("ACK:")) {
                                            isAck = true
                                            val ackedMsgId = plaintext.substring(4)
                                            fifoDb.markDelivered(ackedMsgId)
                                            Log.i("BLEMeshModule", "Received ACK for $ackedMsgId, purged from FIFO database")
                                        }
                                    }
                                } catch (e: Exception) {}

                                val map = Arguments.createMap()
                                map.putString("senderAddress", senderNode)
                                map.putString("payload", payload)
                                map.putString("msgId", msgId)
                                sendEvent("onMessageReceived", map)

                                if (!isAppInForeground() && !isAck) {
                                    persistPendingMessage(senderNode, payload)
                                    // Send background ACK natively
                                    try {
                                        val prefs = reactApplicationContext.getSharedPreferences("pqc_crypto_prefs", Context.MODE_PRIVATE)
                                        val masterKeyB64 = prefs.getString("session_master_key", null)
                                        if (!masterKeyB64.isNullOrEmpty()) {
                                            val masterKeyBytes = android.util.Base64.decode(masterKeyB64, android.util.Base64.DEFAULT)
                                            val bgSession = ChatSession(masterKeyBytes)
                                            val ackPlaintext = "ACK:$msgId"
                                            val ackCiphertextBytes = bgSession.encryptMessage(ackPlaintext)
                                            val ackCiphertextBase64 = android.util.Base64.encodeToString(ackCiphertextBytes, android.util.Base64.NO_WRAP)

                                            val ackEnvelope = JSONObject().apply {
                                                put("type", "MSG")
                                                put("dest", senderNode)
                                                put("sender", getLocalNodeId())
                                                put("msgId", "ACK_$msgId")
                                                put("ttl", 5)
                                                put("payload", ackCiphertextBase64)
                                            }.toString()

                                            mainHandler.postDelayed({
                                                sendMessageDirect(senderNode, ackEnvelope, null)
                                            }, 800)
                                        }
                                    } catch (e: Exception) {}
                                }

                                if (!isAck) {
                                    triggerMessageAudioNotification(senderNode)
                                }
                            } else if (ttl > 1) {
                                val newTtl = ttl - 1
                                val packet = RelayPacket(dest, senderNode, msgId, newTtl, payload)
                                relayQueue.add(packet)

                                val relayMap = Arguments.createMap()
                                relayMap.putString("senderAddress", senderNode)
                                relayMap.putString("destAddress", dest)
                                relayMap.putInt("ttl", newTtl)
                                sendEvent("onMessageRelayed", relayMap)

                                triggerFifoDrain()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BLEMeshModule", "Error processing GATT write: ${e.message}")
                }
            }
        }
    }

    private fun ensureGattServerOpen() {
        if (gattServer == null) {
            try {
                gattServer = bluetoothManager.openGattServer(reactApplicationContext, serverCallback)
                if (gattServer != null) {
                    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    val characteristic = BluetoothGattCharacteristic(
                        CHAR_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ or
                                BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                    )
                    service.addCharacteristic(characteristic)
                    gattServer?.addService(service)
                    Log.i("BLEMeshModule", "GATT Server started and service added")
                }
            } catch (e: Exception) {
                Log.e("BLEMeshModule", "Failed to start GATT server: ${e.message}")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.i("BLEMeshModule", "Advertising onStartSuccess")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BLEMeshModule", "Advertising onStartFailure errorCode=$errorCode")
        }
    }

    @ReactMethod
    fun startAdvertising(promise: Promise? = null) {
        if (startAdvertisingInternal()) {
            promise?.resolve("Advertising Started")
        } else {
            promise?.reject("BLE_UNAVAILABLE", "Bluetooth LE Advertiser not available")
        }
    }

    private fun startAdvertisingInternal(): Boolean {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return false
        ensureGattServerOpen()

        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {}

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .addServiceData(parcelUuid, getLocalNodeId().toByteArray(Charsets.UTF_8))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        return true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val address = device.address

            val serviceData = result.scanRecord?.getServiceData(parcelUuid)
            val advertisedNode = if (serviceData != null) String(serviceData, Charsets.UTF_8) else null
            val name = advertisedNode ?: device.name ?: result.scanRecord?.deviceName ?: "PQC Node (${address.takeLast(5)})"

            if (name == getLocalNodeId() || (advertisedNode != null && advertisedNode == getLocalNodeId())) {
                return // Ignore our own beacon
            }

            val isNew = !discoveredPeers.containsKey(address)
            discoveredPeers[address] = name
            if (advertisedNode != null && advertisedNode.startsWith("NODE_")) {
                nodeToMacMap[advertisedNode] = address
            }

            if (isNew) {
                Log.i("BLEMeshModule", "Discovered peer: $name at $address")
                val map = Arguments.createMap()
                map.putString("address", address)
                map.putString("name", name)
                map.putInt("rssi", result.rssi)
                sendEvent("onPeerDiscovered", map)
                triggerFifoDrain()
            }
        }
    }

    @ReactMethod
    fun startPeerDiscovery(promise: Promise? = null) {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            promise?.reject("SCANNER_UNAVAILABLE", "Bluetooth LE Scanner unavailable")
            return
        }

        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {}

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(null, settings, scanCallback)
            promise?.resolve("BLE Scan Started")
        } catch (e: Exception) {
            promise?.reject("SCAN_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopPeerDiscovery() {
        bleScanner?.stopScan(scanCallback)
    }

    @ReactMethod
    fun startForegroundService(promise: Promise) {
        try {
            BLEMeshService.start(reactApplicationContext)
            promise.resolve("FOREGROUND_SERVICE_STARTED")
        } catch (e: Exception) {
            promise.reject("FG_SERVICE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopForegroundService(promise: Promise) {
        try {
            BLEMeshService.stop(reactApplicationContext)
            promise.resolve("FOREGROUND_SERVICE_STOPPED")
        } catch (e: Exception) {
            promise.reject("FG_SERVICE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopAdvertising() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        closeAllConnections()
        gattServer?.close()
    }

    private fun closeAllConnections() {
        for ((addr, gatt) in connectionPool) {
            try {
                gatt.close()
            } catch (e: Exception) {}
        }
        connectionPool.clear()
        connectionStateMap.clear()
    }

    /**
     * Resolves a target node ID or alias into a valid, active Bluetooth MAC address.
     */
    private fun resolveTargetMac(target: String): String? {
        if (isValidTargetMac(target)) return target

        nodeToMacMap[target]?.let { if (isValidTargetMac(it)) return it }

        discoveredPeers.entries.firstOrNull { isPeerMatch(it.value, target) || isPeerMatch(it.key, target) }?.key?.let {
            if (isValidTargetMac(it)) return it
        }

        return discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
    }

    private fun isPeerMatch(scanName: String?, targetAddress: String): Boolean {
        if (scanName.isNullOrEmpty()) return false
        if (scanName.equals(targetAddress, ignoreCase = true)) return true
        val cleanScan = scanName.replace("NODE_", "").replace("PQC Node", "").replace(" ", "").replace("_", "").lowercase()
        val cleanTarget = targetAddress.replace("NODE_", "").replace("PQC Node", "").replace(" ", "").replace("_", "").lowercase()
        if (cleanScan.isEmpty() || cleanTarget.isEmpty()) return false
        return cleanScan.contains(cleanTarget) || cleanTarget.contains(cleanScan)
    }

    /**
     * Dedicated background FIFO worker: Drains relay queue and on-disk FIFO outbox in strict order.
     */
    private fun triggerFifoDrain() {
        if (!isDrainingFifo.compareAndSet(false, true)) return

        Thread {
            try {
                // 1. Drain Relay Queue
                val relayList = synchronized(relayQueue) { relayQueue.toList() }
                for (packet in relayList) {
                    val targetMac = resolveTargetMac(packet.dest)
                    if (targetMac != null) {
                        val envelope = if (packet.payload.startsWith("{") && packet.payload.contains("\"type\"")) {
                            packet.payload
                        } else {
                            JSONObject().apply {
                                put("type", "MSG")
                                put("dest", packet.dest)
                                put("sender", packet.sender)
                                put("msgId", packet.msgId)
                                put("ttl", packet.ttl)
                                put("payload", packet.payload)
                            }.toString()
                        }
                        val latch = java.util.concurrent.CountDownLatch(1)
                        sendMessageDirect(targetMac, envelope) { delivered ->
                            if (delivered) {
                                synchronized(relayQueue) { relayQueue.removeAll { it.msgId == packet.msgId } }
                            }
                            latch.countDown()
                        }
                        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }

                // 2. Drain On-Disk FIFO Outbox (Strict First-In-First-Out)
                fifoDb.cleanupOldMessages()
                var pending = fifoDb.peekOldestPending()
                while (pending != null) {
                    val targetMac = resolveTargetMac(pending.dest)
                    if (targetMac == null) {
                        break // Target is currently offline, wait for scan detection
                    }

                    val envelope = JSONObject().apply {
                        put("type", "MSG")
                        put("dest", pending.dest)
                        put("sender", pending.sender)
                        put("msgId", pending.msgId)
                        put("ttl", pending.ttl)
                        put("payload", pending.payload)
                    }.toString()

                    fifoDb.markInFlight(pending.msgId)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var sendSuccess = false

                    sendMessageDirect(targetMac, envelope) { delivered ->
                        sendSuccess = delivered
                        if (delivered) {
                            fifoDb.markDelivered(pending!!.msgId)
                        } else {
                            fifoDb.resetInFlightToPending()
                        }
                        latch.countDown()
                    }

                    latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                    if (!sendSuccess) {
                        break // Connection broke or peer went out of range, halt until reconnected
                    }

                    pending = fifoDb.peekOldestPending()
                }
            } catch (e: Exception) {
                Log.e("BLEMeshModule", "FIFO Drain error", e)
            } finally {
                isDrainingFifo.set(false)
            }
        }.start()
    }

    /**
     * Persistent Connection Fast-Path: Writes directly if open, connects and maintains if new.
     */
    private fun sendMessageDirect(targetMac: String, messagePayload: String, onResult: ((Boolean) -> Unit)? = null) {
        val existingGatt = connectionPool[targetMac]
        val isConnected = connectionStateMap[targetMac] == BluetoothProfile.STATE_CONNECTED

        if (existingGatt != null && isConnected) {
            // Instant Fast-Path (0ms connection overhead)
            writePayloadToGatt(existingGatt, targetMac, messagePayload, onResult)
            return
        }

        // Establish or re-attach persistent connection
        val device = bluetoothAdapter?.getRemoteDevice(targetMac)
        if (device == null) {
            onResult?.invoke(false)
            return
        }

        var resultCalled = false
        fun safeResult(success: Boolean) {
            if (!resultCalled) {
                resultCalled = true
                onResult?.invoke(success)
            }
        }

        val gattCallback = object : BluetoothGattCallback() {
            private var mtuOrDiscoveryStarted = false

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                connectionStateMap[targetMac] = newState
                Log.i("BLEMeshModule", "Persistent GATT [$targetMac] state=$newState, status=$status")

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionPool[targetMac] = gatt
                    mtuOrDiscoveryStarted = false
                    mainHandler.postDelayed({
                        try {
                            gatt.requestMtu(517)
                        } catch (e: Exception) {
                            gatt.discoverServices()
                        }
                    }, 50)
                    // Fallback to discoverServices if MTU doesn't callback within 350ms
                    mainHandler.postDelayed({
                        if (!mtuOrDiscoveryStarted) {
                            mtuOrDiscoveryStarted = true
                            try { gatt.discoverServices() } catch (e: Exception) {}
                        }
                    }, 350)
                } else {
                    connectionPool.remove(targetMac)
                    connectionStateMap.remove(targetMac)
                    try { gatt.close() } catch (e: Exception) {}
                    safeResult(false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i("BLEMeshModule", "MTU changed to $mtu, status=$status for $targetMac")
                mtuOrDiscoveryStarted = true
                mainHandler.postDelayed({
                    try { gatt.discoverServices() } catch (e: Exception) {}
                }, 50)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.i("BLEMeshModule", "Services discovered on $targetMac, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectionPool[targetMac] = gatt
                    connectionStateMap[targetMac] = BluetoothProfile.STATE_CONNECTED
                    writePayloadToGatt(gatt, targetMac, messagePayload) { delivered ->
                        safeResult(delivered)
                    }
                } else {
                    safeResult(false)
                }
            }
        }

        Log.i("BLEMeshModule", "Opening persistent GATT to $targetMac (direct connection)...")
        mainHandler.post {
            val gatt = device.connectGatt(reactApplicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) {
                connectionPool[targetMac] = gatt
            }
        }
    }

    private fun writePayloadToGatt(gatt: BluetoothGatt, targetMac: String, payload: String, onResult: ((Boolean) -> Unit)? = null) {
        val service = gatt.services?.firstOrNull {
            it.uuid == SERVICE_UUID || it.uuid.toString().contains("ff01", ignoreCase = true)
        } ?: gatt.getService(SERVICE_UUID)

        val characteristic = service?.characteristics?.firstOrNull {
            it.uuid == CHAR_UUID || it.uuid.toString().contains("ff02", ignoreCase = true)
        } ?: service?.getCharacteristic(CHAR_UUID)

        if (service == null || characteristic == null) {
            Log.w("BLEMeshModule", "Service or Characteristic not found on $targetMac, discovering services...")
            gatt.discoverServices()
            onResult?.invoke(false)
            return
        }

        val rawBytes = payload.toByteArray(Charsets.UTF_8)
        val chunks = if (rawBytes.size <= 480) {
            listOf(rawBytes)
        } else {
            chunkingEngine.splitMessage(rawBytes, 5.toUByte()).map { serializeChunk(it) }
        }

        var chunkIdx = 0
        fun writeNext() {
            if (chunkIdx < chunks.size) {
                val chunkData = chunks[chunkIdx]
                characteristic.value = chunkData
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val ok = gatt.writeCharacteristic(characteristic)
                if (ok) {
                    chunkIdx++
                    mainHandler.postDelayed({ writeNext() }, 20)
                } else {
                    onResult?.invoke(false)
                }
            } else {
                Log.i("BLEMeshModule", "Delivered ${chunks.size} chunks to $targetMac over persistent link!")
                try {
                    val json = JSONObject(payload)
                    val msgId = json.optString("msgId", "")
                    if (msgId.isNotEmpty()) {
                        val map = Arguments.createMap()
                        map.putString("msgId", msgId)
                        map.putString("dest", targetMac)
                        sendEvent("onMessageDelivered", map)
                    }
                } catch (e: Exception) {}
                onResult?.invoke(true)
            }
        }

        writeNext()
    }

    @ReactMethod
    fun sendMessageToDevice(deviceAddress: String, message: String, senderMacAddress: String, promise: Promise) {
        val msgId = UUID.randomUUID().toString()
        val sender = if (senderMacAddress.isNotEmpty()) senderMacAddress else getLocalNodeId()

        // Enqueue atomically to on-disk FIFO database
        fifoDb.enqueue(deviceAddress, sender, msgId, 5, message)
        promise.resolve(msgId)

        // Kick the FIFO drain worker immediately
        triggerFifoDrain()
    }

    @ReactMethod
    fun requestPqcKeysOverBle(deviceAddress: String, senderMacAddress: String, promise: Promise) {
        val myKeys = CryptoModule.identityKeys?.exportPublicKeysBase64() ?: ""
        val reqJson = JSONObject().apply {
            put("type", "KEY_REQ")
            put("sender", if (senderMacAddress.isNotEmpty()) senderMacAddress else getLocalNodeId())
            put("keys", myKeys)
        }.toString()

        val targetMac = resolveTargetMac(deviceAddress) ?: deviceAddress
        sendMessageDirect(targetMac, reqJson) { success ->
            if (success) {
                promise.resolve("KEY_REQ_SENT")
            } else {
                promise.reject("KEY_REQ_FAILED", "Could not connect to peer")
            }
        }
    }

    @ReactMethod
    fun deleteOutboxMessage(msgId: String, promise: Promise? = null) {
        fifoDb.markDelivered(msgId)
        promise?.resolve(true)
    }

    @ReactMethod
    fun resetMeshState(promise: Promise? = null) {
        try {
            seenMsgIds.clear()
            relayQueue.clear()
            discoveredPeers.clear()
            nodeToMacMap.clear()
            fifoDb.clearAll()
            closeAllConnections()

            try {
                bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                gattServer?.close()
                gattServer = null
            } catch (e: Exception) {}

            mainHandler.postDelayed({
                startAdvertisingInternal()
                startPeerDiscovery()
                promise?.resolve("Mesh state and on-disk FIFO outbox wiped successfully")
            }, 500)
        } catch (e: Exception) {
            promise?.reject("RESET_ERROR", e.message)
        }
    }
}
