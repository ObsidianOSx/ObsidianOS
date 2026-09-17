package obsidian.chat.xmpp

import android.util.Base64
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one server key: the SHA-256 of its SubjectPublicKeyInfo must equal the pin built
 * into the app. Certificate authorities play no part, so a mis-issued certificate can't be used to
 * impersonate the server.
 */
class PinnedTrustManager(private val spkiSha256Base64: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Server sent no certificate")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        if (Base64.encodeToString(digest, Base64.NO_WRAP) != spkiSha256Base64) {
            throw CertificateException("Server key doesn't match the pinned key")
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("Client certificates aren't used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
