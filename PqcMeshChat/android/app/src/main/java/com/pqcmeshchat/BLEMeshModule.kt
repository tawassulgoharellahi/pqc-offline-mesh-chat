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
import org.json.JSONObject
import uniffi.rust_core.*
import java.util.UUID

class BLEMeshModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

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

    private data class RelayPacket(
        val dest: String,
        val sender: String,
        val msgId: String,
        val ttl: Int,
        val payload: String
    )

    private data class OutboxPacket(
        val dest: String,
        val sender: String,
        val msgId: String,
        val ttl: Int,
        val payload: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val seenMsgIds = mutableSetOf<String>()
    private val relayQueue = mutableListOf<RelayPacket>()
    private val storeAndForwardOutbox = mutableListOf<OutboxPacket>()
    private val discoveredPeers = mutableMapOf<String, String>()

    private val outboxFlushRunnable = object : Runnable {
        override fun run() {
            try {
                if (storeAndForwardOutbox.isNotEmpty()) {
                    attemptForwardQueuedMessages()
                }
            } catch (e: Exception) {
                Log.w("BLEMeshModule", "Periodic outbox flush error: ${e.message}")
            }
            mainHandler.postDelayed(this, 4000)
        }
    }

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF) {
                    Log.i("BLEMeshModule", "Bluetooth toggled OFF: Closing GATT server...")
                    try {
                        gattServer?.close()
                    } catch (e: Exception) {}
                    gattServer = null
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i("BLEMeshModule", "Bluetooth toggled ON: Restarting advertising, scanning, and outbox flush...")
                    mainHandler.postDelayed({
                        try {
                            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                        } catch (e: Exception) {}

                        val settings = AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(true)
                            .setTimeout(0)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                            .build()
                        val data = AdvertiseData.Builder()
                            .setIncludeDeviceName(true)
                            .addServiceUuid(parcelUuid)
                            .build()
                        try {
                            bluetoothAdapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
                        } catch (e: Exception) {
                            Log.w("BLEMeshModule", "Error restarting advertiser: ${e.message}")
                        }

                        ensureGattServerOpen()
                        startPeerDiscovery()
                        attemptForwardQueuedMessages()
                    }, 1500)
                }
            }
        }
    }

    init {
        createNotificationChannel()
        try {
            val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            reactApplicationContext.registerReceiver(bluetoothStateReceiver, filter)
        } catch (e: Exception) {
            Log.w("BLEMeshModule", "Error registering bluetoothStateReceiver: ${e.message}")
        }
        mainHandler.postDelayed(outboxFlushRunnable, 4000)
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
                ringtone.volume = 0.35f // Dim / lower volume for open app in foreground
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

    private fun triggerMessageAudioNotification(sender: String) {
        mainHandler.post {
            if (isAppInForeground()) {
                Log.i("BLEMeshModule", "App in foreground: Playing soft chime tone")
                playInAppChimeSound()
            } else {
                Log.i("BLEMeshModule", "App in background: Showing system status bar notification with ringtone")
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

    @ReactMethod
    fun getMacAddress(promise: Promise) {
        try {
            val mac = bluetoothAdapter?.address
            if (isValidTargetMac(mac)) {
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
                        
                        val livePeerMac = device.address
                        if (isValidTargetMac(livePeerMac)) {
                            mainHandler.postDelayed({ attemptForwardQueuedMessages() }, 800)
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
                        } catch (e: Exception) {
                            // Raw payload fallback
                        }

                        val isSenderMacValid = sender.isNotEmpty() && sender != "02:00:00:00:00:00"
                        val bestDiscoveredMac = discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
                        val senderNode = if (isSenderMacValid) sender else (bestDiscoveredMac ?: device.address)

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
                                mainHandler.postDelayed({
                                    sendMessageToDeviceInternal(device.address, keyRespJson, null)
                                }, 700)
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
                                triggerMessageAudioNotification(senderNode)
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

    private fun ensureGattServerOpen() {
        if (gattServer == null) {
            try {
                gattServer = bluetoothManager.openGattServer(reactApplicationContext, serverCallback)
                if (gattServer != null) {
                    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    val characteristic = BluetoothGattCharacteristic(
                        CHAR_UUID,
                        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE
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

    @ReactMethod
    fun startAdvertising(promise: Promise) {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            promise.reject("BLE_UNAVAILABLE", "Bluetooth LE Advertiser not available")
            return
        }

        // 1. Start GATT Server
        ensureGattServerOpen()

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
    fun startPeerDiscovery(promise: Promise? = null) {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            promise?.reject("SCANNER_UNAVAILABLE", "Bluetooth LE Scanner unavailable")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(parcelUuid)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
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
    fun requestPqcKeysOverBle(deviceAddress: String, senderMacAddress: String, promise: Promise) {
        val myKeys = CryptoModule.identityKeys?.exportPublicKeysBase64() ?: ""
        val reqJson = JSONObject().apply {
            put("type", "KEY_REQ")
            put("sender", if (senderMacAddress.isNotEmpty()) senderMacAddress else (bluetoothAdapter?.address ?: "02:00:00:00:00:00"))
            put("keys", myKeys)
        }.toString()
        sendMessageToDeviceInternal(deviceAddress, reqJson, promise)
    }

    @ReactMethod
    fun stopAdvertising() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
    }

    private fun queueStoreAndForwardPacket(packet: OutboxPacket) {
        synchronized(storeAndForwardOutbox) {
            val now = System.currentTimeMillis()
            storeAndForwardOutbox.removeAll { now - it.timestamp > 86400000L }
            if (storeAndForwardOutbox.none { it.msgId == packet.msgId }) {
                storeAndForwardOutbox.add(packet)
                Log.i("BLEMeshModule", "Queued packet ${packet.msgId} for Store-and-Forward (Outbox size: ${storeAndForwardOutbox.size})")
            }
        }
    }

    @Volatile
    private var isFlushingOutbox = false

    private fun attemptForwardQueuedMessages() {
        if (storeAndForwardOutbox.isEmpty()) return
        if (isFlushingOutbox) return

        isFlushingOutbox = true
        Thread {
            try {
                // 1. Process Relay Queue
                val relayList = synchronized(relayQueue) { relayQueue.toList() }
                for (packet in relayList) {
                    val targetMac = if (isValidTargetMac(packet.dest)) {
                        packet.dest
                    } else {
                        discoveredPeers.entries.firstOrNull { it.value.equals(packet.dest, ignoreCase = true) }?.key
                        ?: discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
                    }
                    if (targetMac != null && isValidTargetMac(targetMac)) {
                        val envelope = JSONObject().apply {
                            put("type", "MSG")
                            put("dest", packet.dest)
                            put("sender", packet.sender)
                            put("msgId", packet.msgId)
                            put("ttl", packet.ttl)
                            put("payload", packet.payload)
                        }.toString()

                        val latch = java.util.concurrent.CountDownLatch(1)
                        sendMessageToDeviceInternal(targetMac, envelope, null) { isDelivered ->
                            if (isDelivered) {
                                synchronized(relayQueue) {
                                    relayQueue.removeAll { it.msgId == packet.msgId }
                                }
                            }
                            latch.countDown()
                        }
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }

                // 2. Process Store-and-Forward Outbox
                val now = System.currentTimeMillis()
                val outboxList = synchronized(storeAndForwardOutbox) {
                    storeAndForwardOutbox.removeAll { now - it.timestamp > 86400000L }
                    storeAndForwardOutbox.toList()
                }
                if (outboxList.isEmpty()) return@Thread

                for (packet in outboxList) {
                    val targetMac = if (isValidTargetMac(packet.dest) && discoveredPeers.containsKey(packet.dest)) {
                        packet.dest
                    } else {
                        discoveredPeers.entries.firstOrNull { isPeerMatch(it.value, packet.dest) || isPeerMatch(it.key, packet.dest) }?.key
                        ?: discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
                        ?: if (isValidTargetMac(packet.dest)) packet.dest else null
                    }

                    if (targetMac != null && isValidTargetMac(targetMac)) {
                        Log.i("BLEMeshModule", "Auto-flushing Store-and-Forward packet ${packet.msgId} to target $targetMac")
                        val envelope = JSONObject().apply {
                            put("type", "MSG")
                            put("dest", packet.dest)
                            put("sender", packet.sender)
                            put("msgId", packet.msgId)
                            put("ttl", packet.ttl)
                            put("payload", packet.payload)
                        }.toString()

                        val latch = java.util.concurrent.CountDownLatch(1)
                        sendMessageToDeviceInternal(targetMac, envelope, null) { isDelivered ->
                            if (isDelivered) {
                                synchronized(storeAndForwardOutbox) {
                                    storeAndForwardOutbox.removeAll { it.msgId == packet.msgId }
                                }
                            }
                            latch.countDown()
                        }
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            } finally {
                isFlushingOutbox = false
            }
        }.start()
    }

    private fun isPeerMatch(scanName: String?, targetAddress: String): Boolean {
        if (scanName.isNullOrEmpty()) return false
        if (scanName.equals(targetAddress, ignoreCase = true)) return true
        val cleanScan = scanName.replace("NODE_", "").replace("PQC Node", "").replace(" ", "").replace("_", "").lowercase()
val cleanTarget = targetAddress.replace("NODE_", "").replace("PQC Node", "").replace(" ", "").replace("_", "").lowercase()
        if (cleanScan.isEmpty() || cleanTarget.isEmpty()) return false
        return cleanScan.contains(cleanTarget) || cleanTarget.contains(cleanScan)
    }

    private class SafePromise(private val promise: Promise?) {
        private val isResolved = java.util.concurrent.atomic.AtomicBoolean(false)

        fun resolve(value: Any?) {
            if (isResolved.compareAndSet(false, true)) {
                try { promise?.resolve(value) } catch (e: Exception) { Log.e("BLEMeshModule", "SafePromise resolve error: ${e.message}") }
            }
        }

        fun reject(code: String, message: String) {
            if (isResolved.compareAndSet(false, true)) {
                try { promise?.reject(code, message) } catch (e: Exception) { Log.e("BLEMeshModule", "SafePromise reject error: ${e.message}") }
            }
        }

        fun reject(code: String, throwable: Throwable) {
            if (isResolved.compareAndSet(false, true)) {
                try { promise?.reject(code, throwable) } catch (e: Exception) { Log.e("BLEMeshModule", "SafePromise reject error: ${e.message}") }
            }
        }
    }

    @Volatile
    private var isGattBusy = false
    @Volatile
    private var gattBusyStartTime = 0L

    private val gattWatchdogRunnable = Runnable {
        Log.w("BLEMeshModule", "GATT Watchdog timeout triggered! Force releasing lock.")
        releaseGattLock()
    }

    private fun releaseGattLock() {
        mainHandler.removeCallbacks(gattWatchdogRunnable)
        synchronized(this) {
            isGattBusy = false
            gattBusyStartTime = 0L
        }
        mainHandler.postDelayed({
            if (storeAndForwardOutbox.isNotEmpty()) {
                attemptForwardQueuedMessages()
            }
        }, 400)
    }

    private fun sendMessageToDeviceInternal(deviceAddress: String, message: String, rawPromise: Promise?, onComplete: ((Boolean) -> Unit)? = null) {
        val safePromise = SafePromise(rawPromise)
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (isGattBusy && (now - gattBusyStartTime > 10000L)) {
                Log.w("BLEMeshModule", "GATT lock stale (>10s), force resetting lock")
                isGattBusy = false
            }

            if (isGattBusy) {
                Log.i("BLEMeshModule", "GATT busy, queuing packet for Store-and-Forward")
                try {
                    val json = JSONObject(message)
                    if (json.optString("type") == "MSG") {
                        val outbox = OutboxPacket(
                            dest = json.optString("dest", deviceAddress),
                            sender = json.optString("sender", "02:00:00:00:00:00"),
                            msgId = json.optString("msgId", UUID.randomUUID().toString()),
                            ttl = json.optInt("ttl", 5),
                            payload = json.optString("payload", "")
                        )
                        queueStoreAndForwardPacket(outbox)
                        safePromise.resolve("QUEUED_IN_OUTBOX")
                    } else {
                        safePromise.reject("GATT_BUSY", "GATT is currently busy")
                    }
                } catch (e: Exception) {
                    safePromise.reject("GATT_BUSY", "GATT is currently busy")
                }
                onComplete?.invoke(false)
                return
            }
            isGattBusy = true
            gattBusyStartTime = System.currentTimeMillis()
            mainHandler.removeCallbacks(gattWatchdogRunnable)
            mainHandler.postDelayed(gattWatchdogRunnable, 10000L)
        }

        Thread {
            try {
                stopPeerDiscovery()
            } catch (e: Exception) {
                Log.e("BLEMeshModule", "Error stopping discovery: ${e.message}")
            }

            var targetMac: String? = if (isValidTargetMac(deviceAddress) && discoveredPeers.containsKey(deviceAddress)) {
                deviceAddress
            } else {
                discoveredPeers.entries.firstOrNull { isPeerMatch(it.value, deviceAddress) || isPeerMatch(it.key, deviceAddress) }?.key
                ?: discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
            }

            if (targetMac == null || !isValidTargetMac(targetMac)) {
                try {
                    val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
                    targetMac = connectedDevices.firstOrNull { isValidTargetMac(it.address) }?.address
                } catch (e: Exception) {
                    Log.w("BLEMeshModule", "Error getting connected GATT devices: ${e.message}")
                }
            }

            if (targetMac == null || !isValidTargetMac(targetMac)) {
                try {
                    val bonded = bluetoothAdapter?.bondedDevices
                    targetMac = bonded?.firstOrNull { isValidTargetMac(it.address) }?.address
                } catch (e: Exception) {
                    Log.w("BLEMeshModule", "Error getting bonded devices: ${e.message}")
                }
            }

            if (targetMac.isNullOrEmpty() || !isValidTargetMac(targetMac)) {
                try {
                    val scanner = bluetoothAdapter?.bluetoothLeScanner
                    if (scanner != null) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        val quickCallback = object : ScanCallback() {
                            override fun onScanResult(callbackType: Int, result: ScanResult) {
                                val addr = result.device.address
                                val name = result.device.name ?: ""
                                if (isValidTargetMac(addr)) {
                                    discoveredPeers[addr] = if (name.isNotEmpty()) name else "PQC Node (${addr.takeLast(5)})"
                                    targetMac = addr
                                    latch.countDown()
                                }
                            }
                        }
                        scanner.startScan(quickCallback)
                        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        try { scanner.stopScan(quickCallback) } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w("BLEMeshModule", "Fast scan exception: ${e.message}")
                }
            }

            if (targetMac.isNullOrEmpty() && isValidTargetMac(deviceAddress)) {
                targetMac = deviceAddress
            }

            if (targetMac.isNullOrEmpty() || !isValidTargetMac(targetMac)) {
                try {
                    val json = JSONObject(message)
                    if (json.optString("type") == "MSG") {
                        val outbox = OutboxPacket(
                            dest = json.optString("dest", deviceAddress),
                            sender = json.optString("sender", "02:00:00:00:00:00"),
                            msgId = json.optString("msgId", UUID.randomUUID().toString()),
                            ttl = json.optInt("ttl", 5),
                            payload = json.optString("payload", "")
                        )
                        queueStoreAndForwardPacket(outbox)
                        safePromise.resolve("QUEUED_IN_OUTBOX")
                        releaseGattLock()
                        onComplete?.invoke(false)
                        return@Thread
                    }
                } catch (e: Exception) {}

                val fallbackOutbox = OutboxPacket(
                    dest = deviceAddress,
                    sender = "02:00:00:00:00:00",
                    msgId = UUID.randomUUID().toString(),
                    ttl = 5,
                    payload = message
                )
                queueStoreAndForwardPacket(fallbackOutbox)
                safePromise.resolve("QUEUED_IN_OUTBOX")
                releaseGattLock()
                onComplete?.invoke(false)
                return@Thread
            }

            val device = bluetoothAdapter?.getRemoteDevice(targetMac)
            if (device == null) {
                val fallbackOutbox = OutboxPacket(
                    dest = deviceAddress,
                    sender = "02:00:00:00:00:00",
                    msgId = UUID.randomUUID().toString(),
                    ttl = 5,
                    payload = message
                )
                queueStoreAndForwardPacket(fallbackOutbox)
                safePromise.resolve("QUEUED_IN_OUTBOX")
                releaseGattLock()
                onComplete?.invoke(false)
                return@Thread
            }

            val chunks = chunkingEngine.splitMessage(message.toByteArray(), 5.toUByte())
            if (chunks.isEmpty()) {
                safePromise.reject("CHUNKING_FAILED", "Failed to chunk message")
                releaseGattLock()
                onComplete?.invoke(false)
                return@Thread
            }

            var currentChunkIndex = 0
            var servicesDiscovered = false
            var writingStarted = false
            var retryCount = 0

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.i("BLEMeshModule", "onConnectionStateChange: status=$status, newState=$newState")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLEMeshModule", "GATT connection status error: $status, closing...")
                        gatt.close()
                        if (retryCount < 2) {
                            retryCount++
                            mainHandler.postDelayed({
                                val oldMac = targetMac
                                discoveredPeers.remove(oldMac)
                                val scanner = bluetoothAdapter?.bluetoothLeScanner
                                var foundNewMac: String? = null
                                if (scanner != null) {
                                    val scanCb = object : ScanCallback() {
                                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                                            val mac = result.device.address
                                            val name = result.device.name ?: result.scanRecord?.deviceName
                                            discoveredPeers[mac] = name ?: "Unknown"
                                            if (isPeerMatch(name, deviceAddress) || isPeerMatch(mac, deviceAddress)) {
                                                foundNewMac = mac
                                            }
                                        }
                                    }
                                    try {
                                        val filter = ScanFilter.Builder().setServiceUuid(parcelUuid).build()
                                        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                                        scanner.startScan(listOf(filter), settings, scanCb)
                                    } catch (e: Exception) {
                                        Log.w("BLEMeshModule", "Retry scan exception: ${e.message}")
                                    }
                                    mainHandler.postDelayed({
                                        try { scanner.stopScan(scanCb) } catch(e: Exception){}
                                        val newTarget = foundNewMac ?: (
                                            discoveredPeers.entries.firstOrNull { isPeerMatch(it.value, deviceAddress) || isPeerMatch(it.key, deviceAddress) }?.key
                                            ?: discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
                                        )
                                        targetMac = newTarget ?: oldMac
                                        Log.i("BLEMeshModule", "Retrying GATT connection (attempt $retryCount) to $targetMac...")
                                        val retryDevice = bluetoothAdapter?.getRemoteDevice(targetMac)
                                        retryDevice?.connectGatt(reactApplicationContext, false, this, BluetoothDevice.TRANSPORT_LE)
                                    }, 2000)
                                } else {
                                    val newTarget = discoveredPeers.entries.firstOrNull { isPeerMatch(it.value, deviceAddress) || isPeerMatch(it.key, deviceAddress) }?.key
                                        ?: discoveredPeers.keys.firstOrNull { isValidTargetMac(it) }
                                    targetMac = newTarget ?: oldMac
                                    Log.i("BLEMeshModule", "Retrying GATT connection (attempt $retryCount) to $targetMac...")
                                    val retryDevice = bluetoothAdapter?.getRemoteDevice(targetMac)
                                    retryDevice?.connectGatt(reactApplicationContext, false, this, BluetoothDevice.TRANSPORT_LE)
                                }
                            }, 300)
                        } else {
                            handleGattFailure(gatt, message, deviceAddress, safePromise)
                        }
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
                    Log.i("BLEMeshModule", "onServicesDiscovered: status=$status, writingStarted=$writingStarted")
                    servicesDiscovered = true
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (!writingStarted) {
                            writingStarted = true
                            writeNextChunk(gatt)
                        }
                    } else {
                        Log.e("BLEMeshModule", "Service discovery failed with status $status")
                        handleGattFailure(gatt, message, deviceAddress, safePromise)
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
                        handleGattFailure(gatt, message, deviceAddress, safePromise)
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
                            if (!success) {
                                handleGattFailure(gatt, message, deviceAddress, safePromise)
                            }
                        } else {
                            Log.e("BLEMeshModule", "Target GATT Service or Characteristic not found on device!")
                            handleGattFailure(gatt, message, deviceAddress, safePromise)
                        }
                    } else {
                        Log.i("BLEMeshModule", "All ${chunks.size} chunks sent successfully!")
                        try {
                            val json = JSONObject(message)
                            val msgId = json.optString("msgId", "")
                            val dest = json.optString("dest", "")
                            if (msgId.isNotEmpty()) {
                                val map = Arguments.createMap()
                                map.putString("msgId", msgId)
                                map.putString("dest", dest)
                                sendEvent("onMessageDelivered", map)
                            }
                        } catch (e: Exception) {}

                        safePromise.resolve("DELIVERED")
                        mainHandler.postDelayed({
                            try { gatt.disconnect() } catch (e: Exception) {}
                            try { gatt.close() } catch (e: Exception) {}
                            releaseGattLock()
                            onComplete?.invoke(true)
                        }, 500)
                    }
                }
            }
            
            Log.i("BLEMeshModule", "Connecting GATT to device $targetMac via TRANSPORT_LE...")
            mainHandler.postDelayed({
                device.connectGatt(reactApplicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }, 300)
        }.start()
    }

    private fun handleGattFailure(gatt: BluetoothGatt, message: String, deviceAddress: String, safePromise: SafePromise, onComplete: ((Boolean) -> Unit)? = null) {
        try {
            val json = JSONObject(message)
            if (json.optString("type") == "MSG") {
                val outbox = OutboxPacket(
                    dest = json.optString("dest", deviceAddress),
                    sender = json.optString("sender", "02:00:00:00:00:00"),
                    msgId = json.optString("msgId", UUID.randomUUID().toString()),
                    ttl = json.optInt("ttl", 5),
                    payload = json.optString("payload", "")
                )
                queueStoreAndForwardPacket(outbox)
                safePromise.resolve("QUEUED_IN_OUTBOX")
            } else {
                safePromise.reject("GATT_ERROR", "GATT transaction failed")
            }
        } catch (e: Exception) {
            val fallbackOutbox = OutboxPacket(
                dest = deviceAddress,
                sender = "02:00:00:00:00:00",
                msgId = UUID.randomUUID().toString(),
                ttl = 5,
                payload = message
            )
            queueStoreAndForwardPacket(fallbackOutbox)
            safePromise.resolve("QUEUED_IN_OUTBOX")
        }
        try { gatt.disconnect() } catch (e: Exception) {}
        try { gatt.close() } catch (e: Exception) {}
        releaseGattLock()
        onComplete?.invoke(false)
    }

    @ReactMethod
    fun sendMessageToDevice(deviceAddress: String, message: String, senderMacAddress: String, customMsgId: String?, promise: Promise) {
        val envelope = JSONObject().apply {
            put("type", "MSG")
            put("dest", deviceAddress)
            put("sender", if (senderMacAddress.isNotEmpty()) senderMacAddress else (bluetoothAdapter?.address ?: "02:00:00:00:00:00"))
            put("msgId", if (!customMsgId.isNullOrEmpty()) customMsgId else UUID.randomUUID().toString())
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
            attemptForwardQueuedMessages()
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLEMeshModule", "BLE Scan Failed: $errorCode")
        }
    }

    @ReactMethod
    fun getOutboxCount(promise: Promise) {
        synchronized(storeAndForwardOutbox) {
            promise.resolve(storeAndForwardOutbox.size)
        }
    }

    @ReactMethod
    fun resetMeshState(promise: Promise) {
        try {
            seenMsgIds.clear()
            relayQueue.clear()
            synchronized(storeAndForwardOutbox) { storeAndForwardOutbox.clear() }
            discoveredPeers.clear()
            Log.i("BLEMeshModule", "BLE Mesh cache, outbox, and discovered peers cleared")
            promise.resolve("Mesh state reset successfully")
        } catch (e: Exception) {
            promise.reject("RESET_FAILED", e)
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
