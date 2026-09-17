package obsidian.chat.xmpp

import obsidian.chat.BuildConfig
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart

/** Usernames as sign-up allows them: 3 to 32 lower-case letters, digits, dots, dashes, underscores. */
val USERNAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{2,31}")

/** Creates [username] on our server over a connected connection that hasn't signed in. */
fun XMPPTCPConnection.registerAccount(username: String, password: String) {
    try {
        AccountManager.getInstance(this).createAccount(Localpart.from(username), password)
    } catch (e: XMPPException.XMPPErrorException) {
        if (e.stanzaError.condition == StanzaError.Condition.conflict) {
            throw IllegalArgumentException("The username \"$username\" is taken. Try another one.")
        }
        throw e
    }
}

/**
 * Asks our server whether a username exists (Prosody module mod_obsidian_lookup). Standard XMPP
 * answers the same whether or not an account exists, so nobody can probe for names; ours answers
 * signed-in members only, a limited number of times.
 */
class UserLookupIq(private val username: String) : IQ(ELEMENT, NAMESPACE) {
    init {
        type = Type.get
    }

    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        xml.attribute("user", username)
        xml.setEmptyElement()
        return xml
    }

    companion object {
        const val ELEMENT = "exists"
        const val NAMESPACE = "urn:obsidian:lookup:0"
    }
}

/** True if [username] has an account on our server. */
fun XMPPTCPConnection.userExists(username: String): Boolean {
    val query = UserLookupIq(username).apply { to = JidCreate.domainBareFrom(BuildConfig.SERVER_DOMAIN) }
    return try {
        sendIqRequestAndWaitForResponse<IQ>(query)
        true
    } catch (e: XMPPException.XMPPErrorException) {
        if (e.stanzaError.condition == StanzaError.Condition.item_not_found) false else throw e
    }
}
