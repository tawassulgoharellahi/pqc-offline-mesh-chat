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

/**
 * DiskQueueDatabase: An atomic, crash-resilient on-disk SQLite FIFO message queue.
 * Ensures strict First-In, First-Out (FIFO) sequential delivery with zero memory bloat.
 */
class DiskQueueDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "pqc_fifo_mesh.db"
        private const val DATABASE_VERSION = 1

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

        @Volatile
        private var instance: DiskQueueDatabase? = null

        fun getInstance(context: Context): DiskQueueDatabase {
            return instance ?: synchronized(this) {
                instance ?: DiskQueueDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
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
        """.trimIndent()
        db.execSQL(createTableQuery)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_id ON $TABLE_OUTBOX ($COL_STATUS, $COL_ID ASC)")
        Log.i("DiskQueueDatabase", "FIFO outbox database initialized on disk")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_OUTBOX")
        onCreate(db)
    }

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
            // Delete directly upon confirmed delivery to keep disk lean
            val deleted = db.delete(TABLE_OUTBOX, "$COL_MSG_ID = ?", arrayOf(msgId))
            Log.i("DiskQueueDatabase", "Marked delivered & purged $msgId from disk (rows=$deleted)")
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
    fun clearAll() {
        try {
            val db = writableDatabase
            db.delete(TABLE_OUTBOX, null, null)
            Log.i("DiskQueueDatabase", "Emergency Wipe: Purged all on-disk outbox packets")
        } catch (e: Exception) {
            Log.e("DiskQueueDatabase", "Error clearing FIFO database", e)
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
}
