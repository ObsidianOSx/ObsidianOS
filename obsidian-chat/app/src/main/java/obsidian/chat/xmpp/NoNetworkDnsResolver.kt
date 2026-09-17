package obsidian.chat.xmpp

import java.net.InetAddress
import org.jivesoftware.smack.ConnectionConfiguration.DnssecMode
import org.jivesoftware.smack.util.dns.DNSResolver
import org.jivesoftware.smack.util.rce.RemoteConnectionEndpointLookupFailure
import org.minidns.dnsname.DnsName
import org.minidns.record.SRV

/**
 * Smack resolves the configured host before it opens a proxied connection. Real DNS would leak the
 * server's .onion name to the local network (and fail anyway), so this resolver never touches the
 * network: it returns a placeholder address and Smack then hands the name itself to Tor over SOCKS5.
 */
object NoNetworkDnsResolver : DNSResolver(false) {
    override fun lookupSrvRecords0(
        name: DnsName,
        lookupFailures: MutableList<RemoteConnectionEndpointLookupFailure>,
        dnssecMode: DnssecMode,
    ): Collection<SRV> = emptyList()

    override fun lookupHostAddress0(
        name: DnsName,
        lookupFailures: MutableList<RemoteConnectionEndpointLookupFailure>,
        dnssecMode: DnssecMode,
    ): List<InetAddress> = listOf(InetAddress.getByAddress(name.toString(), ByteArray(4)))
}
