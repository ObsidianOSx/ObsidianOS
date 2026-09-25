package obsidian.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import obsidian.chat.BuildConfig
import obsidian.chat.crypto.PgpIdentity
import obsidian.chat.security.KeyBox
import obsidian.chat.tor.TorManager
import obsidian.chat.update.UpdateManager
import obsidian.chat.xmpp.ChatClient
import obsidian.chat.xmpp.TorOnlyDns

/** Live status of each protection, so a user (or a demo) can see Tor and OMEMO actually working. */
@Composable
fun SecurityScreen(vm: AppViewModel) {
    val tor by vm.torState.collectAsState()
    val status by vm.status.collectAsState()
    val tick by vm.changes.collectAsState()
    val client = vm.client
    val contacts = remember(tick) { client.contacts().filter { !it.incomingRequest } }
    val keyStorage = remember { KeyBox.securityLevel() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = { vm.back() }) { Text("‹ Back") }
        Text("Security status", style = MaterialTheme.typography.titleLarge)

        val torNow = tor
        StatusItem(
            title = "Tor",
            ok = torNow is TorManager.State.Ready,
            lines = when (torNow) {
                is TorManager.State.Ready -> listOf(
                    "Running inside this app (Tor ${BuildConfig.TOR_VERSION})",
                    "All chat traffic goes through its SOCKS proxy on 127.0.0.1:${torNow.socksPort}",
                )
                is TorManager.State.Starting -> listOfNotNull(
                    "Connecting to the Tor network: ${torNow.progress}%",
                    torNow.summary,
                )
                is TorManager.State.Failed -> listOf("Failed: ${torNow.reason}")
            },
        )

        UpdateItem(vm)

        val statusNow = status
        StatusItem(
            title = "Server connection",
            ok = statusNow is ChatClient.Status.Online,
            lines = listOf(
                when (statusNow) {
                    is ChatClient.Status.Online -> "Online as ${statusNow.jid.substringBefore('@')}"
                    ChatClient.Status.Connecting -> "Connecting…"
                    is ChatClient.Status.Failed -> "Offline: ${statusNow.message}"
                    ChatClient.Status.SignedOut -> "Signed out"
                },
                // The server's address is deliberately never shown, on screen or in errors
                "Reached only as a Tor onion service, so the server never learns this phone's IP address",
                "The server's own key is pinned, so nothing can stand in the middle",
                if (TorOnlyDns.isInstalled()) "No DNS lookups: the server's name only ever goes to Tor"
                else "WARNING: a network DNS resolver is active, so the app will refuse to connect",
            ),
        )

        StatusItem(
            title = "OMEMO end-to-end encryption",
            ok = client.ownOmemoFingerprint != null,
            lines = listOfNotNull(
                "Signal protocol (Smack ${BuildConfig.SMACK_VERSION}). The server only ever sees encrypted messages.",
                client.ownOmemoDeviceId?.let { "This phone is OMEMO device $it" },
                client.ownOmemoFingerprint?.let { "Device key $it" },
                client.ownPgpFingerprint?.let { "Signed by your PGP key ${PgpIdentity.formatFingerprint(it)}" },
            ),
        )

        StatusItem(
            title = "Contacts",
            ok = contacts.isNotEmpty() && contacts.all { client.trustedDeviceCount(it.jid) > 0 },
            lines = if (contacts.isEmpty()) listOf("No contacts yet") else contacts.map { contact ->
                val devices = client.trustedDeviceCount(contact.jid)
                val check = if (contact.verified) "PGP key verified in person" else "PGP key not verified in person yet"
                "${contact.jid.substringBefore('@')}: $check, $devices OMEMO device${if (devices == 1) "" else "s"} vouched for by it"
            },
        )

        StatusItem(
            title = "Location",
            ok = vm.locationOff,
            lines = if (vm.locationOff) listOf(
                "Switched off, and this phone won't let it be switched back on",
                "No app on this phone can ask where you are",
            ) else listOf(
                "WARNING: location is on. On an OBSIDIAN phone it is held off by policy; this phone " +
                    "is not set up that way.",
            ),
        )

        StatusItem(
            title = "On this phone",
            ok = true,
            lines = listOf(
                "Messages and contacts are kept in an encrypted database (SQLCipher)",
                "Its key and your PGP key are sealed by an Android Keystore key held in $keyStorage",
                "Other apps can't screenshot or record this app's screen",
            ),
        )

        PinSettings(vm)
    }
}

/**
 * The operating system's own updates. Android security fixes come out monthly, and a phone that
 * cannot take them stops being safe to carry, so this sits beside the other things that protect it
 * rather than being buried in a settings menu.
 */
@Composable
private fun UpdateItem(vm: AppViewModel) {
    val update by vm.updateState.collectAsState()
    val tor by vm.torState.collectAsState()
    val ready = tor is TorManager.State.Ready
    val now = update

    // Ask once when the screen is first opened and Tor is up, so the answer is usually already there.
    LaunchedEffect(ready) { if (ready) vm.checkForUpdate() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusItem(
            title = "Operating system",
            ok = now is UpdateManager.State.UpToDate || now is UpdateManager.State.ReadyToRestart,
            lines = buildList {
                add("OBSIDIAN ${vm.installedVersion}")
                when (now) {
                    UpdateManager.State.Unknown -> add(if (ready) "Checking for updates" else "Waiting for Tor")
                    UpdateManager.State.Checking -> add("Checking for updates")
                    UpdateManager.State.NotConfigured ->
                        add("This is a test build, so it cannot receive updates. Install a new release by hand.")
                    is UpdateManager.State.UpToDate -> add("Up to date")
                    is UpdateManager.State.Available -> {
                        add("Version ${now.version} is available, ${bytesInGb(now.bytes)}")
                        if (now.small) add("A small update, because it only carries what changed")
                        add("Downloaded over Tor and checked against the OBSIDIAN signing key before it is installed")
                    }
                    is UpdateManager.State.Downloading -> add("Downloading ${now.version} over Tor: ${now.percent}%")
                    is UpdateManager.State.Installing ->
                        add("${now.phase}: ${now.percent}%. You can keep using the phone.")
                    is UpdateManager.State.ReadyToRestart ->
                        add("Version ${now.version} is installed and starts when the phone restarts")
                    is UpdateManager.State.Failed -> add(now.message)
                }
            },
        )
        when (now) {
            is UpdateManager.State.Available ->
                TextButton(onClick = { vm.installUpdate() }, enabled = ready) { Text("Download and install") }
            is UpdateManager.State.ReadyToRestart ->
                TextButton(onClick = { vm.restartForUpdate() }) { Text("Restart now") }
            is UpdateManager.State.Failed ->
                TextButton(onClick = { vm.checkForUpdate() }, enabled = ready) { Text("Try again") }
            is UpdateManager.State.UpToDate ->
                TextButton(onClick = { vm.checkForUpdate() }, enabled = ready) { Text("Check again") }
            else -> Unit
        }
    }
}

private fun bytesInGb(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

@Composable
private fun StatusItem(title: String, ok: Boolean, lines: List<String>, monospaceLine: Int = -1) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "●" else "○", color = if (ok) Color(0xFF6FCF97) else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        lines.forEachIndexed { index, line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (index == monospaceLine) FontFamily.Monospace else null,
            )
        }
    }
}
