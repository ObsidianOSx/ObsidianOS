package obsidian.chat.xmpp

import org.jivesoftware.smack.Smack
import org.jivesoftware.smack.util.DNSUtil

/**
 * Keeps Smack on [NoNetworkDnsResolver] so the server's name only ever goes to Tor.
 *
 * Smack loads its startup classes lazily, the first time SmackInitialization is touched, and one of
 * them (MiniDnsResolver) installs itself as the global resolver. If our resolver is set before that
 * happens it gets silently replaced, and the next connection asks the local network's DNS for the
 * .onion name.
 */
object TorOnlyDns {
    fun install() {
        // Reading the version runs SmackInitialization's static block, which loads every startup class
        Smack.getVersion()
        DNSUtil.setDNSResolver(NoNetworkDnsResolver)
    }

    fun isInstalled(): Boolean = DNSUtil.getDNSResolver() === NoNetworkDnsResolver

    /** Throws if anything has put a real DNS resolver back since [install]. */
    fun check() {
        check(isInstalled()) {
            "A network DNS resolver is installed, so connecting could leak the server's name. Refusing to connect."
        }
    }
}
