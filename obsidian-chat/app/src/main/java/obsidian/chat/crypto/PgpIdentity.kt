package obsidian.chat.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.signature.SignatureUtils
import org.pgpainless.util.ArmorUtils
import org.pgpainless.util.Passphrase

/**
 * The user's long-term OpenPGP identity. It is what the user registers with, what contacts verify
 * in person, and what vouches for each OMEMO device key that encrypts the actual messages.
 */
object PgpIdentity {
    private val api: PGPainless get() = PGPainless.getInstance()

    /** EdDSA primary key with EdDSA signing and X25519 encryption subkeys. */
    fun generate(userId: String): OpenPGPKey = api.generateKey().modernKeyRing(userId, null as String?)

    fun parseKey(armored: String): OpenPGPKey = api.readKey().parseKey(armored)

    fun parseCertificate(armored: String): OpenPGPCertificate = api.readKey().parseCertificate(armored)

    fun armoredSecretKey(key: OpenPGPKey): String = key.toAsciiArmoredString()

    fun armoredPublicKey(key: OpenPGPKey): String = key.toCertificate().toAsciiArmoredString()

    fun fingerprint(certificate: OpenPGPCertificate): String = api.inspect(certificate).fingerprint.toString()

    fun protector(passphrase: String?): SecretKeyRingProtector =
        if (passphrase.isNullOrEmpty()) SecretKeyRingProtector.unprotectedKeys()
        else SecretKeyRingProtector.unlockAnyKeyWith(Passphrase.fromPassword(passphrase))

    /** Creates a detached, ASCII-armored signature over [data]. */
    fun sign(key: OpenPGPKey, protector: SecretKeyRingProtector, data: ByteArray): String {
        val stream = api.generateMessage()
            .onOutputStream(ByteArrayOutputStream())
            .withOptions(
                ProducerOptions.sign(SigningOptions.get(api).addDetachedSignature(protector, key))
                    .setAsciiArmor(false)
            )
        stream.write(data)
        stream.close()
        return ArmorUtils.toAsciiArmoredString(stream.result.detachedSignatures.flatten().first())
    }

    /** True if [armoredSignature] is a valid detached signature over [data] made by [certificate]. */
    fun verify(certificate: OpenPGPCertificate, data: ByteArray, armoredSignature: String): Boolean =
        runCatching {
            val options = ConsumerOptions.get(api).addVerificationCert(certificate)
            SignatureUtils.readSignatures(armoredSignature).forEach { options.addVerificationOfDetachedSignature(it) }
            val stream = api.processMessage().onInputStream(ByteArrayInputStream(data)).withOptions(options)
            stream.readBytes()
            stream.close()
            stream.metadata.isVerifiedSignedBy(certificate)
        }.getOrDefault(false)

    /** Groups a hex fingerprint into blocks of four for reading aloud or comparing on screen. */
    fun formatFingerprint(fingerprint: String): String = fingerprint.uppercase().chunked(4).joinToString(" ")
}
