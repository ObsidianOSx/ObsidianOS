package obsidian.chat

import android.app.Application
import java.io.File
import obsidian.chat.data.SecureStore
import obsidian.chat.security.DeviceLock
import obsidian.chat.tor.TorManager
import obsidian.chat.xmpp.ChatClient
import obsidian.chat.xmpp.TorOnlyDns
import org.jivesoftware.smack.SmackConfiguration
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smackx.omemo.OmemoConfiguration
import org.jivesoftware.smackx.omemo.OmemoService
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService

class ObsidianApp : Application() {
    lateinit var tor: TorManager
        private set
    lateinit var client: ChatClient
        private set
    lateinit var secureStore: SecureStore
        private set
    lateinit var updates: obsidian.chat.update.UpdateManager
        private set

    /** Lives as long as the app, for work that should not stop when a screen closes. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    override fun onCreate() {
        super.onCreate()

        AndroidSmackInitializer.initialize(this)
        // Never resolve names on the local network; every connection goes to Tor by name
        TorOnlyDns.install()
        // Every request crosses an onion circuit, where Smack's default 5 s reply timeout is too short
        // (the TLS handshake alone can take longer)
        SmackConfiguration.setDefaultReplyTimeout(30_000)

        // No plaintext "this message is OMEMO encrypted" hint body in outgoing stanzas
        OmemoConfiguration.setAddOmemoHintBody(false)
        // smack-omemo-signal is GPLv3; this app is distributed under the GPLv3 as well
        SignalOmemoService.acknowledgeLicense()
        SignalOmemoService.setup()
        (OmemoService.getInstance() as SignalOmemoService)
            .setOmemoStoreBackend(SignalCachingOmemoStore(SignalFileBasedOmemoStore(File(filesDir, "omemo"))))

        // As the phone's device owner: location off for good, no side-loading, decent screen lock
        DeviceLock.applyLockdown(this)
        Notifications.ensureChannel(this)

        tor = TorManager(this).also { it.start() }
        secureStore = SecureStore(this)
        client = ChatClient(this, tor, secureStore)

        // Look for operating system updates in the background, so a phone does not stay on an old
        // system just because nobody opened the security screen. It only ever asks; installing is
        // still something a person chooses.
        updates = obsidian.chat.update.UpdateManager(this, appScope).also { it.watchForUpdates(tor.state) }
    }
}
