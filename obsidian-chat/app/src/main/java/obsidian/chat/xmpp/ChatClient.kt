package obsidian.chat.xmpp

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import obsidian.chat.BuildConfig
import obsidian.chat.crypto.PgpIdentity
import obsidian.chat.data.ChatDatabase
import obsidian.chat.data.ChatMessage
import obsidian.chat.data.Contact
import obsidian.chat.data.SecureStore
import obsidian.chat.media.EncryptedMedia
import obsidian.chat.security.PanicWipe
import obsidian.chat.tor.TorManager
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.roster.AbstractRosterListener
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.SubscribeListener
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jivesoftware.smackx.message_fastening.element.FasteningElement
import org.jivesoftware.smackx.message_retraction.MessageRetractionManager
import org.jivesoftware.smackx.message_retraction.element.RetractElement
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.OmemoMessage
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState
import org.jivesoftware.smackx.pep.PepManager
import org.jivesoftware.smackx.ping.PingManager
import org.jivesoftware.smackx.pubsub.PayloadItem
import org.jivesoftware.smackx.pubsub.PubSubManager
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jxmpp.jid.BareJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

/**
 * Everything the app does on the network: invite registration, login over Tor, the PGP-anchored
 * OMEMO trust model, contact requests, messaging and deletion.
 *
 * Trust model: each account publishes its PGP certificate and, for every OMEMO device, a PGP
 * signature over that device's key. We pin a contact's PGP fingerprint the first time we see it
 * (and the user confirms it in person), and only encrypt to OMEMO devices whose binding signature
 * verifies against that pinned certificate.
 */
