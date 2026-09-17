package obsidian.chat.xmpp

import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.XmlElement
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.pubsub.SimplePayload

/** An OMEMO device key vouched for by the account's PGP key. */
data class DeviceBinding(val deviceId: Int, val omemoFingerprint: String, val signature: String)

/**
 * What each account publishes on its PEP node: its armored PGP certificate, plus PGP signatures
 * binding each of its OMEMO device keys to that certificate. Contacts verify the PGP fingerprint
 * once, in person, and from then on only trust OMEMO devices that the PGP key has signed.
 */
data class PublishedIdentity(val pgpCertificate: String, val bindings: List<DeviceBinding>) {
    fun toElement(): StandardExtensionElement {
        val builder = StandardExtensionElement.builder(ELEMENT, NAMESPACE).addElement(PGP, pgpCertificate)
        for (binding in bindings) {
            builder.addElement(
                StandardExtensionElement.builder(BINDING, NAMESPACE)
                    .addAttribute(ATTR_DEVICE, binding.deviceId.toString())
                    .addAttribute(ATTR_FINGERPRINT, binding.omemoFingerprint)
                    .setText(binding.signature)
                    .build()
            )
        }
        return builder.build()
    }

    companion object {
        const val NODE = "urn:obsidian:identity:0"
        const val ELEMENT = "identity"
        const val NAMESPACE = "urn:obsidian:identity:0"
        private const val PGP = "pgp"
        private const val BINDING = "binding"
        private const val ATTR_DEVICE = "device"
        private const val ATTR_FINGERPRINT = "fingerprint"

        fun fromElement(element: StandardExtensionElement): PublishedIdentity? {
            val pgp = element.getFirstElement(PGP)?.text ?: return null
            val bindings = element.getElements(BINDING).mapNotNull { binding ->
                val deviceId = binding.getAttributeValue(ATTR_DEVICE)?.toIntOrNull()
                val fingerprint = binding.getAttributeValue(ATTR_FINGERPRINT)
                val signature = binding.text
                if (deviceId == null || fingerprint == null || signature == null) null
                else DeviceBinding(deviceId, fingerprint, signature)
            }
            return PublishedIdentity(pgp, bindings)
        }

        /**
         * Reads a PEP item payload. No provider is registered for our element, so Smack hands it over
         * as raw XML: registering StandardExtensionElementProvider makes Smack 4.5's PubSub ItemProvider
         * throw a ClassCastException and silently drop the whole reply.
         */
        fun fromPayload(payload: XmlElement?): PublishedIdentity? {
            val element = when (payload) {
                is StandardExtensionElement -> payload
                is SimplePayload -> StandardExtensionElementProvider.INSTANCE.parse(
                    PacketParserUtils.getParserFor(payload.toXML().toString())
                )
                else -> null
            }
            if (element == null || element.elementName != ELEMENT || element.namespace != NAMESPACE) return null
            return fromElement(element)
        }

        /** The exact bytes a binding signature covers. */
        fun bindingStatement(jid: String, deviceId: Int, omemoFingerprint: String): ByteArray =
            "obsidian-omemo-binding:v1\n$jid\n$deviceId\n${normalizeFingerprint(omemoFingerprint)}\n"
                .toByteArray(Charsets.UTF_8)

        fun normalizeFingerprint(fingerprint: String): String = fingerprint.filterNot { it.isWhitespace() }.lowercase()
    }
}
