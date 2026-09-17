package obsidian.chat.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
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
                parseBootstrapPhase(phase)?.let { _state.compareAndSet(current, it) }
            }
            delay(2_000)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(context, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        watchBootstrap()
    }

    companion object {
        private val PROGRESS = Regex("PROGRESS=(\\d+)")
        private val SUMMARY = Regex("SUMMARY=\"([^\"]*)\"")
        private val WARNING = Regex("WARNING=\"([^\"]*)\"")

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
