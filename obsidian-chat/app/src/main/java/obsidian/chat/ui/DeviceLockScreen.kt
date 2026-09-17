package obsidian.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import obsidian.chat.security.DeviceLock

/** Tracks whether the phone has a screen lock, rechecking each time the app comes back. */
@Composable
fun rememberPhoneIsLocked(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var secure by remember { mutableStateOf(DeviceLock.isSet(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) secure = DeviceLock.isSet(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return secure
}

/** Shown instead of the app until the phone itself has a lock screen. */
@Composable
fun DeviceLockRequiredScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OBSIDIAN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, letterSpacing = 10.sp)
        Text("Set a PIN for this phone", style = MaterialTheme.typography.titleMedium)
        Text(
            "This phone has no lock screen. Until it does, anyone who picks it up can read your " +
                "messages, and the phone's encryption protects nothing while it is switched on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { context.startActivity(DeviceLock.setLockIntent()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Set a phone PIN") }
    }
}
