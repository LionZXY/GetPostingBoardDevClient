package dev.getpostingboard.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.getpostingboard.reader.data.registrationFailure
import dev.getpostingboard.reader.state.ReaderState
import dev.getpostingboard.reader.state.ReaderStore
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Suppress("DEPRECATION")
@Composable
internal fun ConnectionDialog(state: ReaderState, store: ReaderStore, initiallyCreate: Boolean, dismiss: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(initiallyCreate) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    // Secrets and their visibility must never enter Android saved-instance state.
    var key by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val hasKey = state.connected || state.keyNeedsSaving
    val issuedKey = remember(state.connected, state.registeredName, state.keyNeedsSaving) {
        if (hasKey) store.availableApiKey().orEmpty() else ""
    }
    LaunchedEffect(hasKey) { key = ""; revealed = false; copied = false }
    var now by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    val retryAt = state.connectionFailure?.retryAt
    LaunchedEffect(retryAt) {
        now = Clock.System.now().epochSeconds
        while (retryAt != null && now < retryAt) { delay(1000); now = Clock.System.now().epochSeconds }
    }
    val remaining = ((retryAt ?: 0) - now).coerceAtLeast(0)
    val canSubmit = !state.connecting && remaining == 0L && if (creating) {
        !state.registrationUncertain && registrationFailure(name.trim(), description.trim()) == null
    } else key.isNotBlank()
    val submit = {
        if (canSubmit) {
            if (creating) store.register(name, description) else store.connect(key)
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Accounts & voting") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasKey) {
                    Text(state.registeredName?.let { "Account created: $it" } ?: "Your API key is connected.")
                    Text(if (state.keyNeedsSaving) "Keep a copy of your new API key before closing the app."
                        else if (store.persistsCredentials) "Your key is saved securely on this device. Keep a secure copy: the service cannot recover it."
                        else "Connected for this session only. Copy your key before closing the app; the service cannot recover it.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = issuedKey, onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text("API key") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (revealed) "Hide API key" else "Show API key")
                            }
                        })
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(issuedKey)); copied = true },
                        enabled = issuedKey.isNotEmpty()) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied) "Copied" else "Copy API key")
                    }
                } else {
                    Text("Create an account to get a new API key, or use a key you already have.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = creating, enabled = !state.connecting,
                            onClick = { creating = true; key = ""; store.clearConnectionFailure() }, label = { Text("Create account") })
                        FilterChip(selected = !creating, enabled = !state.connecting,
                            onClick = { creating = false; store.clearConnectionFailure() }, label = { Text("Use API key") })
                    }
                    if (creating) {
                        OutlinedTextField(value = name, onValueChange = { name = it; store.clearConnectionFailure() },
                            label = { Text("Account name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("3–40 lowercase letters, numbers, or hyphens. Start with a letter or number.") },
                            isError = name.isNotEmpty() && registrationFailure(name.trim(), "") != null,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false, imeAction = ImeAction.Next), enabled = !state.connecting)
                        OutlinedTextField(value = description, onValueChange = { description = it; store.clearConnectionFailure() },
                            label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                            supportingText = { Text("${description.length}/240 · Public account details") },
                            isError = description.trim().length > 240, enabled = !state.connecting,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }))
                        Text("Creating an account generates and connects its API key automatically.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedTextField(value = key, onValueChange = { key = it; store.clearConnectionFailure() },
                            label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }), enabled = !state.connecting)
                    }
                }
                state.connectionFailure?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (remaining > 0) Text("Try again in ${remaining}s.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                VotingSettings(store.voting)
                HorizontalDivider()
                Text("Unsorted needs no account. This unofficial reader does not publish messages.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            when {
                state.keyNeedsSaving -> TextButton(onClick = store::saveRegisteredKey) { Text("Retry saving key") }
                state.connected -> TextButton(onClick = dismiss) { Text("Done") }
                else -> TextButton(onClick = submit, enabled = canSubmit) {
                    Text(if (creating) "Create & get API key" else "Connect")
                }
            }
        }, dismissButton = {
            if (state.connected) TextButton(onClick = { store.disconnect(); key = "" }) { Text("Disconnect") }
            else TextButton(onClick = dismiss) { Text("Close") }
        })
}
