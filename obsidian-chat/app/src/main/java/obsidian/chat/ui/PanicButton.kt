package obsidian.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val HOLD_MILLIS = 3000

/**
 * The emergency wipe button. Holding it for three seconds erases everything; letting go early
 * cancels. There is no confirmation dialog on purpose — in a panic, the hold is the confirmation.
 */
@Composable
fun HoldToWipeButton(onWipe: () -> Unit, modifier: Modifier = Modifier) {
    var held by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(held) {
        if (!held) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        progress.animateTo(1f, tween(durationMillis = HOLD_MILLIS, easing = LinearEasing))
        onWipe()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.error,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true
                    tryAwaitRelease()
                    held = false
                })
            },
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (held) "Keep holding to erase everything…" else "EMERGENCY WIPE · HOLD 3 SECONDS",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
            if (held) {
                LinearProgressIndicator(
                    progress = { progress.value },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
