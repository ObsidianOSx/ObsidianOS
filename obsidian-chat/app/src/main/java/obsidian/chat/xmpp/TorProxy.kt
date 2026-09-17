package obsidian.chat.xmpp

import java.util.UUID
import org.jivesoftware.smack.proxy.ProxyInfo

/**
 * SOCKS5 settings for the embedded Tor.
 *
 * Smack offers username/password authentication in its SOCKS5 greeting and Tor always picks it when
 * offered, so the connection fails unless credentials are set. Tor doesn't check them; it uses them
 * to isolate streams, so connections with different credentials never share a circuit.
 */
object TorProxy {
    /** New for every app process, so this process's circuits are never shared with other traffic. */
    private val isolationToken = UUID.randomUUID().toString()

    fun forSocksPort(port: Int): ProxyInfo =
        ProxyInfo.forSocks5Proxy("127.0.0.1", port, "obsidian-chat", isolationToken)
}