class ChatClient(
    private val context: Context,
    private val tor: TorManager,
    private val store: SecureStore,
) {
    sealed interface Status {
        data object SignedOut : Status
        data object Connecting : Status
        data class Online(val jid: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.SignedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Bumped whenever contacts or messages change, so the UI knows to reload. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var connection: XMPPTCPConnection? = null
    private var omemo: OmemoManager? = null
    private var pgpKey: OpenPGPKey? = null
    private var pgpPassphrase: String? = null

    /** jid -> (OMEMO device id -> normalized fingerprint) for devices the contact's PGP key vouches for. */
    private val trustedDevices = ConcurrentHashMap<String, Map<Int, String>>()

    private val databaseLazy = lazy {
        val encoded = store.getString(SecureStore.DATABASE_KEY) ?: Base64.encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP,
        ).also { store.putString(SecureStore.DATABASE_KEY, it) }
        ChatDatabase(context, Base64.decode(encoded, Base64.NO_WRAP))
    }
    private val database: ChatDatabase by databaseLazy

    val hasAccount: Boolean get() = store.contains(SecureStore.ACCOUNT_JID)
    var ownJid: String? = null
        private set
    var ownPgpFingerprint: String? = null
        private set
    var ownOmemoFingerprint: String? = null
        private set
    val ownOmemoDeviceId: Int? get() = omemo?.deviceId

    private fun changed() = _changes.update { it + 1 }

    // ---------------------------------------------------------------------------------------------
    // Account

    /**
     * Deletes the account from the server (when online) and everything the app keeps on this phone:
     * messages, contacts, OMEMO keys, the PGP key, and the Keystore key that sealed them. Then ends
     * the process, so nothing of the account stays in memory either.
     */
    suspend fun eraseAccount() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                connection?.let { conn ->
                    ReconnectionManager.getInstanceFor(conn).disableAutomaticReconnection()
                    if (conn.isAuthenticated) runCatching { AccountManager.getInstance(conn).deleteAccount() }
                    runCatching { conn.instantShutdown() }
                }
                if (databaseLazy.isInitialized()) database.close()
                PanicWipe.wipeLocalData(context, store)
            }
        }
        System.exit(0)
    }

    /**
     * The hold-to-wipe button. Erases everything this app holds and then factory resets the phone
     * if it is allowed to. It never waits for the network, so it works with Tor down or no signal.
     */
    fun panicWipe() {
        runCatching { connection?.instantShutdown() }
        if (databaseLazy.isInitialized()) runCatching { database.close() }
        PanicWipe.wipeLocalData(context, store)
        PanicWipe.factoryReset(context)
        System.exit(0)
    }

    suspend fun createAccount(username: String, password: String, importedKey: String?, keyPassphrase: String?) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                require(USERNAME_PATTERN.matches(username)) { "Usernames are 3–32 characters: a–z, 0–9, dot, dash, underscore" }
                val jid = "$username@${BuildConfig.SERVER_DOMAIN}"

                // Prepare the key first, so a bad import fails before an account exists
                val key = importedKey?.let { PgpIdentity.parseKey(it) } ?: PgpIdentity.generate("xmpp:$jid")
                PgpIdentity.sign(key, PgpIdentity.protector(keyPassphrase), "unlock check".toByteArray())

                val registration = XMPPTCPConnection(configuration(username = null, password = null))
                try {
                    registration.connect()
                    registration.registerAccount(username, password)
                } finally {
                    registration.disconnect()
                }

                store.putString(SecureStore.PGP_SECRET_KEY, PgpIdentity.armoredSecretKey(key))
                keyPassphrase?.let { store.putString(SecureStore.PGP_PASSPHRASE, it) }
                store.putString(SecureStore.ACCOUNT_PASSWORD, password)
                store.putString(SecureStore.ACCOUNT_RESOURCE, "phone-" + UUID.randomUUID().toString().take(8))
                // Written last: its presence marks the account as complete
                store.putString(SecureStore.ACCOUNT_JID, jid)
            }
            connectLocked()
        }
    }

    suspend fun connect() = mutex.withLock { connectLocked() }

    private suspend fun connectLocked() = withContext(Dispatchers.IO) {
        if (connection?.isAuthenticated == true) return@withContext
        _status.value = Status.Connecting
        try {
            val jid = JidCreate.entityBareFrom(store.getString(SecureStore.ACCOUNT_JID))
            val key = PgpIdentity.parseKey(store.getString(SecureStore.PGP_SECRET_KEY)!!)
            pgpKey = key
            pgpPassphrase = store.getString(SecureStore.PGP_PASSPHRASE)
            ownJid = jid.toString()
            ownPgpFingerprint = PgpIdentity.fingerprint(key)

            // A new connection replaces any old one, which must stop reconnecting on its own
            connection?.let { old ->
                connection = null
                ReconnectionManager.getInstanceFor(old).disableAutomaticReconnection()
                runCatching { old.instantShutdown() }
            }

            val conn = XMPPTCPConnection(
                configuration(jid.localpart.toString(), store.getString(SecureStore.ACCOUNT_PASSWORD)!!)
            )
            conn.addConnectionListener(object : ConnectionListener {
                override fun authenticated(connection: XMPPConnection, resumed: Boolean) {
                    _status.value = Status.Online(jid.toString())
                }

                override fun connectionClosedOnError(e: Exception) {
                    _status.value = Status.Failed("connection lost, reconnecting")
                }
            })
            conn.connect()
            conn.login()
            ReconnectionManager.getInstanceFor(conn).enableAutomaticReconnection()
            // Tor circuits can die without either end noticing for many minutes, so ping every minute
            PingManager.getInstanceFor(conn).apply {
                pingInterval = 60
                registerPingFailedListener { scope.launch { resumeAfterPingFailure(conn) } }
            }

            setUpRoster(conn)
            setUpRetractions(conn)
            val manager = setUpOmemo(conn)
            connection = conn
            omemo = manager
            publishIdentity(conn, manager, jid.toString())

            for (contact in database.contacts()) {
                if (!contact.incomingRequest) runCatching { refreshLocked(contact.jid) }
            }
            _status.value = Status.Online(jid.toString())
            changed()
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * A ping went unanswered, so the Tor circuit is probably dead even though the socket looks open.
     * Drop the socket without closing the XMPP stream and reconnect the same connection: the server
     * resumes the session (XEP-0198) and anything it never acknowledged is sent again.
     */
    private suspend fun resumeAfterPingFailure(conn: XMPPTCPConnection) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (connection !== conn) return@withContext
            _status.value = Status.Connecting
            try {
                conn.instantShutdown()
                conn.connect()
                conn.login()
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun configuration(username: String?, password: String?): XMPPTCPConnectionConfiguration {
        val socksPort = (tor.state.value as? TorManager.State.Ready)?.socksPort
            ?: throw IllegalStateException("Tor isn't connected yet")
        val domain = BuildConfig.SERVER_DOMAIN
        val builder = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(domain)
            // Resolved by NoNetworkDnsResolver to a placeholder; the name itself goes to Tor
            .setHost(domain)
            .setPort(5222)
            .setProxyInfo(TorProxy.forSocksPort(socksPort))
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
            .setCustomX509TrustManager(PinnedTrustManager(BuildConfig.SERVER_SPKI_SHA256))
            // The pinned key authenticates the server, so the name check adds nothing
            .setHostnameVerifier { _, _ -> true }
            .setConnectTimeout(90_000)
        if (username != null && password != null) {
            builder.setUsernameAndPassword(username, password)
            builder.setResource(store.getString(SecureStore.ACCOUNT_RESOURCE) ?: "phone")
        }
        val config = builder.build()
        // Fail closed: never start a connection if real DNS could see the server's name
        TorOnlyDns.check()
        return config
    }

    // ---------------------------------------------------------------------------------------------
    // Contacts

    private fun setUpRoster(conn: XMPPTCPConnection) {
        val roster = Roster.getInstanceFor(conn)
        roster.subscriptionMode = Roster.SubscriptionMode.manual
        roster.addSubscribeListener { from, _ -> onSubscribeRequest(from) }
        roster.addRosterListener(object : AbstractRosterListener() {
            override fun entriesAdded(addresses: Collection<Jid>) = refreshInBackground(addresses)
            override fun entriesUpdated(addresses: Collection<Jid>) = refreshInBackground(addresses)
        })
    }

    private fun onSubscribeRequest(from: Jid): SubscribeListener.SubscribeAnswer? {
        if (from.domain.toString() != BuildConfig.SERVER_DOMAIN) return SubscribeListener.SubscribeAnswer.Deny
        val jid = from.asBareJid().toString()
        val existing = database.contact(jid)
        if (existing != null && !existing.incomingRequest) {
            // We added them first: this is their side completing the mutual subscription
            return SubscribeListener.SubscribeAnswer.ApproveAndAlsoRequestIfRequired
        }
        // A new person: leave it pending until the user accepts, so nobody can see this user's
        // online status or send them messages without their approval
        if (existing == null) database.upsertContact(Contact(jid, null, null, verified = false, incomingRequest = true))
        changed()
        return null
    }

    private fun refreshInBackground(addresses: Collection<Jid>) {
        scope.launch {
            for (address in addresses) {
                mutex.withLock { runCatching { refreshLocked(address.asBareJid().toString()) } }
            }
        }
    }

    suspend fun addContact(username: String) = withConnection { conn ->
        val name = username.trim().lowercase()
        require(USERNAME_PATTERN.matches(name)) { "Enter just their username, like alice" }
        val jid = JidCreate.entityBareFrom("$name@${BuildConfig.SERVER_DOMAIN}")
        require(jid.toString() != ownJid) { "That's your own username" }
        require(conn.userExists(name)) { "There's no one called \"$name\" on OBSIDIAN. Check the spelling with them." }
        acceptLocked(conn, jid)
    }

    suspend fun acceptRequest(jid: String) = withConnection { conn -> acceptLocked(conn, JidCreate.entityBareFrom(jid)) }

    private fun acceptLocked(conn: XMPPTCPConnection, jid: BareJid) {
        val existing = database.contact(jid.toString())
        database.upsertContact(
            Contact(jid.toString(), existing?.pgpFingerprint, existing?.pgpCertificate, existing?.verified ?: false, incomingRequest = false)
        )
        if (existing?.incomingRequest == true) {
            conn.sendStanza(conn.stanzaFactory.buildPresenceStanza().ofType(Presence.Type.subscribed).to(jid).build())
        }
        Roster.getInstanceFor(conn).createItemAndRequestSubscription(jid, null, null)
        changed()
        // Their keys only become readable once they accept too, so a failure here is expected
        runCatching { refreshLocked(jid.toString()) }
    }

    suspend fun declineRequest(jid: String) = withConnection { conn ->
        val bareJid = JidCreate.bareFrom(jid)
        conn.sendStanza(conn.stanzaFactory.buildPresenceStanza().ofType(Presence.Type.unsubscribed).to(bareJid).build())
        database.deleteContact(jid)
        changed()
    }

    /**
     * Removes a contact for good: the server-side roster entry goes, which cancels the subscription
     * in both directions, and this phone drops their messages, their keys and the trust we derived
     * from them. They are not told; their app simply stops seeing this account.
     */
    suspend fun deleteContact(jid: String) = withConnection { conn ->
        val roster = Roster.getInstanceFor(conn)
        roster.getEntry(JidCreate.entityBareFrom(jid))?.let { runCatching { roster.removeEntry(it) } }
        trustedDevices.remove(jid)
        database.deleteContact(jid)
        changed()
    }

    suspend fun refreshContact(jid: String) = withConnection { refreshLocked(jid) }

    /** Fetches the contact's published identity and re-derives which of their devices we trust. */
    private fun refreshLocked(jid: String) {
        val conn = connection ?: return
        val identity = fetchIdentity(conn, JidCreate.bareFrom(jid)) ?: return
        val certificate = PgpIdentity.parseCertificate(identity.pgpCertificate)
        val fingerprint = PgpIdentity.fingerprint(certificate)
        val existing = database.contact(jid)
        if (existing?.pgpFingerprint != null && existing.pgpFingerprint != fingerprint) {
            trustedDevices.remove(jid)
            throw SecurityException(
                "${jid.substringBefore('@')} now presents a different PGP key. Check with them in person before continuing."
            )
        }
        val bindings = identity.bindings.filter { binding ->
            PgpIdentity.verify(certificate, PublishedIdentity.bindingStatement(jid, binding.deviceId, binding.omemoFingerprint), binding.signature)
        }
        trustedDevices[jid] = bindings.associate { it.deviceId to PublishedIdentity.normalizeFingerprint(it.omemoFingerprint) }
        database.upsertContact(
            Contact(jid, fingerprint, identity.pgpCertificate, existing?.verified ?: false, existing?.incomingRequest ?: false)
        )
        changed()
    }

    private fun fetchIdentity(conn: XMPPConnection, jid: BareJid): PublishedIdentity? {
        val node = PubSubManager.getInstanceFor(conn, jid).getLeafNode(PublishedIdentity.NODE)
        return PublishedIdentity.fromPayload(node.getItems<PayloadItem<*>>(1).lastOrNull()?.payload)
    }

    fun markVerified(jid: String) {
        database.contact(jid)?.let { database.upsertContact(it.copy(verified = true)) }
        changed()
    }

    fun contacts(): List<Contact> = database.contacts()

    fun contact(jid: String): Contact? = database.contact(jid)

    /** Sealed messages waiting, per contact, for the unread marks in the chat list. */
    fun unreadCounts(): Map<String, Int> = database.unreadByContact()

    fun trustedDeviceCount(jid: String): Int = trustedDevices[jid]?.size ?: 0

    // ---------------------------------------------------------------------------------------------
    // OMEMO and identity

    private val trustCallback = object : OmemoTrustCallback {
        override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState {
            val vouched = trustedDevices[device.jid.toString()]?.get(device.deviceId) ?: return TrustState.untrusted
            return if (vouched == PublishedIdentity.normalizeFingerprint(fingerprint.toString())) TrustState.trusted
            else TrustState.untrusted
        }

        // Trust comes only from PGP bindings, never from manual OMEMO decisions
        override fun setTrust(device: OmemoDevice, fingerprint: OmemoFingerprint, state: TrustState) = Unit
    }

    private val messageListener = object : OmemoMessageListener {
        override fun onOmemoMessageReceived(stanza: Stanza, decryptedMessage: OmemoMessage.Received) {
            if (decryptedMessage.isKeyTransportMessage) return
            val sender = decryptedMessage.senderDevice
            val from = sender.jid.toString()
            val contact = database.contact(from)
            // Only accepted contacts can reach this user
            if (contact == null || contact.incomingRequest) return
            val id = (stanza as? Message)?.let { OriginIdElement.getOriginId(it)?.id } ?: stanza.stanzaId ?: UUID.randomUUID().toString()
            val trusted = trustCallback.getTrust(sender, decryptedMessage.sendersFingerprint) == TrustState.trusted
            database.insertMessage(
                ChatMessage(id, from, outgoing = false, body = decryptedMessage.body, timestamp = System.currentTimeMillis(), opened = false, trusted = trusted)
            )
            // Says only that something arrived: no sender, no preview
            obsidian.chat.Notifications.sealedMessageArrived(context)
            changed()
        }

        // One device per account for now, so there are no carbon copies from our own other devices
        override fun onOmemoCarbonCopyReceived(
            direction: CarbonExtension.Direction,
            carbonCopy: Message,
            wrappingMessage: Message,
            decryptedCarbonCopy: OmemoMessage.Received,
        ) = Unit
    }

    private fun setUpOmemo(conn: XMPPTCPConnection): OmemoManager {
        val deviceId = store.getString(SecureStore.OMEMO_DEVICE_ID)?.toIntOrNull()
            ?: OmemoManager.randomDeviceId().also { store.putString(SecureStore.OMEMO_DEVICE_ID, it.toString()) }
        val manager = OmemoManager.getInstanceFor(conn, deviceId)
        manager.setTrustCallback(trustCallback)
        manager.addOmemoMessageListener(messageListener)
        manager.initialize()
        ownOmemoFingerprint = manager.ownFingerprint.blocksOf8Chars()
        return manager
    }

    /** Publishes our PGP certificate and a PGP signature binding this device's OMEMO key to it. */
    private fun publishIdentity(conn: XMPPTCPConnection, manager: OmemoManager, jid: String) {
        val key = pgpKey ?: return
        val fingerprint = PublishedIdentity.normalizeFingerprint(manager.ownFingerprint.toString())
        val deviceId = manager.deviceId
        val signature = PgpIdentity.sign(
            key, PgpIdentity.protector(pgpPassphrase), PublishedIdentity.bindingStatement(jid, deviceId, fingerprint),
        )
        val identity = PublishedIdentity(PgpIdentity.armoredPublicKey(key), listOf(DeviceBinding(deviceId, fingerprint, signature)))
        PepManager.getInstanceFor(conn).publish(PublishedIdentity.NODE, PayloadItem(identity.toElement()))
    }

    // ---------------------------------------------------------------------------------------------
    // Messages

    fun messages(jid: String): List<ChatMessage> = database.messages(jid)

    suspend fun send(jid: String, text: String) = sendBody(jid, text)

    /**
     * Encrypts [jpeg] with a one-time key, uploads only the ciphertext to our server over Tor, and
     * sends the link with that key inside the OMEMO-encrypted message (XEP-0454). The server stores
     * an encrypted blob it cannot read, and deletes it after a week.
     */
    suspend fun sendPhoto(jid: String, jpeg: ByteArray) {
        val conn = connection?.takeIf { it.isAuthenticated }
            ?: throw IllegalStateException("Not connected to the server")
        val link = withContext(Dispatchers.IO) {
            val sealed = EncryptedMedia.encrypt(jpeg)
            val slot = HttpFileUploadManager.getInstanceFor(conn).requestSlot(
                "${UUID.randomUUID()}.enc",
                sealed.ciphertext.size.toLong(),
                "application/octet-stream",
            )
            TorHttp.upload(socksPort(), slot.putUrl.toString(), slot.headers, sealed.ciphertext)
            EncryptedMedia.link(slot.getUrl.toString(), sealed)
        }
        sendBody(jid, link)
    }

    /** Fetches a shared photo over Tor and decrypts it in memory; it never reaches this phone's disk. */
    suspend fun loadPhoto(message: ChatMessage): ByteArray = withContext(Dispatchers.IO) {
        val link = EncryptedMedia.parse(message.body) ?: throw IllegalArgumentException("That message isn't a photo")
        EncryptedMedia.decrypt(TorHttp.download(socksPort(), link.downloadUrl), link.key, link.iv)
    }

    private fun socksPort(): Int = (tor.state.value as? TorManager.State.Ready)?.socksPort
        ?: throw IllegalStateException("Tor isn't connected yet")

    private suspend fun sendBody(jid: String, body: String) = withConnection { conn ->
        val manager = omemo ?: throw IllegalStateException("Not connected to the server")
        val recipient = JidCreate.entityBareFrom(jid)
        if (trustedDevices[jid].isNullOrEmpty()) refreshLocked(jid)
        val sent = manager.encrypt(recipient, body)
        val reachable = sent.intendedDevices.filter { it.jid == recipient && it !in sent.skippedDevices.keys }
        if (reachable.isEmpty()) {
            throw IllegalStateException("None of this contact's devices is vouched for by their PGP key yet, so nothing was sent")
        }
        val builder = conn.stanzaFactory.buildMessageStanza()
        val originId = OriginIdElement.addTo(builder)
        conn.sendStanza(sent.buildMessage(builder, recipient))
        database.insertMessage(
            ChatMessage(originId.id, jid, outgoing = true, body = body, timestamp = System.currentTimeMillis(), opened = true, trusted = true)
        )
        changed()
    }

    fun open(message: ChatMessage) {
        database.markOpened(message.id)
        changed()
    }

    /** Deletes the message here and asks the contact's app to delete its copy (XEP-0424). */
    suspend fun delete(message: ChatMessage) {
        database.deleteMessage(message.contact, message.id)
        changed()
        withConnection { conn ->
            val builder = conn.stanzaFactory.buildMessageStanza().to(JidCreate.entityBareFrom(message.contact))
            MessageRetractionManager.addRetractionElementToMessage(OriginIdElement(message.id), builder)
            conn.sendStanza(builder.build())
        }
    }

    private fun setUpRetractions(conn: XMPPTCPConnection) {
        MessageRetractionManager.getInstanceFor(conn).announceSupport()
        conn.addAsyncStanzaListener({ stanza ->
            val message = stanza as Message
            val fastening = message.getExtensionElement(FasteningElement.ELEMENT, FasteningElement.NAMESPACE) as? FasteningElement
            if (fastening != null && fastening.wrappedPayloads.any { it is RetractElement }) {
                val from = message.from?.asBareJid()?.toString() ?: return@addAsyncStanzaListener
                if (database.deleteMessage(from, fastening.referencedStanzasOriginId.id)) changed()
            }
        }, StanzaTypeFilter.MESSAGE)
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun <T> withConnection(block: (XMPPTCPConnection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val conn = connection?.takeIf { it.isAuthenticated } ?: throw IllegalStateException("Not connected to the server")
            block(conn)
        }
    }}
