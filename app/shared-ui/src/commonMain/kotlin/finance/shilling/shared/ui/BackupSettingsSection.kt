package finance.shilling.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import finance.shilling.core.backup.*
import finance.shilling.shared.data.backup.BackupController
import finance.shilling.shared.data.backup.KeySyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val LocalBackupController = staticCompositionLocalOf<BackupController?> { null }

@Composable
fun BackupSettingsSection() {
    val controller = LocalBackupController.current
    if (controller == null) {
        ShillingCard { Text("Connect to your hosted account to manage cloud sync.") }
        return
    }
    val scope = rememberCoroutineScope()
    val policy by controller.policy.collectAsState()
    val busy by controller.busy.collectAsState()
    val status by controller.status.collectAsState()
    val hasKey by controller.hasKey.collectAsState()
    val trustedConsent by controller.trustedConsent.collectAsState()
    val savedKey by controller.savedKey.collectAsState()
    val keyStatuses by controller.keySync.status.collectAsState()
    val approvals by controller.keySync.pendingApprovals.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var showRecoveryTools by remember { mutableStateOf(false) }
    var setupOpen by remember { mutableStateOf(false) }
    var setupLoading by remember { mutableStateOf(false) }
    var setupKey by remember { mutableStateOf<String?>(null) }
    var setupChecked by remember { mutableStateOf(false) }
    var setupCopied by remember { mutableStateOf(false) }
    var setupDismissed by remember { mutableStateOf(false) }
    var enableAfterConsent by remember { mutableStateOf(false) }
    var showTrustedConsent by remember { mutableStateOf(false) }
    var consentChecked by remember { mutableStateOf(false) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    var importKey by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf<String?>(null) }
    var invitationInput by remember { mutableStateOf("") }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Unable to complete this step. Check the key or invitation and try again." }
        }
    }

    fun beginEncryptedSetup() {
        if (setupLoading) return
        setupOpen = true
        setupLoading = true
        setupChecked = false
        setupCopied = false
        setupKey = null
        action {
            try {
                controller.refresh()
                if (controller.hasKey.value || controller.policy.value?.recoveryKeyRequired == false) {
                    setupKey = controller.recoveryKey()
                    setupChecked = controller.savedKey.value
                }
            } finally { setupLoading = false }
        }
    }
    // Surface recovery immediately on a device joining an already encrypted household.
    LaunchedEffect(policy?.enabled, policy?.encryption, hasKey) {
        if (policy?.enabled == true && policy?.encryption == BackupEncryption.END_TO_END && !hasKey && !setupOpen && !setupDismissed) beginEncryptedSetup()
        if (setupOpen && hasKey && setupKey == null && !setupLoading) {
            action {
                setupKey = controller.recoveryKey()
                setupChecked = controller.savedKey.value
            }
        }
    }

    ShillingCard {
        Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
        Text("Keeps devices up to date while the app is open, even when your other devices are offline. Cloud sync is not historical backup.", style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val current = policy
        if (current == null) {
            OutlinedButton(onClick = { action { controller.refresh() } }) { Text("Retry") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cloud sync")
                Switch(checked = current.enabled, enabled = !busy && (current.enabled || current.available), onCheckedChange = { enabled ->
                    when {
                        enabled && current.encryption == BackupEncryption.END_TO_END && (!hasKey || !savedKey) -> beginEncryptedSetup()
                        enabled && current.encryption == BackupEncryption.TRUSTED_CLOUD && !trustedConsent -> {
                            enableAfterConsent = true; consentChecked = false; showTrustedConsent = true
                        }
                        else -> action { controller.update(enabled, current.turnEnabled, current.encryption) }
                    }
                })
            }
            if (!current.available && !current.enabled) Text("Requires a paid plan and a configured cloud peer.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TURN connectivity")
                Switch(checked = current.turnEnabled, enabled = !busy && !current.enabled, onCheckedChange = { turn ->
                    action { controller.update(current.enabled, turn, current.encryption) }
                })
            }
            Text(if (current.enabled) "Required for cloud sync and recovery-key sync." else "Helps devices connect across different networks.", style = MaterialTheme.typography.bodySmall)
            Text("Encryption", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = current.encryption == BackupEncryption.END_TO_END, enabled = !busy, onClick = {
                    if (current.enabled && (!hasKey || !savedKey)) beginEncryptedSetup()
                    else action { controller.update(current.enabled, current.turnEnabled, BackupEncryption.END_TO_END) }
                })
                Text("End-to-end encrypted (recommended)")
            }
            Text("Only devices with your recovery key can decrypt your cloud data. Shilling cannot recover the key for you.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = current.encryption == BackupEncryption.TRUSTED_CLOUD, enabled = !busy && current.trustedCloudAvailable, onClick = { enableAfterConsent = false; consentChecked = false; showTrustedConsent = true })
                Text("Trusted cloud")
            }
            Text("Encrypted in storage, with a key held by Shilling. The service can decrypt your cloud data. Changing modes affects future uploads. Previously uploaded data retains its encryption mode.", style = MaterialTheme.typography.bodySmall)
            if (current.encryption == BackupEncryption.TRUSTED_CLOUD && !trustedConsent) {
                OutlinedButton(onClick = { enableAfterConsent = false; consentChecked = false; showTrustedConsent = true }) { Text("Confirm cloud access on this device") }
            }
            if (!current.trustedCloudAvailable) Text("Trusted cloud is not configured on this server.", style = MaterialTheme.typography.bodySmall)
            if (current.enabled && current.encryption == BackupEncryption.END_TO_END && !hasKey) {
                Button(enabled = !busy && !setupLoading, onClick = { beginEncryptedSetup() }) { Text("Connect this device") }
            } else {
                Button(enabled = current.enabled && !busy, onClick = { action { controller.syncNow() } }) { Text("Sync now") }
            }
            TextButton(onClick = { showRecoveryTools = !showRecoveryTools }) {
                Text(if (showRecoveryTools) "Hide recovery and devices" else "Recovery and devices")
            }
        }
    }
    if (showRecoveryTools) {
    ShillingCard {
        Text("Recovery key", style = MaterialTheme.typography.titleMedium)
        Text("Save a copy outside this device. Device sync cannot help if all your devices are lost.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            if (hasKey) action { shownKey = controller.recoveryKey() } else beginEncryptedSetup()
        }) {
            Text(if (hasKey) "Show recovery key" else "Set up this device")
        }
        shownKey?.let { key ->
            SelectionContainer { Text(key, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = savedKey, enabled = !savedKey, onCheckedChange = { checked -> if (checked) action { controller.confirmSavedKey() } })
                Text("I've saved a copy outside this device.")
            }
            TextButton(onClick = { shownKey = null }) { Text("Hide key") }
        }
        OutlinedTextField(value = importKey, onValueChange = { importKey = it }, label = { Text("Saved recovery key") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = importKey.isNotBlank() && !busy, onClick = { action { controller.importRecoveryKey(importKey); importKey = "" } }) { Text("Import recovery key") }
    }
    ShillingCard {
        Text("Sync recovery key to another device", style = MaterialTheme.typography.titleMedium)
        Text("Open Settings on both devices. Enter the invitation on the new device, then approve it here. Keep both devices online until sync is confirmed.", style = MaterialTheme.typography.bodySmall)
        Text("This device: ${controller.deviceId}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = hasKey && !busy, onClick = { action { invitation = controller.keySync.invitation() } }) { Text("Create device invitation") }
        invitation?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }; Text("Expires in 10 minutes. Share only with your own device.", style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(value = invitationInput, onValueChange = { invitationInput = it }, label = { Text("Invitation from your existing device") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = invitationInput.isNotBlank() && !busy, onClick = { action { controller.keySync.join(invitationInput); invitationInput = "" } }) { Text("Request key sync") }
        approvals.forEach { approval ->
            Text("Approve device ${approval.peerId}?", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { action { controller.keySync.approve(approval.peerId) } }) { Text("Approve device") }
        }
        keyStatuses.forEach { (peer, state) ->
            Text("$peer: ${when (state) { KeySyncStatus.PENDING -> "Pending — waiting for device"; KeySyncStatus.SYNCED -> "Key saved on device"; KeySyncStatus.FAILED -> "Failed — create a new invitation" }}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { action { controller.keySync.revoke(peer) } }) { Text("Revoke key sharing") }
        }
    }
    }
    if (setupOpen) {
        AlertDialog(
            onDismissRequest = { if (!setupLoading && !busy) { setupOpen = false; setupKey = null; setupDismissed = true } },
            title = { Text(if (setupKey != null) "Save your recovery key" else "Connect this device") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (setupLoading) {
                    Text("Preparing encrypted cloud sync…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (setupKey != null) {
                    Text("Your key is ready. Save it in a password manager or another safe place so you can recover your data if you lose your devices.")
                    SelectionContainer { Text(setupKey!!, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(setupKey!!)); setupCopied = true }) {
                        Text(if (setupCopied) "Copied" else "Copy recovery key")
                    }
                    Text("Shilling cannot recover this key for you.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = setupChecked, onCheckedChange = { setupChecked = it })
                        Text("I've saved my key somewhere safe.")
                    }
                } else {
                    Text("Your cloud data already has a recovery key. Get it from an existing device or use your saved copy.")
                    Text("On your other device, open Settings → Recovery and devices → Create device invitation.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = invitationInput, onValueChange = { invitationInput = it }, label = { Text("Device invitation") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = invitationInput.isNotBlank() && !busy, onClick = { action {
                        controller.keySync.join(invitationInput); invitationInput = ""
                    } }) { Text("Connect with another device") }
                    if (keyStatuses.values.any { it == KeySyncStatus.PENDING }) Text("Approve this device on your other device. Keep both apps open; we'll continue when the key arrives.")
                    if (keyStatuses.values.any { it == KeySyncStatus.FAILED }) Text("Connection failed. Create a new invitation and try again.")
                    OutlinedTextField(value = importKey, onValueChange = { importKey = it }, label = { Text("Saved recovery key") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = importKey.isNotBlank() && !busy, onClick = { action {
                        controller.importRecoveryKey(importKey); importKey = ""
                        setupKey = controller.recoveryKey(); setupChecked = true
                    } }) { Text("Use saved key") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!setupLoading) Text(status, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = setupKey != null && setupChecked && !setupLoading && !busy, onClick = {
                setupLoading = true
                action {
                    try {
                        controller.confirmSavedKey()
                        controller.refresh()
                        if (controller.policy.value == null) return@action
                        controller.update(true, true, BackupEncryption.END_TO_END)
                        if (controller.policy.value?.let { it.enabled && it.encryption == BackupEncryption.END_TO_END } == true) {
                            setupOpen = false; setupKey = null
                            controller.syncNow()
                        }
                    } finally { setupLoading = false }
                }
            }) { Text("Continue") } },
            dismissButton = { TextButton(enabled = !setupLoading && !busy, onClick = { setupOpen = false; setupKey = null; setupDismissed = true }) { Text("Not now") } }
        )
    }
    if (showTrustedConsent) {
        AlertDialog(
            onDismissRequest = { showTrustedConsent = false },
            title = { Text("Allow Shilling to access your cloud data?") },
            text = { Column {
                Text("Trusted cloud gives Shilling access to your budget and receipt contents. Storage remains encrypted with a service-held key. Future cloud integrations would need their own access controls. This uploads your current budget in trusted-cloud mode. Previously uploaded data retains its encryption mode.")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                    Text(TRUSTED_CLOUD_CONSENT)
                }
            } },
            confirmButton = { TextButton(enabled = consentChecked, onClick = {
                policy?.let { current -> action { controller.update(current.enabled || enableAfterConsent, true, BackupEncryption.TRUSTED_CLOUD, TRUSTED_CLOUD_CONSENT) } }
                showTrustedConsent = false
            }) { Text("Allow cloud access") } },
            dismissButton = { TextButton(onClick = { showTrustedConsent = false }) { Text("Cancel") } }
        )
    }
}
