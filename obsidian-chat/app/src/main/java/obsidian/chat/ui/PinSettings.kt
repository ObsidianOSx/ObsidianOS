package obsidian.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Set, change or turn off the PIN that locks the app. */
@Composable
fun PinSettings(vm: AppViewModel) {
    var editing by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val hasPin = vm.hasPin

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Screen lock", style = MaterialTheme.typography.titleSmall)
        Text(
            if (hasPin) "The app locks whenever you leave it and asks for your PIN when you come back."
            else "No PIN yet. With one set, the app locks whenever you leave it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The lock can be changed but not switched off: every account is set up with one
            Button(onClick = { pin = ""; confirm = ""; editing = true }) {
                Text(if (hasPin) "Change PIN" else "Set a PIN")
            }
        }
    }

    if (editing) {
        val valid = pin.length in 4..16 && pin == confirm
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (hasPin) "Change PIN" else "Set a PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinField(pin, { pin = it }, "PIN (4–16 digits)")
                    PinField(confirm, { confirm = it }, "Repeat PIN", isError = confirm.isNotEmpty() && confirm != pin)
                    Text(
                        "If you forget it, the only way back in is the emergency wipe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid && !vm.checkingPin,
                    onClick = { vm.changePin(pin) { editing = false } },
                ) { Text(if (vm.checkingPin) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !vm.checkingPin, onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
