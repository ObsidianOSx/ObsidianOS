package obsidian.chat.update

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import obsidian.chat.BuildConfig
import obsidian.chat.xmpp.TorProxy
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches update information and update packages, always through Tor.
 *
 * The update site learns nothing it could use: the request arrives from a Tor exit rather than the
 * phone, and carries no identifier of any kind. It cannot tell one phone from another, or an
 * OBSIDIAN phone from anything else asking for the same public file.
 *
 * Nothing here decides whether an answer is genuine. The metadata carries a signature that is
 * checked against a certificate built into the phone, and the package carries one that
 * update_engine checks before it writes a byte. A hostile server can refuse to answer, and that
 * is all.
 */
class UpdateDownloader(private val socksPort: Int) {
    private val client = OkHttpClient.Builder()
        // Hand the name to Tor rather than resolving it here: the address is a placeholder.
        .dns(Dns { hostname -> listOf(InetAddress.getByAddress(hostname, ByteArray(4))) })
        .socketFactory(TorSocketFactory(socksPort))
        .retryOnConnectionFailure(false)
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Reads a small file, such as the update information or its signature. The cap is there so a
     * server that answers with something enormous cannot fill the phone's memory.
     */
    fun fetchBytes(path: String, limit: Int = 512 * 1024): ByteArray {
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The update server answered ${response.code}")
            val collected = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            response.body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (collected.size() + read > limit) throw IOException("The update server sent far more than expected")
                    collected.write(buffer, 0, read)
                }
            }
            return collected.toByteArray()
        }
    }

    /**
     * Downloads a package to [destination], checking it against [expectedSha256] as it arrives.
     * A file that does not match is deleted rather than kept, so a half download cannot be mistaken
     * for a whole one later.
     */
    fun download(
        path: String,
        expectedSize: Long,
        expectedSha256: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            client.newCall(Request.Builder().url(url(path)).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The update server answered ${response.code}")
                destination.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    response.body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            received += read
                            if (received > expectedSize) throw IOException("The update is larger than it claimed to be")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                            onProgress(received, expectedSize)
                        }
                    }
                }
            }
            if (received != expectedSize) throw IOException("The update stopped downloading part way through")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("The update does not match its published checksum, so it will not be installed")
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /** Update files come from the OBSIDIAN site and nowhere else, whatever any answer might say. */
    private fun url(path: String): String {
        val clean = path.trimStart('/')
        val full = "https://${BuildConfig.UPDATE_DOMAIN}/$clean"
        val host = full.toHttpUrlOrNull()?.host
        require(host == BuildConfig.UPDATE_DOMAIN) { "Updates are only ever fetched from our own site" }
        return full
    }

    private class TorSocketFactory(private val socksPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = TorSocket(socksPort)
        override fun createSocket(host: String, port: Int): Socket = unsupported()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("Connections must be made through Tor")
    }

    private class TorSocket(private val socksPort: Int) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as InetSocketAddress
            if (target.port == socksPort && target.address?.isLoopbackAddress == true) {
                super.connect(endpoint, timeout)
                return
            }
            TorProxy.forSocksPort(socksPort).proxySocketConnection
                .connect(this, target.hostString, target.port, timeout)
        }
    }
}
