package obsidian.chat.xmpp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import obsidian.chat.BuildConfig
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The HTTP side of media sharing, held to the same rules as the XMPP connection: every byte goes
 * through Tor by name, the server's TLS key is pinned, names are never resolved on the local
 * network, and only our own server may be contacted.
 */
object TorHttp {
    private const val OCTET_STREAM = "application/octet-stream"
    private const val MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024

    fun upload(socksPort: Int, url: String, headers: Map<String, String>, body: ByteArray) {
        val request = Request.Builder()
            .url(ourServerUrl(url))
            .put(body.toRequestBody(OCTET_STREAM.toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client(socksPort).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The server refused the upload (${response.code})")
        }
    }

    fun download(socksPort: Int, url: String): ByteArray {
        val request = Request.Builder().url(ourServerUrl(url)).build()
        client(socksPort).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The file is no longer on the server (${response.code})")
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            response.body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (bytes.size() + read > MAX_DOWNLOAD_BYTES) throw IOException("That file is too large to open")
                    bytes.write(buffer, 0, read)
                }
            }
            return bytes.toByteArray()
        }
    }

    private fun ourServerUrl(url: String): String {
        val host = url.toHttpUrlOrNull()?.host ?: throw IOException("That link isn't a web address")
        require(host == BuildConfig.SERVER_DOMAIN || host.endsWith(".${BuildConfig.SERVER_DOMAIN}")) {
            "Media is only ever fetched from our own server"
        }
        return url
    }

    private fun client(socksPort: Int): OkHttpClient {
        val pinned = PinnedTrustManager(BuildConfig.SERVER_SPKI_SHA256)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pinned), null) }
        return OkHttpClient.Builder()
            // Hand the name to Tor instead of looking it up: the address here is only a placeholder
            .dns(Dns { hostname -> listOf(InetAddress.getByAddress(hostname, ByteArray(4))) })
            .socketFactory(TorSocketFactory(socksPort))
            .sslSocketFactory(tls.socketFactory, pinned)
            // The pinned key authenticates the server, so the name check adds nothing
            .hostnameVerifier { _, _ -> true }
            .retryOnConnectionFailure(false)
            .connectTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private class TorSocketFactory(private val socksPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = TorSocket(socksPort)

        // OkHttp only ever creates unconnected sockets; the rest are here to satisfy the interface
        override fun createSocket(host: String, port: Int): Socket = unsupported()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("Connections must be made through Tor")
    }

    /** A socket that reaches its destination through Tor, by name, never touching local DNS. */
    private class TorSocket(private val socksPort: Int) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as InetSocketAddress
            if (target.port == socksPort && target.address?.isLoopbackAddress == true) {
                // The SOCKS5 client connects this socket to Tor first, which lands back here
                super.connect(endpoint, timeout)
                return
            }
            TorProxy.forSocksPort(socksPort).proxySocketConnection
                .connect(this, target.hostString, target.port, timeout)
        }
    }
}
