package obsidian.chat.xmpp

import org.jivesoftware.smack.Smack
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.pubsub.PayloadItem
import org.jivesoftware.smackx.pubsub.SimplePayload
import org.jivesoftware.smackx.pubsub.provider.ItemProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishedIdentityTest {
    // Shaped like the real thing, including the ellipsis PGPainless puts in long armor comments
    private val identity = PublishedIdentity(
        "-----BEGIN PGP PUBLIC KEY BLOCK-----\nComment: xmpp:alice@obsidianexampleonionaddressforunittestsonly…\n\n" +
            "mDMEaqmQPxYJKwYBBAHaRw8BAQdA2ht7wTuoJy5SMgdP9g8FJmypOZZpQ7KDnmkN\n=AbCd\n-----END PGP PUBLIC KEY BLOCK-----\n",
        listOf(
            DeviceBinding(
                586606904,
                "f0ba2fe060f60b595f95703f4828615472ccfeea99190884e7b73b573edcf502",
                "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQSsFKWUAOD6y9Bxk8y4V4gh5zD3qgUCaqmQPwAKCRC4V4gh\n-----END PGP SIGNATURE-----\n",
            ),
        ),
    )

    /** The path that used to throw inside Smack and silently drop every identity reply. */
    @Test
    fun survivesSmackPubSubItemParsing() {
        Smack.getVersion() // load Smack's providers the way the app does
        val itemXml = "<item xmlns='http://jabber.org/protocol/pubsub' id='current'>${identity.toElement().toXML()}</item>"
        val item = ItemProvider().parse(PacketParserUtils.getParserFor(itemXml)) as PayloadItem<*>
        assertEquals(identity, PublishedIdentity.fromPayload(item.payload))
    }

    @Test
    fun ignoresOtherPayloads() {
        assertNull(PublishedIdentity.fromPayload(SimplePayload("<something xmlns='urn:example'/>")))
        assertNull(PublishedIdentity.fromPayload(null))
    }
}
