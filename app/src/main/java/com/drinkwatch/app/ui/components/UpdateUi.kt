package com.drinkwatch.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drinkwatch.app.data.UpdateController
import com.drinkwatch.app.data.UpdateState
import kotlinx.coroutines.launch

/**
 * Launch-time update prompt. Shown by MainActivity whenever a newer build is
 * published on the server, unless the user deferred that exact version.
 */
@Composable
fun UpdatePromptDialog(controller: UpdateController) {
    if (!controller.showPrompt) return
    val scope = rememberCoroutineScope()
    val state = controller.state

    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { controller.dismissPrompt(true) },
            title = { Text("Update available") },
            text = {
                Column {
                    Text(
                        "DrinkWatch " + state.update.versionName + " is out (you have " +
                            controller.currentVersion + ").",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.update.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.update.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { controller.downloadAndInstall(state.update) }
                }) { Text("Download and install") }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissPrompt(true) }) { Text("Later") }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update…") },
            text = {
                Column {
                    val progress = state.progress
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text((progress * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {},
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { controller.dismissPrompt(false) },
            title = { Text("Almost there") },
            text = { Text("The installer should be open. If it did not appear, tap Install again.") },
            confirmButton = {
                TextButton(onClick = { controller.install(state.file) }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { controller.dismissPrompt(false) }) { Text("Close") } },
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = { controller.dismissPrompt(false) },
            title = { Text("Update failed") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { controller.reset(); controller.dismissPrompt(false) }) { Text("OK") }
            },
        )

        else -> Unit
    }
}
