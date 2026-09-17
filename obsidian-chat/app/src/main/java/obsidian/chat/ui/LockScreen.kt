package obsidian.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown instead of everything else while the app is locked. */
@Composable
fun LockScreen(vm: AppViewModel) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OBSIDIAN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, letterSpacing = 10.sp)
        Text("Enter your PIN", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit); wrong = false },
            label = { Text("PIN") },
            isError = wrong,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrong) Text("That PIN is not right", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                vm.unlock(pin) { ok -> if (!ok) wrong = true; pin = "" }
            },
            enabled = pin.length >= 4 && !vm.checkingPin,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.checkingPin) "Checking…" else "Unlock") }
    }
}
