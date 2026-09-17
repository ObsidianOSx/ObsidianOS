package obsidian.chat.xmpp

import java.net.Inet4Address
import org.jivesoftware.smack.ConnectionConfiguration.DnssecMode
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.DNSUtil
import org.jivesoftware.smack.util.dns.minidns.MiniDnsResolver
import org.jivesoftware.smack.util.rce.RemoteConnectionEndpointLookupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.minidns.dnsname.DnsName

class TorOnlyDnsTest {
    private val onion = "obsidianexampleonionaddressforunittestsonlyaaaaaaaaaaaaa.onion"

    @Test
    fun resolverSurvivesSmackStartup() {
        TorOnlyDns.install()
        // Building a configuration is what caused the leak: it loaded Smack's startup classes, one
        // of which installed MiniDNS over our resolver
        XMPPTCPConnectionConfiguration.builder().setXmppDomain(onion).setHost(onion).build()
        assertSame(NoNetworkDnsResolver, DNSUtil.getDNSResolver())
        TorOnlyDns.check()
    }

    @Test
    fun lookupsNeverTouchTheNetwork() {
        val failures = mutableListOf<RemoteConnectionEndpointLookupFailure>()
        val addresses = NoNetworkDnsResolver.lookupHostAddress(DnsName.from(onion), failures, DnssecMode.disabled)
        assertEquals(1, addresses.size)
        // The name is passed through untouched for Tor to resolve; the address is a placeholder
        assertEquals(onion, addresses[0].hostName)
        assertTrue(addresses[0] is Inet4Address && addresses[0].address.all { it == 0.toByte() })
        val srv = NoNetworkDnsResolver.lookupSrvRecords(DnsName.from("_xmpp-client._tcp.$onion"), failures, DnssecMode.disabled)
        assertTrue(srv.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun checkFailsClosedWhenRealDnsIsInstalled() {
        TorOnlyDns.install()
        MiniDnsResolver.setup()
        try {
            TorOnlyDns.check()
        } finally {
            TorOnlyDns.install()
        }
    }
}
