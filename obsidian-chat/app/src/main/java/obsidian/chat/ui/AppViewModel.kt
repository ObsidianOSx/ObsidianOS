package obsidian.chat.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import obsidian.chat.ObsidianApp
import obsidian.chat.data.ChatMessage
import obsidian.chat.data.Contact

sealed interface Screen {
    data object Contacts : Screen
    data class Chat(val jid: String) : Screen
    data object MyIdentity : Screen
    data class ContactIdentity(val jid: String) : Screen
    data object Security : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ObsidianApp
    val client = app.client
    val torState = app.tor.state
    val status = client.status
    val changes = client.changes
    private val store = app.secureStore

    /** Operating system updates, which go over the same Tor connection the chat uses. */
    private val updates = obsidian.chat.update.UpdateManager(app, viewModelScope)
    val updateState = updates.state
    val installedVersion: String get() = updates.installedVersion

    private fun socksPortOrNull(): Int? =
        (torState.value as? obsidian.chat.tor.TorManager.State.Ready)?.socksPort

    fun checkForUpdate() { socksPortOrNull()?.let(updates::check) }
    fun installUpdate() { socksPortOrNull()?.let(updates::downloadAndInstall) }
    fun restartForUpdate() = updates.restartNow()

    /** With a PIN set, the app locks itself whenever it leaves the foreground. */
    var locked by mutableStateOf(obsidian.chat.security.AppLock.isSet(app.secureStore))
        private set
    var hasPin by mutableStateOf(obsidian.chat.security.AppLock.isSet(app.secureStore))
        private set

    /** True when the phone reports location switched off, which OBSIDIAN enforces. */
    val locationOff: Boolean get() = obsidian.chat.security.DeviceLock.locationIsOff(app)

    fun lock() {
        if (hasPin) locked = true
    }

    /** True while a PIN is being checked: the hash is slow on purpose, so the screen must say so. */
    var checkingPin by mutableStateOf(false)
        private set

    /** Reports the result through [onResult] once the hash finishes off the main thread. */
    fun unlock(pin: String, onResult: (Boolean) -> Unit) {
        if (checkingPin) return
        viewModelScope.launch {
            checkingPin = true
            val ok = try {
                obsidian.chat.security.AppLock.verify(store, pin)
            } finally {
                checkingPin = false
            }
            if (ok) locked = false
            onResult(ok)
        }
    }

    private suspend fun setPin(pin: String) {
        obsidian.chat.security.AppLock.setPin(store, pin)
        hasPin = true
    }

    /**
     * Changing the PIN runs the same deliberately-slow hash as unlocking, so it also has to stay
     * off the main thread. [onDone] fires once it is stored, so the dialog can close then and not
     * before - closing early would leave the work running behind a screen that looks finished.
     */
    fun changePin(pin: String, onDone: () -> Unit) {
        if (checkingPin) return
        viewModelScope.launch {
            checkingPin = true
            try {
                setPin(pin)
            } finally {
                checkingPin = false
            }
            onDone()
        }
    }

    fun removePin() {
        obsidian.chat.security.AppLock.clear(store)
        hasPin = false
        locked = false
    }

    var backStack by mutableStateOf(listOf<Screen>(Screen.Contacts))
        private set
    val screen: Screen get() = backStack.last()

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun navigate(screen: Screen) {
        backStack = backStack + screen
    }

    fun back() {
        if (backStack.size > 1) backStack = backStack.dropLast(1)
    }

    private fun launchAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    /** The screen lock PIN is part of setting up, so an account never exists without one. */
    fun createAccount(username: String, password: String, pin: String, importedKey: String?, keyPassphrase: String?) =
        launchAction {
            client.createAccount(username, password, importedKey, keyPassphrase)
            setPin(pin)
        }

    fun connect() = launchAction { client.connect() }

    fun eraseAccount() = launchAction { client.eraseAccount() }

    /** Straight to the wipe: no network, no waiting, no confirmation. */
    fun panicWipe() = client.panicWipe()

    fun addContact(username: String) = launchAction { client.addContact(username) }

    fun acceptRequest(jid: String) = launchAction { client.acceptRequest(jid) }

    fun declineRequest(jid: String) = launchAction { client.declineRequest(jid) }

    fun refreshContact(jid: String) = launchAction { client.refreshContact(jid) }

    /** Removes the contact, then returns to the chat list since their screens no longer exist. */
    fun deleteContact(jid: String) = launchAction {
        client.deleteContact(jid)
        backStack = listOf(Screen.Contacts)
    }

    fun send(jid: String, text: String) = launchAction { client.send(jid, text) }

    fun sendPhoto(jid: String, jpeg: ByteArray) = launchAction { client.sendPhoto(jid, jpeg) }

    suspend fun loadPhoto(message: ChatMessage): ByteArray = client.loadPhoto(message)

    fun delete(message: ChatMessage) = launchAction { client.delete(message) }

    fun open(message: ChatMessage) = client.open(message)

    fun markVerified(jid: String) = client.markVerified(jid)

    fun contacts(): List<Contact> = client.contacts()

    fun unreadCounts(): Map<String, Int> = client.unreadCounts()

    fun messages(jid: String): List<ChatMessage> = client.messages(jid)
}
