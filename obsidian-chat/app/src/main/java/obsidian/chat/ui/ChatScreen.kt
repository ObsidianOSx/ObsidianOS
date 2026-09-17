package obsidian.chat.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import obsidian.chat.data.ChatMessage
import obsidian.chat.media.EncryptedMedia
import obsidian.chat.media.PhotoScrubber
import obsidian.chat.xmpp.ChatClient

@Composable
fun ChatScreen(vm: AppViewModel, jid: String) {
    val tick by vm.changes.collectAsState()
    val status by vm.status.collectAsState()
    val messages = remember(tick, jid) { vm.messages(jid) }
    val contact = remember(tick, jid) { vm.client.contact(jid) }
    var draft by remember { mutableStateOf("") }
    var photoToSend by remember { mutableStateOf<PhotoScrubber.Photo?>(null) }
    var preparingPhoto by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online = status is ChatClient.Status.Online

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                preparingPhoto = true
                // Strip the metadata off the main thread; large photos take a moment
                photoToSend = runCatching { withContext(Dispatchers.Default) { PhotoScrubber.scrub(context, uri) } }
                    .onFailure { vm.error = it.message ?: "Could not read that photo" }
                    .getOrNull()
                preparingPhoto = false
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("‹ Back") }
            Column(Modifier.weight(1f)) {
                Text(jid.substringBefore('@'), style = MaterialTheme.typography.titleMedium)
                Text(
                    "OMEMO end-to-end encrypted · via Tor",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { vm.navigate(Screen.ContactIdentity(jid)) }) {
                Text(if (contact?.verified == true) "Verified" else "Verify")
            }
        }
        if (contact?.verified != true) {
            Text(
                "Compare PGP fingerprints with this contact in person before sharing anything sensitive.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(vm, message, onOpen = { vm.open(message) }, onDelete = { vm.delete(message) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = online && !vm.busy && !preparingPhoto,
            ) { Text(if (preparingPhoto) "Preparing…" else "Photo") }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Encrypted message") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.send(jid, draft.trim())
                    draft = ""
                },
                enabled = draft.isNotBlank() && online && !vm.busy,
            ) { Text("Send") }
        }
    }

    photoToSend?.let { photo ->
        AlertDialog(
            onDismissRequest = { photoToSend = null },
            title = { Text("Send this photo?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        photo.bitmap.asImageBitmap(),
                        contentDescription = "The photo you picked",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                    Text(
                        if (photo.removed.isEmpty()) "This photo carries no location or camera details."
                        else "Removed from the copy you send: ${photo.removed.joinToString()} (location, camera and time).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "It is encrypted on this phone; the server only stores the encrypted file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.sendPhoto(jid, photo.jpeg); photoToSend = null }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { photoToSend = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageBubble(vm: AppViewModel, message: ChatMessage, onOpen: () -> Unit, onDelete: () -> Unit) {
    val sealed = !message.outgoing && !message.opened
    val isPhoto = EncryptedMedia.isLink(message.body)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.outgoing) Color(0xFF2A2A2A) else Color(0xFF161616),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 300.dp).then(if (sealed) Modifier.clickable(onClick = onOpen) else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (sealed) {
                    Text(if (isPhoto) "Sealed photo" else "Sealed message", style = MaterialTheme.typography.titleSmall)
                    Text("Tap to open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (isPhoto) Photo(vm, message) else Text(message.body)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!message.outgoing && !message.trusted) {
                            Text(
                                "Unverified device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        TextButton(onClick = onDelete) { Text("Delete", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

/** Fetches and decrypts the photo while it is on screen; the pixels stay in memory only. */
@Composable
private fun Photo(vm: AppViewModel, message: ChatMessage) {
    val photo by produceState<Result<ImageBitmap>?>(null, message.id) {
        value = runCatching {
            val bytes = vm.loadPhoto(message)
            val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            requireNotNull(bitmap) { "That photo could not be shown" }.asImageBitmap()
        }
    }
    val result = photo
    when {
        result == null -> Text(
            "Opening photo over Tor…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.isSuccess -> Image(
            result.getOrThrow(),
            contentDescription = "Photo",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        else -> Text(
            result.exceptionOrNull()?.message ?: "That photo is no longer available",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontFamily = FontFamily.Default,
        )
    }
}
