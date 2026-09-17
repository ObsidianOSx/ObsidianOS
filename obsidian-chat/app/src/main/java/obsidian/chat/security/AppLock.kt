package obsidian.chat.security

import java.security.MessageDigest
import java.security.SecureRandom
// java.util, not android.util: this class is pure logic like its neighbours, and android.util.Base64
// is a stub that returns null under JVM unit tests. Both emit the same unwrapped base64.
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obsidian.chat.data.KeyValueStore
import obsidian.chat.data.SecureStore

/**
 * The PIN that locks the app. Only a salted, stretched hash is kept, sealed by the Keystore like
 * every other secret, so the PIN itself is never written down anywhere.
 */
object AppLock {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun isSet(store: KeyValueStore): Boolean = store.contains(SecureStore.LOCK_HASH)

    /**
     * Stretching a 4-digit PIN 120,000 times is deliberately slow - that is what makes the PIN
     * expensive to guess. It therefore must never run on the main thread: it froze the UI for
     * ~33 seconds and Android killed the app with "isn't responding" (ANR) when the Unlock button
     * called straight into it. Both entry points suspend onto a worker thread instead.
     */
    suspend fun setPin(store: KeyValueStore, pin: String) {
        require(pin.length in 4..16 && pin.all { it.isDigit() }) { "A PIN is 4 to 16 digits" }
        val salt = ByteArray(16).also(random::nextBytes)
        val hashed = hash(pin, salt)
        store.putString(SecureStore.LOCK_SALT, Base64.getEncoder().encodeToString(salt))
        store.putString(SecureStore.LOCK_HASH, Base64.getEncoder().encodeToString(hashed))
    }

    suspend fun verify(store: KeyValueStore, pin: String): Boolean {
        val salt = store.getString(SecureStore.LOCK_SALT)?.let { Base64.getDecoder().decode(it) } ?: return false
        val expected = store.getString(SecureStore.LOCK_HASH)?.let { Base64.getDecoder().decode(it) } ?: return false
        // Constant-time, so a wrong PIN tells an attacker nothing by how long it took
        return MessageDigest.isEqual(expected, hash(pin, salt))
    }

    fun clear(store: KeyValueStore) {
        store.remove(SecureStore.LOCK_HASH)
        store.remove(SecureStore.LOCK_SALT)
    }

    /**
     * Where the stretching runs. Injectable so a test can prove it happens off the caller's thread:
     * that cannot be observed from outside, because [withContext] resumes on the calling thread and
     * every value this object stores is therefore written back there regardless.
     */
    @Volatile
    internal var hashingDispatcher: CoroutineDispatcher = Dispatchers.Default

    private suspend fun hash(pin: String, salt: ByteArray): ByteArray = withContext(hashingDispatcher) {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded
    }
}
