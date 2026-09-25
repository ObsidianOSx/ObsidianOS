package obsidian.chat.update

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemProperties
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import obsidian.chat.BuildConfig
import obsidian.chat.Notifications
import obsidian.chat.tor.TorManager
import obsidian.chat.security.PanicDeviceAdmin

/**
 * Keeping the phone up to date.
 *
 * Android security fixes arrive monthly, and a phone that cannot take them quietly rots. Until
 * this existed the only way to move a phone forward was to erase it and start again, which nobody
 * will do often enough.
 *
 * What the phone trusts, in order: the update information must carry a signature from the OBSIDIAN
 * release key, the package must match the size and checksum that signed information gives, and
 * update_engine must accept the package's own signature before it writes anything. Three separate
 * checks, none of which involve trusting the server, the network, or Tor.
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Unknown : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val version: String, val bytes: Long, val small: Boolean) : State
        data class Downloading(val version: String, val percent: Int) : State
        data class Installing(val version: String, val percent: Int, val phase: String) : State
        data class ReadyToRestart(val version: String) : State
        data class Failed(val message: String) : State
        /** This build has no update certificate, so it cannot tell a genuine update from any other. */
        data object NotConfigured : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    val installedVersion: String get() = SystemProperties.get(VERSION_PROPERTY, "").ifBlank { "unknown" }
    private val device: String get() = SystemProperties.get(DEVICE_PROPERTY, "")

    private var pending: Pair<UpdateMetadata, File>? = null

    /**
     * Looks for updates on its own, so a phone does not sit on an old system because nobody opened
     * the right screen. It waits for Tor, checks, then checks again twice a day, and tells the
     * person once per version rather than nagging.
     *
     * Nothing is downloaded or installed without being asked. The check itself is one small request
     * over Tor, carrying nothing that identifies the phone.
     */
    fun watchForUpdates(torState: StateFlow<TorManager.State>) {
        scope.launch {
            var announced = ""
            while (true) {
                val ready = torState.first { it is TorManager.State.Ready } as TorManager.State.Ready
                check(ready.socksPort)
                // Let the check finish before deciding whether to say anything about it.
                val settled = state.first { it !is State.Checking }
                if (settled is State.Available && settled.version != announced) {
                    announced = settled.version
                    Notifications.updateAvailable(context, settled.version)
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    fun check(socksPort: Int) {
        if (BuildConfig.UPDATE_CERT_PEM.isBlank()) {
            _state.value = State.NotConfigured
            return
        }
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        scope.launch(Dispatchers.IO) {
            _state.value = State.Checking
            runCatching { fetchMetadata(socksPort) }
                .onSuccess { metadata ->
                    val installed = installedVersion
                    _state.value = when {
                        metadata.version == installed -> State.UpToDate(installed)
                        !isNewer(metadata.version, installed) -> State.UpToDate(installed)
                        else -> {
                            val pkg = metadata.packageFor(installed)
                            State.Available(metadata.version, pkg.size, small = pkg !== metadata.full)
                        }
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "update check failed", error)
                    _state.value = State.Failed(error.message ?: "The update check did not finish")
                }
        }
    }

    fun downloadAndInstall(socksPort: Int) {
        val available = _state.value as? State.Available ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val metadata = fetchMetadata(socksPort)
                val pkg = metadata.packageFor(installedVersion)
                val destination = File(updatesDir(), "obsidian-${metadata.version}.zip")
                _state.value = State.Downloading(metadata.version, 0)
                var lastShown = 0
                UpdateDownloader(socksPort).download(pkg.file, pkg.size, pkg.sha256, destination) { got, total ->
                    val percent = ((got * 100) / total).toInt()
                    if (percent != lastShown) {
                        lastShown = percent
                        _state.value = State.Downloading(metadata.version, percent)
                    }
                }
                pending = metadata to destination
                _state.value = State.Installing(metadata.version, 0, "Preparing")
                SystemUpdater().apply(destination) { progress ->
                    _state.value = when (progress) {
                        is SystemUpdater.Progress.Working ->
                            State.Installing(metadata.version, progress.percent, progress.phase)
                        SystemUpdater.Progress.NeedsRestart -> State.ReadyToRestart(metadata.version)
                    }
                }
                // The package has been written into the spare half of the phone and is no longer
                // needed. Two gigabytes is worth reclaiming on a phone this small.
                destination.delete()
                _state.value = State.ReadyToRestart(metadata.version)
            }.onFailure { error ->
                Log.w(TAG, "update failed", error)
                pending?.second?.delete()
                pending = null
                _state.value = State.Failed(error.message ?: "The update did not finish")
            }
        }
    }

    /** Restarts into the update. OBSIDIAN owns the phone, so it can do this without asking Android. */
    fun restartNow() {
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, PanicDeviceAdmin::class.java)
        runCatching { policy.reboot(admin) }
            .onFailure { _state.value = State.Failed("Restart the phone yourself to finish the update.") }
    }

    private suspend fun fetchMetadata(socksPort: Int): UpdateMetadata = withContext(Dispatchers.IO) {
        require(device.isNotBlank()) { "This phone does not say which model it is, so it cannot be updated" }
        val downloader = UpdateDownloader(socksPort)
        val json = downloader.fetchBytes("updates/$device.json")
        val signature = downloader.fetchBytes("updates/$device.json.sig", limit = 8 * 1024)
        val metadata = UpdateMetadata.verifyAndParse(json, signature, BuildConfig.UPDATE_CERT_PEM)
        require(metadata.device == device) { "That update is for a different phone" }
        metadata
    }

    /**
     * Versions are dates, so newer sorts later. A phone never accepts an older release than the one
     * it runs: that is how an attacker with a copy of an old, signed update would try to put a
     * known hole back on a phone.
     */
    private fun isNewer(offered: String, installed: String): Boolean {
        if (installed == "unknown" || installed.isBlank()) return true
        return offered.compareTo(installed) > 0
    }

    private fun updatesDir(): File = File(context.filesDir, "updates").apply { mkdirs() }

    private companion object {
        const val TAG = "ObsidianUpdate"
        const val VERSION_PROPERTY = "ro.obsidian.version"
        const val DEVICE_PROPERTY = "ro.obsidian.device"
        // Twice a day. Often enough that a security fix is picked up within hours of publishing,
        // rare enough to be nothing on the battery or the Tor circuit.
        const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000
    }
}
