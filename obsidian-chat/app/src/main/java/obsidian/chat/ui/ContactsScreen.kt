package obsidian.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import obsidian.chat.data.Contact

@Composable
fun ContactsScreen(vm: AppViewModel) {
    val tick by vm.changes.collectAsState()
    val contacts = remember(tick) { vm.contacts() }
    val unread = remember(tick) { vm.unreadCounts() }
    var adding by remember { mutableStateOf(false) }

    // Android 13 and later show nothing without this; asked for once, on first sight of the list
    val askForNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}
    androidx.compose.runtime.LaunchedEffect(Unit) {
        askForNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.navigate(Screen.Security) }) { Text("Security") }
            TextButton(onClick = { vm.navigate(Screen.MyIdentity) }) { Text("My key") }
        }
        if (contacts.isEmpty()) {
            Text(
                "No contacts yet. Add someone by their username.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(contacts, key = { it.jid }) { contact ->
                val unreadHere = unread[contact.jid] ?: 0
                ListItem(
                    headlineContent = { Text(contact.jid.substringBefore('@')) },
                    supportingContent = {
                        if (unreadHere > 0) {
                            Text(
                                if (unreadHere == 1) "1 new sealed message" else "$unreadHere new sealed messages",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(trustLabel(contact))
                        }
                    },
                    trailingContent = if (contact.incomingRequest) {
                        {
                            Row {
                                TextButton(onClick = { vm.declineRequest(contact.jid) }) { Text("Decline") }
                                TextButton(onClick = { vm.acceptRequest(contact.jid) }) { Text("Accept") }
                            }
                        }
                    } else if (unreadHere > 0) {
                        { Text("$unreadHere", color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = if (contact.incomingRequest) Modifier else Modifier.clickable { vm.navigate(Screen.Chat(contact.jid)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Add contact") }
        HoldToWipeButton(onWipe = { vm.panicWipe() })
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add contact") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().trim() },
                    label = { Text("Their username") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.addContact(name); adding = false }, enabled = name.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

private fun trustLabel(contact: Contact): String = when {
    contact.incomingRequest -> "Wants to add you"
    contact.verified -> "Verified in person"
    contact.pgpFingerprint != null -> "Keys received · not verified yet"
    else -> "Waiting for their keys"
}
