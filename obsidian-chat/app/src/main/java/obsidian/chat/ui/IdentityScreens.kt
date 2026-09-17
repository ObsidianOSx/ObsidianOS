package obsidian.chat.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import obsidian.chat.crypto.PgpIdentity

@Composable
fun MyIdentityScreen(vm: AppViewModel) {
    val client = vm.client
    var confirmErase by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = { vm.back() }) { Text("‹ Back") }
        Text("My identity", style = MaterialTheme.typography.titleLarge)
        client.ownJid?.let { jid ->
            Text("Your username: ${jid.substringBefore('@')}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Contacts add you by this username.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        client.ownPgpFingerprint?.let { fingerprint ->
            Text("PGP fingerprint", style = MaterialTheme.typography.titleSmall)
            Text(PgpIdentity.formatFingerprint(fingerprint), fontFamily = FontFamily.Monospace)
            QrCode("OPENPGP4FPR:$fingerprint", Modifier.size(220.dp).align(Alignment.CenterHorizontally))
            Text(
                "Show this to a contact in person. They check it matches what their app shows for you.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        client.ownOmemoFingerprint?.let { fingerprint ->
            Text("This device (OMEMO key, signed by your PGP key)", style = MaterialTheme.typography.titleSmall)
            Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { confirmErase = true }, enabled = !vm.busy) { Text("Erase this account") }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase this account?") },
            text = {
                Text(
                    "Wipes your messages, contacts and keys from this phone and, if you're online, deletes the " +
                        "account from the server. This can't be undone. The app closes when it's done."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; vm.eraseAccount() }) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ContactIdentityScreen(vm: AppViewModel, jid: String) {
    val tick by vm.changes.collectAsState()
    val contact = remember(tick, jid) { vm.client.contact(jid) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = { vm.back() }) { Text("‹ Back") }
        Text(jid.substringBefore('@'), style = MaterialTheme.typography.titleLarge)
        val fingerprint = contact?.pgpFingerprint
        if (fingerprint == null) {
            Text(
                "Their keys haven't arrived yet. They appear once they accept your contact request.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Their PGP fingerprint", style = MaterialTheme.typography.titleSmall)
            Text(PgpIdentity.formatFingerprint(fingerprint), fontFamily = FontFamily.Monospace)
            Text(
                "Devices vouched for by this key: ${vm.client.trustedDeviceCount(jid)}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (contact.verified) {
                Text("You verified this fingerprint in person.", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = { vm.markVerified(jid) }) { Text("It matches what they showed me") }
            }
        }
        OutlinedButton(onClick = { vm.refreshContact(jid) }, enabled = !vm.busy) { Text("Refresh keys") }

        var confirmRemove by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirmRemove = true }, enabled = !vm.busy) { Text("Remove contact") }

        if (confirmRemove) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("Remove ${jid.substringBefore('@')}?") },
                text = {
                    Text(
                        "Deletes your conversation and their keys from this phone, and stops either of you " +
                            "seeing the other. They are not told. You can add them again later by username."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmRemove = false; vm.deleteContact(jid) }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, contentDescription = "QR code of the fingerprint", modifier = modifier)
}
