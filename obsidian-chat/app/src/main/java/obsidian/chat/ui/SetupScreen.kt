package obsidian.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import obsidian.chat.xmpp.USERNAME_PATTERN

@Composable
fun SetupScreen(vm: AppViewModel, torReady: Boolean) {
    var username by rememberSaveable { mutableStateOf("") }
    // Secrets use plain remember so they are never written into saved instance state
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var importKey by rememberSaveable { mutableStateOf(false) }
    var armoredKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }

    val usernameValid = USERNAME_PATTERN.matches(username)
    val keyValid = !importKey || armoredKey.contains("PRIVATE KEY BLOCK")
    // Say what still stops the account being created, instead of leaving a dead button
    val missing = when {
        !torReady -> "Waiting for Tor to connect (progress at the top)…"
        !usernameValid -> "Choose a username: 3–32 characters, a–z, 0–9, dot, dash or underscore"
        password.length < 12 -> "Your password needs at least 12 characters"
        password != confirm -> "The two passwords don't match"
        pin.length < 4 -> "Choose a screen lock PIN of 4 to 16 digits"
        pin != pinConfirm -> "The two PINs don't match"
        !keyValid -> "Paste your armored PGP secret key"
        else -> null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OBSIDIAN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, letterSpacing = 10.sp)
        Text(
            "Create your account. Your PGP key is created and kept on this phone only. " +
                "Contacts add you by your username.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.lowercase().trim() },
            label = { Text("Username") },
            supportingText = { Text("3–32 characters: a–z, 0–9, dot, dash, underscore") },
            isError = username.isNotEmpty() && !usernameValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, "Password (12+ characters)")
        PasswordField(confirm, { confirm = it }, "Confirm password", isError = confirm.isNotEmpty() && confirm != password)

        Text("Screen lock", style = MaterialTheme.typography.titleSmall)
        Text(
            "The app locks itself whenever you leave it, and asks for this PIN when you come back.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SetupPinField(pin, { pin = it }, "Screen lock PIN (4–16 digits)")
        SetupPinField(pinConfirm, { pinConfirm = it }, "Repeat PIN", isError = pinConfirm.isNotEmpty() && pinConfirm != pin)

        Text("PGP identity", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !importKey, onClick = { importKey = false })
            Text("Generate a new key on this phone")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = importKey, onClick = { importKey = true })
            Text("Import my existing key")
        }
        if (importKey) {
            OutlinedTextField(
                value = armoredKey,
                onValueChange = { armoredKey = it },
                label = { Text("Armored secret key") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(keyPassphrase, { keyPassphrase = it }, "Key passphrase (if it has one)")
        }

        Button(
            onClick = {
                vm.createAccount(
                    username = username,
                    password = password,
                    pin = pin,
                    importedKey = armoredKey.takeIf { importKey },
                    keyPassphrase = keyPassphrase.takeIf { importKey && it.isNotEmpty() },
                )
            },
            enabled = missing == null && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (vm.busy) "Creating account over Tor…" else "Create account")
        }
        if (missing != null && !vm.busy) {
            Text(missing, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SetupPinField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean = false) {
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
