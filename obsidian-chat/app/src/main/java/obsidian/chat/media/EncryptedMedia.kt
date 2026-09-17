package obsidian.chat.media

import java.net.URI
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Files are encrypted on the phone before upload, the way OMEMO clients share media (XEP-0454):
 * AES-256-GCM with a fresh key and IV per file, and the ciphertext (with its tag) is what the server
 * stores. The key travels only inside the OMEMO-encrypted message, as the fragment of an
 * `aesgcm://` link, so the server never sees it.
 */
object EncryptedMedia {
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val SCHEME = "aesgcm"
    private val random = SecureRandom()

    class Sealed(val ciphertext: ByteArray, val key: ByteArray, val iv: ByteArray)

    fun encrypt(plaintext: ByteArray): Sealed {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(cipher.doFinal(plaintext), key, iv)
    }

    /** Throws if the ciphertext was altered or the key is wrong. */
    fun decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** The link to send: the upload's https URL with the aesgcm scheme and `#<iv><key>` in hex. */
    fun link(downloadUrl: String, sealed: Sealed): String {
        require(downloadUrl.startsWith("https://")) { "Uploads must be served over https" }
        return SCHEME + downloadUrl.removePrefix("https") + "#" + (sealed.iv + sealed.key).toHex()
    }

    class Link(val downloadUrl: String, val host: String, val key: ByteArray, val iv: ByteArray)

    /** Parses an `aesgcm://` link; null if [text] isn't one. */
    fun parse(text: String): Link? {
        if (!text.startsWith("$SCHEME://")) return null
        val fragment = text.substringAfter('#', "")
        if (fragment.length != (IV_BYTES + KEY_BYTES) * 2 || !fragment.all { it in HEX }) return null
        val bytes = fragment.fromHex()
        val downloadUrl = "https" + text.substringBefore('#').removePrefix(SCHEME)
        val host = runCatching { URI(downloadUrl).host }.getOrNull() ?: return null
        return Link(downloadUrl, host, key = bytes.copyOfRange(IV_BYTES, bytes.size), iv = bytes.copyOfRange(0, IV_BYTES))
    }

    fun isLink(text: String) = parse(text) != null

    private const val HEX = "0123456789abcdef"
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
