package obsidian.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import obsidian.chat.tor.TorManager
import obsidian.chat.xmpp.ChatClient

@Composable
fun ObsidianChatApp(vm: AppViewModel = viewModel()) {
    val tor by vm.torState.collectAsState()
    val status by vm.status.collectAsState()

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    // Sign in automatically once Tor is up and an account exists on this phone
    LaunchedEffect(tor) {
        if (tor is TorManager.State.Ready && vm.client.hasAccount && vm.status.value is ChatClient.Status.SignedOut) {
            vm.connect()
        }
    }

    LockOnBackground(vm)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Nothing is shown until the phone itself has a lock screen
        if (!rememberPhoneIsLocked()) {
            DeviceLockRequiredScreen()
            return@Surface
        }
        if (vm.locked) {
            LockScreen(vm)
            return@Surface
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ConnectionBar(tor, status, onRetry = { vm.connect() })
            vm.error?.let { ErrorBanner(it.withoutServerName(), onDismiss = { vm.error = null }) }
            if (!vm.client.hasAccount) {
                SetupScreen(vm, torReady = tor is TorManager.State.Ready)
            } else {
                when (val screen = vm.screen) {
                    Screen.Contacts -> ContactsScreen(vm)
                    is Screen.Chat -> ChatScreen(vm, screen.jid)
                    Screen.MyIdentity -> MyIdentityScreen(vm)
                    is Screen.ContactIdentity -> ContactIdentityScreen(vm, screen.jid)
                    Screen.Security -> SecurityScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun ConnectionBar(tor: TorManager.State, status: ChatClient.Status, onRetry: () -> Unit) {
    val torText = when (tor) {
        is TorManager.State.Starting -> "Connecting to Tor… ${tor.progress}%" + (tor.summary?.let { " · $it" } ?: "")
        is TorManager.State.Ready -> "Tor connected"
        is TorManager.State.Failed -> "Tor failed: ${tor.reason}"
    }
    val chatText = when (status) {
        ChatClient.Status.SignedOut -> ""
        ChatClient.Status.Connecting -> " · connecting to server…"
        is ChatClient.Status.Online -> " · online"
        is ChatClient.Status.Failed -> " · offline: ${status.message.withoutServerName()}"
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            torText + chatText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (status is ChatClient.Status.Failed) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
