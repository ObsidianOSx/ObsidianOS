package obsidian.chat.update

import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.json.JSONObject

/**
 * What the update server says is available, and the proof that we said it.
 *
 * The phone does not trust the server that hands it this file. The bytes are signed with the same
 * key that signs the operating system, and that signature is checked here against a certificate
 * built into the phone. A server that has been taken over can refuse to answer, but it cannot
 * invent a version, point a phone at a file of its choosing, or quietly send everyone backwards to
 * an older release with a known hole in it.
 */
data class UpdateMetadata(
    val device: String,
    val version: String,
    val full: Package,
    /** Smaller updates, each one usable only by a phone running exactly that version. */
    val incrementalFrom: Map<String, Package>,
) {
    data class Package(val file: String, val size: Long, val sha256: String)

    /** The package this phone should fetch: the small one when it fits, the whole system otherwise. */
    fun packageFor(installedVersion: String): Package = incrementalFrom[installedVersion] ?: full

    companion object {
        /** Signatures are made over the exact bytes of the metadata file, so parse only after checking. */
        fun verifyAndParse(json: ByteArray, signature: ByteArray, certificatePem: String): UpdateMetadata {
            require(certificatePem.isNotBlank()) {
                "This build has no update certificate, so it cannot check whether an update is genuine"
            }
            val certificate = parseCertificate(certificatePem)
            val verifier = Signature.getInstance("SHA256withRSA").apply {
                initVerify(certificate)
                update(json)
            }
            require(verifier.verify(signature)) {
                "The update information was not signed by OBSIDIAN, so it will not be used"
            }
            return parse(String(json, Charsets.UTF_8))
        }

        private fun parseCertificate(pem: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
                    as X509Certificate

        private fun parse(text: String): UpdateMetadata {
            val root = JSONObject(text)
            val incremental = root.optJSONObject("incremental")
            val from = buildMap {
                incremental?.keys()?.forEach { key -> put(key, readPackage(incremental.getJSONObject(key))) }
            }
            return UpdateMetadata(
                device = root.getString("device"),
                version = root.getString("version"),
                full = readPackage(root.getJSONObject("full")),
                incrementalFrom = from,
            )
        }

        private fun readPackage(json: JSONObject) = Package(
            file = json.getString("file"),
            size = json.getLong("size"),
            sha256 = json.getString("sha256").lowercase(),
        )
    }
}
