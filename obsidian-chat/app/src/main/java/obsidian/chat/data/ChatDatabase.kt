package obsidian.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class Contact(
    val jid: String,
    /** PGP fingerprint pinned the first time the contact's identity was fetched. */
    val pgpFingerprint: String?,
    val pgpCertificate: String?,
    /** True once the user compared fingerprints with the contact in person. */
    val verified: Boolean,
    /** They asked to add us and we haven't accepted yet. Their messages are dropped until then. */
    val incomingRequest: Boolean,
)

data class ChatMessage(
    /** XEP-0359 origin-id, shared by both sides so deletions can refer to it. */
    val id: String,
    val contact: String,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    /** Incoming messages stay sealed until the user taps them. */
    val opened: Boolean,
    /** Sent from an OMEMO device that the contact's PGP key vouches for. */
    val trusted: Boolean,
)

/** SQLCipher database holding contacts and messages; the whole file is encrypted on disk. */
class ChatDatabase(context: Context, key: ByteArray) {
    private val db: SQLiteDatabase

    init {
        System.loadLibrary("sqlcipher")
        db = SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "chat.db"), key, null, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS contacts (jid TEXT PRIMARY KEY, pgp_fingerprint TEXT, " +
                "pgp_certificate TEXT, verified INTEGER NOT NULL DEFAULT 0, " +
                "incoming_request INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, contact TEXT NOT NULL, " +
                "outgoing INTEGER NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, " +
                "opened INTEGER NOT NULL, trusted INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_by_contact ON messages(contact, timestamp)")
    }

    @Synchronized
    fun upsertContact(contact: Contact) {
        val values = ContentValues().apply {
            put("jid", contact.jid)
            put("pgp_fingerprint", contact.pgpFingerprint)
            put("pgp_certificate", contact.pgpCertificate)
            put("verified", if (contact.verified) 1 else 0)
            put("incoming_request", if (contact.incomingRequest) 1 else 0)
        }
        db.insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun contact(jid: String): Contact? =
        db.rawQuery("$CONTACT_COLUMNS WHERE jid = ?", arrayOf(jid)).use { c -> if (c.moveToFirst()) c.toContact() else null }

    @Synchronized
    fun contacts(): List<Contact> =
        db.rawQuery("$CONTACT_COLUMNS ORDER BY incoming_request DESC, jid", arrayOf<String>()).use { c ->
            buildList { while (c.moveToNext()) add(c.toContact()) }
        }

    @Synchronized
    fun deleteContact(jid: String) {
        db.delete("contacts", "jid = ?", arrayOf(jid))
        db.delete("messages", "contact = ?", arrayOf(jid))
    }

    @Synchronized
    fun insertMessage(message: ChatMessage) {
        val values = ContentValues().apply {
            put("id", message.id)
            put("contact", message.contact)
            put("outgoing", if (message.outgoing) 1 else 0)
            put("body", message.body)
            put("timestamp", message.timestamp)
            put("opened", if (message.opened) 1 else 0)
            put("trusted", if (message.trusted) 1 else 0)
        }
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun messages(contact: String): List<ChatMessage> =
        db.rawQuery(
            "SELECT id, contact, outgoing, body, timestamp, opened, trusted FROM messages " +
                "WHERE contact = ? ORDER BY timestamp",
            arrayOf(contact),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ChatMessage(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getString(3),
                        c.getLong(4), c.getInt(5) == 1, c.getInt(6) == 1))
                }
            }
        }

    /** How many sealed messages are waiting, per contact. */
    @Synchronized
    fun unreadByContact(): Map<String, Int> =
        db.rawQuery(
            "SELECT contact, COUNT(*) FROM messages WHERE outgoing = 0 AND opened = 0 GROUP BY contact",
            arrayOf<String>(),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    @Synchronized
    fun markOpened(id: String) = db.execSQL("UPDATE messages SET opened = 1 WHERE id = ?", arrayOf<Any?>(id))

    /** Deletes a message only if it belongs to [contact], so a peer can't delete other chats. */
    @Synchronized
    fun deleteMessage(contact: String, id: String): Boolean =
        db.delete("messages", "id = ? AND contact = ?", arrayOf(id, contact)) > 0

    @Synchronized
    fun close() = db.close()

    private fun Cursor.toContact() =
        Contact(getString(0), getString(1), getString(2), getInt(3) == 1, getInt(4) == 1)

    private companion object {
        const val CONTACT_COLUMNS = "SELECT jid, pgp_fingerprint, pgp_certificate, verified, incoming_request FROM contacts"
    }
}
