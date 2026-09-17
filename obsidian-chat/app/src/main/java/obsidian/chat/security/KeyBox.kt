package obsidian.chat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals local secrets (database key, PGP secret key, account password) with an AES-256-GCM key
 * held by the Android Keystore, inside the StrongBox secure element when the device has one.
 * The key can't be exported, so copying the app's files off the phone yields only ciphertext.
 */
object KeyBox {
    private const val ALIAS = "obsidian-local-secrets"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey =
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /** Where the key lives, in words, for the security status screen. */
    fun securityLevel(): String {
        val key = key()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "the StrongBox secure element"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "the phone's trusted execution environment"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software only (no secure hardware, as on an emulator)"
            else -> "unknown storage"
        }
    }

    /** Deletes the key, so nothing it sealed can ever be decrypted again. */
    @Synchronized
    fun destroy() = keyStore.deleteEntry(ALIAS)
}
