package obsidian.chat.xmpp

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class TorProxyTest {
    private val onion = "obsidianexampleonionaddressforunittestsonlyaaaaaaaaaaaaa.onion"

    /** Plays Tor's side of the SOCKS5 handshake: it picks username/password auth whenever offered. */
    @Test
    fun handshakeSucceedsAndNamesTheOnion() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var offeredAuth = false
            var addressType = -1
            var requestedHost: String? = null
            var requestedPort = -1
            val tor = thread {
                server.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val output = client.getOutputStream()
                    input.readUnsignedByte() // version
                    val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    offeredAuth = 2.toByte() in methods
                    output.write(byteArrayOf(5, 2))
                    // RFC 1929 username/password; Tor accepts any values
                    input.readUnsignedByte()
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    output.write(byteArrayOf(1, 0))
                    // CONNECT request: version, command, reserved, address type, address, port
                    input.readUnsignedByte()
                    input.readUnsignedByte()
                    input.readUnsignedByte()
                    addressType = input.readUnsignedByte()
                    if (addressType == 3) {
                        requestedHost = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                    }
                    requestedPort = input.readUnsignedShort()
                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
            }

            Socket().use { socket ->
                TorProxy.forSocksPort(server.localPort).proxySocketConnection.connect(socket, onion, 5222, 5_000)
            }
            tor.join(5_000)

            assertEquals(true, offeredAuth)
            // 3 = a name, which Tor resolves itself; 1 or 4 would mean an IP address was looked up locally
            assertEquals(3, addressType)
            assertEquals(onion, requestedHost)
            assertEquals(5222, requestedPort)
        }
    }
}
