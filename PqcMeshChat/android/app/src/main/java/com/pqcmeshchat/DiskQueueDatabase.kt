package com.pqcmeshchat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class OutboxEntry(
    val id: Long,
    val msgId: String,
    val dest: String,
    val sender: String,
    val payload: String,
    val ttl: Int,
    val timestamp: Long,
    val status: String,
    val retryCount: Int
)

data class ChatMessageEntry(
    val id: Long,
    val msgId: String,
    val peerId: String,
    val sender: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
    val timeStr: String,
    val status: String
)

data class ContactEntry(
    val nodeId: String,
    val name: String,
    val publicKeys: String?,
    val sessionMasterKey: String?,
    val targetMac: String?,
    val lastActive: Long,
    val isActive: Boolean
)

/**
 * DiskQueueDatabase: An atomic, crash-resilient on-disk SQLite database.
 * Manages:
 * 1. fifo_outbox: Strict FIFO message transport queue.
 * 2. chat_messages: Persistent conversation history per peer.
 * 3. contacts: Multi-peer contact and session registry.
 */
class DiskQueueDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "pqc_fifo_mesh.db"
        private const val DATABASE_VERSION = 2

        // Table 1: FIFO Outbox
        private const val TABLE_OUTBOX = "fifo_outbox"
        private const val COL_ID = "id"
        private const val COL_MSG_ID = "msg_id"
        private const val COL_DEST = "dest"
        private const val COL_SENDER = "sender"
        private const val COL_PAYLOAD = "payload"
        private const val COL_TTL = "ttl"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_STATUS = "status"
        private const val COL_RETRY_COUNT = "retry_count"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_FLIGHT = "IN_FLIGHT"
        const val STATUS_DELIVERED = "DELIVERED"

        // Table 2: Persistent Chat Messages
        private const val TABLE_CHAT_MESSAGES = "chat_messages"
        private const val COL_CHAT_PEER_ID = "peer_id"
        private const val COL_CHAT_SENDER = "sender"
        private const val COL_CHAT_TEXT = "text"
        private const val COL_CHAT_IS_MINE = "is_mine"
        private const val COL_CHAT_TIME_STR = "time_str"

        // Table 3: Contacts / Sessions
        private const val TABLE_CONTACTS = "contacts"
        private const val COL_CONTACT_NODE_ID = "node_id"
        private const val COL_CONTACT_NAME = "name"
        private const val COL_CONTACT_PUBLIC_KEYS = "public_keys"
        private const val COL_CONTACT_MASTER_KEY = "session_master_key"
        private const val COL_CONTACT_TARGET_MAC = "target_mac"
        private const val COL_CONTACT_LAST_ACTIVE = "last_active"
        private const val COL_CONTACT_IS_ACTIVE = "is_active"

        @Volatile
        private var instance: DiskQueueDatabase? = null

        fun getInstance(context: Context): DiskQueueDatabase {
            return instance ?: synchronized(this) {
                instance ?: DiskQueueDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Create FIFO Outbox
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_OUTBOX (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MSG_ID TEXT UNIQUE NOT NULL,
                $COL_DEST TEXT NOT NULL,
                $COL_SENDER TEXT NOT NULL,
                $COL_PAYLOAD TEXT NOT NULL,
                $COL_TTL INTEGER DEFAULT 5,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_STATUS TEXT DEFAULT '$STATUS_PENDING',
                $COL_RETRY_COUNT INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_id ON $TABLE_OUTBOX ($COL_STATUS, $COL_ID ASC)")

        // 2. Create Persistent Chat Messages Table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CHAT_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MSG_ID TEXT UNIQUE NOT NULL,
                $COL_CHAT_PEER_ID TEXT NOT NULL,
                $COL_CHAT_SENDER TEXT NOT NULL,
                $COL_CHAT_TEXT TEXT NOT NULL,
                $COL_CHAT_IS_MINE INTEGER NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_CHAT_TIME_STR TEXT NOT NULL,
                $COL_STATUS TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_peer ON $TABLE_CHAT_MESSAGES ($COL_CHAT_PEER_ID, $COL_TIMESTAMP ASC)")

        // 3. Create Contacts Table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACTS (
                $COL_CONTACT_NODE_ID TEXT PRIMARY KEY,
                $COL_CONTACT_NAME TEXT NOT NULL,
                $COL_CONTACT_PUBLIC_KEYS TEXT,
                $COL_CONTACT_MASTER_KEY TEXT,
                $COL_CONTACT_TARGET_MAC TEXT,
                $COL_CONTACT_LAST_ACTIVE INTEGER NOT NULL,
                $COL_CONTACT_IS_ACTIVE INTEGER DEFAULT 0
            )
        """.trimIndent())

        Log.i("DiskQueueDatabase", "SQLite database initialized on disk with Outbox, Chat Messages, and Contacts tables")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            onCreate(db)
        }
    }

    // ==========================================
    // 1. FIFO OUTBOX METHODS
    // ==========================================

    @Synchronized
    fun enqueue(dest: String, sender: String, msgId: String, ttl: Int, payload: String): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_MSG_ID, msgId)
                put(COL_DEST, dest)
                put(COL_SENDER, sender)
                put(COL_PAYLOAD, payload)
                put(COL_TTL, ttl)
                put(COL_TIMESTAMP, System.currentTimeMillis())
                put(COL_STATUS, STATUS_PENDING)
                put(COL_RETRY_COUNT, 0)
            }
            val rowId = db.insertWithOnConflict(TABLE_OUTBOX, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.i("DiskQueueDatabase", "Enqueued packet $msgId for $dest (rowId=$rowId)")
            rowId
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error enqueuing packet $msgId", e)
            -1L
        }
    }

    @Synchronized
    fun peekOldestPending(): OutboxEntry? {
        return try {
            val db = readableDatabase
            val query = "SELECT * FROM $TABLE_OUTBOX WHERE $COL_STATUS = '$STATUS_PENDING' ORDER BY $COL_ID ASC LIMIT 1"
            db.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    OutboxEntry(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        msgId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_ID)),
                        dest = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEST)),
                        sender = cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER)),
                        payload = cursor.getString(cursor.getColumnIndexOrThrow(COL_PAYLOAD)),
                        ttl = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TTL)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS)),
                        retryCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_RETRY_COUNT))
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error peeking oldest pending packet", e)
            null
        }
    }

    @Synchronized
    fun markInFlight(msgId: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_IN_FLIGHT)
            }
            db.update(TABLE_OUTBOX, values, "$COL_MSG_ID = ?", arrayOf(msgId))
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error marking in-flight $msgId", e)
        }
    }

    @Synchronized
    fun markDelivered(msgId: String) {
        try {
            val db = writableDatabase
            val deleted = db.delete(TABLE_OUTBOX, "$COL_MSG_ID = ?", arrayOf(msgId))
            Log.i("DiskQueueDatabase", "Marked delivered & purged $msgId from outbox (rows=$deleted)")
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error marking delivered $msgId", e)
        }
    }

    @Synchronized
    fun resetInFlightToPending() {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_PENDING)
            }
            db.update(TABLE_OUTBOX, values, "$COL_STATUS = '$STATUS_IN_FLIGHT'", null)
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error resetting in-flight packets", e)
        }
    }

    @Synchronized
    fun cleanupOldMessages(maxAgeMs: Long = 86400000L) { // 24 Hours
        try {
            val threshold = System.currentTimeMillis() - maxAgeMs
            val db = writableDatabase
            val count = db.delete(TABLE_OUTBOX, "$COL_TIMESTAMP < ?", arrayOf(threshold.toString()))
            if (count > 0) {
                Log.i("DiskQueueDatabase", "Cleaned up $count expired outbox packets from disk")
            }
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error cleaning up expired packets", e)
        }
    }

    @Synchronized
    fun getPendingCount(): Int {
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_OUTBOX WHERE $COL_STATUS = '$STATUS_PENDING'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    // ==========================================
    // 2. PERSISTENT CHAT MESSAGES METHODS
    // ==========================================

    @Synchronized
    fun saveChatMessage(
        msgId: String,
        peerId: String,
        sender: String,
        text: String,
        isMine: Boolean,
        timestamp: Long,
        timeStr: String,
        status: String
    ): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_MSG_ID, msgId)
                put(COL_CHAT_PEER_ID, peerId)
                put(COL_CHAT_SENDER, sender)
                put(COL_CHAT_TEXT, text)
                put(COL_CHAT_IS_MINE, if (isMine) 1 else 0)
                put(COL_TIMESTAMP, timestamp)
                put(COL_CHAT_TIME_STR, timeStr)
                put(COL_STATUS, status)
            }
            db.insertWithOnConflict(TABLE_CHAT_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error saving chat message $msgId", e)
            -1L
        }
    }

    @Synchronized
    fun getChatMessages(peerId: String, limit: Int = 100): List<ChatMessageEntry> {
        val list = mutableListOf<ChatMessageEntry>()
        try {
            val db = readableDatabase
            val query = "SELECT * FROM $TABLE_CHAT_MESSAGES WHERE $COL_CHAT_PEER_ID = ? ORDER BY $COL_TIMESTAMP ASC LIMIT ?"
            db.rawQuery(query, arrayOf(peerId, limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        ChatMessageEntry(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                            msgId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_ID)),
                            peerId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_PEER_ID)),
                            sender = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_SENDER)),
                            text = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_TEXT)),
                            isMine = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CHAT_IS_MINE)) == 1,
                            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                            timeStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_TIME_STR)),
                            status = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error loading chat messages for $peerId", e)
        }
        return list
    }

    @Synchronized
    fun updateChatMessageStatus(msgId: String, status: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_STATUS, status)
            }
            db.update(TABLE_CHAT_MESSAGES, values, "$COL_MSG_ID = ?", arrayOf(msgId))
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error updating chat message status for $msgId", e)
        }
    }

    // ==========================================
    // 3. CONTACTS & SESSIONS METHODS
    // ==========================================

    @Synchronized
    fun saveContact(
        nodeId: String,
        name: String,
        publicKeys: String?,
        sessionMasterKey: String?,
        targetMac: String?,
        isActive: Boolean
    ) {
        try {
            val db = writableDatabase
            if (isActive) {
                // Clear any other active contacts first
                val clearValues = ContentValues().apply { put(COL_CONTACT_IS_ACTIVE, 0) }
                db.update(TABLE_CONTACTS, clearValues, null, null)
            }
            val values = ContentValues().apply {
                put(COL_CONTACT_NODE_ID, nodeId)
                put(COL_CONTACT_NAME, name)
                if (publicKeys != null) put(COL_CONTACT_PUBLIC_KEYS, publicKeys)
                if (sessionMasterKey != null) put(COL_CONTACT_MASTER_KEY, sessionMasterKey)
                if (targetMac != null) put(COL_CONTACT_TARGET_MAC, targetMac)
                put(COL_CONTACT_LAST_ACTIVE, System.currentTimeMillis())
                put(COL_CONTACT_IS_ACTIVE, if (isActive) 1 else 0)
            }
            db.insertWithOnConflict(TABLE_CONTACTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.i("DiskQueueDatabase", "Saved contact $nodeId ($name), isActive=$isActive")
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error saving contact $nodeId", e)
        }
    }

    @Synchronized
    fun getContacts(): List<ContactEntry> {
        val list = mutableListOf<ContactEntry>()
        try {
            val db = readableDatabase
            val query = "SELECT * FROM $TABLE_CONTACTS ORDER BY $COL_CONTACT_LAST_ACTIVE DESC"
            db.rawQuery(query, null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        ContactEntry(
                            nodeId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_NODE_ID)),
                            name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_NAME)),
                            publicKeys = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_PUBLIC_KEYS)),
                            sessionMasterKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_MASTER_KEY)),
                            targetMac = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_TARGET_MAC)),
                            lastActive = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CONTACT_LAST_ACTIVE)),
                            isActive = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CONTACT_IS_ACTIVE)) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error getting contacts", e)
        }
        return list
    }

    @Synchronized
    fun getActiveContact(): ContactEntry? {
        try {
            val db = readableDatabase
            val query = "SELECT * FROM $TABLE_CONTACTS WHERE $COL_CONTACT_IS_ACTIVE = 1 LIMIT 1"
            db.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    return ContactEntry(
                        nodeId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_NODE_ID)),
                        name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_NAME)),
                        publicKeys = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_PUBLIC_KEYS)),
                        sessionMasterKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_MASTER_KEY)),
                        targetMac = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT_TARGET_MAC)),
                        lastActive = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CONTACT_LAST_ACTIVE)),
                        isActive = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error getting active contact", e)
        }
        return null
    }

    @Synchronized
    fun setActiveContact(nodeId: String) {
        try {
            val db = writableDatabase
            val deactValues = ContentValues().apply { put(COL_CONTACT_IS_ACTIVE, 0) }
            db.update(TABLE_CONTACTS, deactValues, null, null)

            val actValues = ContentValues().apply {
                put(COL_CONTACT_IS_ACTIVE, 1)
                put(COL_CONTACT_LAST_ACTIVE, System.currentTimeMillis())
            }
            db.update(TABLE_CONTACTS, actValues, "$COL_CONTACT_NODE_ID = ?", arrayOf(nodeId))
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error setting active contact $nodeId", e)
        }
    }

    // ==========================================
    // 4. EMERGENCY WIPE (ALL TABLES PURGED)
    // ==========================================

    @Synchronized
    fun clearAll() {
        try {
            val db = writableDatabase
            db.delete(TABLE_OUTBOX, null, null)
            db.delete(TABLE_CHAT_MESSAGES, null, null)
            db.delete(TABLE_CONTACTS, null, null)
            Log.i("DiskQueueDatabase", "Emergency Wipe: Purged 100% of on-disk SQLite tables (outbox, chat_messages, contacts)")
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error clearing database tables", e)
        }
    }
}
