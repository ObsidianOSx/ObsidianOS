package obsidian.chat.data

import android.content.Context
import android.util.Base64
import obsidian.chat.security.KeyBox

/**
 * What [SecureStore] offers its callers. Split out so logic like the PIN lock can be tested on a
 * plain JVM, where there is no Android Keystore to seal anything with.
 */
interface KeyValueStore {
    fun putString(name: String, value: String)
    fun getString(name: String): String?
    fun contains(name: String): Boolean
    fun remove(name: String)
}

/** Small key-value store whose values are sealed with [KeyBox] before they touch disk. */
class SecureStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    override fun putString(name: String, value: String) {
        val sealed = KeyBox.seal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    override fun getString(name: String): String? =
        prefs.getString(name, null)?.let { KeyBox.open(Base64.decode(it, Base64.NO_WRAP)).toString(Charsets.UTF_8) }

    override fun contains(name: String): Boolean = prefs.contains(name)

    override fun remove(name: String) {
        prefs.edit().remove(name).commit()
    }

    /** Removes every stored value and destroys the Keystore key that sealed them. */
    fun wipe() {
        prefs.edit().clear().commit()
        KeyBox.destroy()
    }

    companion object {
        const val ACCOUNT_JID = "account.jid"
        const val ACCOUNT_PASSWORD = "account.password"
        const val ACCOUNT_RESOURCE = "account.resource"
        const val PGP_SECRET_KEY = "pgp.secret"
        const val PGP_PASSPHRASE = "pgp.passphrase"
        const val DATABASE_KEY = "db.key"
        const val OMEMO_DEVICE_ID = "omemo.device"
        const val LOCK_SALT = "lock.salt"
        const val LOCK_HASH = "lock.hash"
    }
}
