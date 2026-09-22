package obsidian.chat.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.torproject.jni.TorService

/** Runs the embedded Tor daemon. The app sends all of its network traffic through its SOCKS port. */
class TorManager(private val context: Context) {
    sealed interface State {
        /** Tor's own bootstrap [progress] in percent and what it is doing, once it reports them. */
        data class Starting(val progress: Int = 0, val summary: String? = null) : State
        data class Ready(val socksPort: Int) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting())
    val state: StateFlow<State> = _state.asStateFlow()

    private var service: TorService? = null
    private var lastStatus: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastRetryAt = 0L
    private var lastProgress = -1
    private var lastProgressAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TorService.LocalBinder).service
            publish(null)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.value = State.Starting()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TorService.ACTION_STATUS -> publish(intent.getStringExtra(TorService.EXTRA_STATUS))
                TorService.ACTION_ERROR ->
                    _state.value = State.Failed(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor failed to start")
            }
        }
    }

    private fun publish(status: String?) {
        if (status != null) lastStatus = status
        val port = service?.socksPort ?: 0
        when {
            // TorService reports ON once its first circuit is built, not merely when tor is running
            lastStatus == TorService.STATUS_ON && port > 0 -> _state.value = State.Ready(port)
            _state.value !is State.Failed && _state.value !is State.Starting -> _state.value = State.Starting()
        }
    }

    /** Reads Tor's bootstrap phase every two seconds while it starts, so the UI can show progress. */
    private fun watchBootstrap() = scope.launch {
        while (isActive) {
            val current = _state.value
            if (current is State.Starting) {
                val phase = runCatching { service?.getInfo("status/bootstrap-phase") }.getOrNull()
                parseBootstrapPhase(phase)?.let { next ->
                    // Remember when the number last moved, so a stall can be told from slow progress
                    if (next.progress != lastProgress) {
                        lastProgress = next.progress
                        lastProgressAt = SystemClock.elapsedRealtime()
                        Log.i(TAG, "bootstrap ${next.progress}%: ${next.summary ?: ""}")
                    }
                    _state.compareAndSet(current, next)
                }
                // A bootstrap can also stall while the network stays up, and then no network callback
                // ever arrives to prompt a retry. Check for that here too, but only after a full minute
                // without progress, so a slow first connection is left to finish on its own.
                val now = SystemClock.elapsedRealtime()
                if (shouldRetryForStall(_state.value, now - lastRetryAt, now - lastProgressAt)) {
                    Log.i(TAG, "bootstrap stuck at $lastProgress% for ${(now - lastProgressAt) / 1000} s; asking Tor to start again")
                    lastRetryAt = now
                    retryBootstrap()
                }
            }
            delay(2_000)
        }
    }

    /**
     * Tor started before the phone had a network gets stuck: it sits at 0%, or reaches a phase such
     * as loading the consensus and stays there, until something prods it. That is easy to hit on a
     * new phone, where the app is opened before joining wi-fi. So watch for a network arriving and
     * ask the running daemon to bootstrap again.
     */
    private fun watchNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkUsable()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) onNetworkUsable()
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    private fun onNetworkUsable() {
        val now = SystemClock.elapsedRealtime()
        if (!shouldRetryForNetwork(_state.value, now - lastRetryAt, now - lastProgressAt)) return
        Log.i(TAG, "network available while bootstrap is stuck at $lastProgress%; asking Tor to start again")
        lastRetryAt = now
        scope.launch { retryBootstrap() }
    }

    /**
     * Asks the running Tor to start its bootstrap over, using the network that just arrived.
     *
     * The daemon must never be stopped and started again to achieve this. Tor keeps state in
     * globals inside its native library and deliberately aborts the whole process if it is
     * initialised a second time, so re-binding the service crashes the app rather than recovering
     * it. Toggling DisableNetwork over the control port gets the same result from inside the
     * daemon that is already running.
     */
    private fun retryBootstrap() {
        val control = service?.torControlConnection
        if (control == null) {
            Log.w(TAG, "no control connection yet; retry skipped")
            return
        }
        runCatching {
            control.setConf("DisableNetwork", "1")
            control.setConf("DisableNetwork", "0")
        }.onFailure { Log.w(TAG, "retry failed", it) }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(context, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Android delivers a network callback the moment we register, so start both clocks here;
        // otherwise the first bootstrap is interrupted before it has had a chance to get anywhere.
        val now = SystemClock.elapsedRealtime()
        lastRetryAt = now
        lastProgressAt = now
        context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        watchBootstrap()
        watchNetwork()
    }

    companion object {
        private val PROGRESS = Regex("PROGRESS=(\\d+)")
        private val SUMMARY = Regex("SUMMARY=\"([^\"]*)\"")
        private val WARNING = Regex("WARNING=\"([^\"]*)\"")

        /** Long enough that a network flapping on and off cannot put Tor in a retry loop. */
        const val RETRY_BACKOFF_MS = 20_000L

        /** How long bootstrap must sit at the same percentage before we treat it as stuck. */
        const val STALL_MS = 20_000L

        /**
         * With no network change to go on, wait longer before calling a bootstrap stuck, and retry at most
         * this often: a first connection on a new phone downloads the whole network directory.
         */
        const val STALL_WITHOUT_NETWORK_MS = 60_000L

        private const val TAG = "ObsidianTor"

        /** Prod Tor when bootstrap has made no progress for a minute and was not prodded in that minute. */
        fun shouldRetryForStall(state: State, millisSinceLastRetry: Long, millisSinceProgress: Long): Boolean =
            state is State.Starting &&
                millisSinceLastRetry >= STALL_WITHOUT_NETWORK_MS &&
                millisSinceProgress >= STALL_WITHOUT_NETWORK_MS

        /**
         * Prod Tor when a network appears, unless it is already connected, we prodded it a moment
         * ago, or it is still making progress on its own. Android delivers these callbacks in
         * bursts, several per network change, and sends one immediately on registering.
         */
        fun shouldRetryForNetwork(
            state: State,
            millisSinceLastRetry: Long,
            millisSinceProgress: Long,
        ): Boolean =
            state !is State.Ready &&
                millisSinceLastRetry >= RETRY_BACKOFF_MS &&
                millisSinceProgress >= STALL_MS

        /**
         * Parses a reply such as `NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY="Loading
         * relay descriptors"`. A WARN reply carries the problem, e.g. `WARNING="Connection refused"`.
         */
        fun parseBootstrapPhase(phase: String?): State.Starting? {
            if (phase == null) return null
            val progress = PROGRESS.find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val summary = SUMMARY.find(phase)?.groupValues?.get(1)
            val warning = WARNING.find(phase)?.groupValues?.get(1)
            return State.Starting(progress, if (warning != null) "$summary (problem: $warning)" else summary)
        }
    }
}
