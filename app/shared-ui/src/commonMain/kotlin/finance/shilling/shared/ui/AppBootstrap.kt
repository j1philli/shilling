package finance.shilling.shared.ui

import finance.shilling.shared.data.auth.AuthErrors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.initKoin
import finance.shilling.shared.session.AppSession
import finance.shilling.shared.session.SessionPhase
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

data class AppBootstrapScaffoldConfig(
    val onboardingTopPadding: Dp = 0.dp,
    val navRailTopPadding: Dp = 0.dp,
    /** Overrides the navigation rail width to make room for the macOS traffic lights. */
    val navRailWidth: Dp = Dp.Unspecified,
    val cameraButton: ReceiptPickerButton? = null,
    val photoButton: ReceiptPickerButton? = null,
    val externalNavRequest: StateFlow<ShelfDestination?> = MutableStateFlow(null),
    val pendingReceiptFile: PlatformFile? = null,
    val onPendingReceiptConsumed: () -> Unit = {},
    val preScaffoldContent: @Composable () -> Unit = {},
    /** Show developer tools in Settings without the hidden unlock (debug builds). */
    val developerToolsEnabled: Boolean = false,
    /** Given the app's NavController once the main scaffold is shown (web: browser history). */
    val navControllerHook: @Composable (NavHostController) -> Unit = {},
    val startupPendingContent: @Composable () -> Unit = { DefaultLoadingSurface() }
)

/**
 * Renders the [AppSession] (onboarding, startup, main app). The session itself runs outside
 * Compose: each platform calls [initKoin] with `sessionModule`, and the session owns hosted
 * bootstrap and sync; this only shows its phase and forwards the user's actions.
 */
@Composable
fun ShillingAppBootstrap(
    scaffoldConfig: AppBootstrapScaffoldConfig = AppBootstrapScaffoldConfig()
) {
    val session = koinInject<AppSession>()
    remember(session) { session.start() }
    val phase by session.phase.collectAsState()

    when (val current = phase) {
        is SessionPhase.Onboarding -> ShillingTheme {
            FirstLaunchOnboardingView(
                authService = current.authService,
                topPadding = scaffoldConfig.onboardingTopPadding
            )
        }

        SessionPhase.Starting -> ShillingTheme {
            scaffoldConfig.startupPendingContent()
        }

        is SessionPhase.Ready -> androidx.compose.runtime.key(current.spaceId) {
            val owner = remember { object : androidx.lifecycle.ViewModelStoreOwner {
                override val viewModelStore = androidx.lifecycle.ViewModelStore()
            } }
            androidx.compose.runtime.DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner) { ShillingTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                scaffoldConfig.preScaffoldContent()
                ShillingScaffold(
                    authService = current.authService,
                    featureGate = current.featureGate,
                    selfHosted = current.selfHosted,
                    onRetryHostedBootstrap = session::retryBootstrap,
                    navRailTopPadding = scaffoldConfig.navRailTopPadding,
                    navRailWidth = scaffoldConfig.navRailWidth,
                    cameraButton = scaffoldConfig.cameraButton,
                    photoButton = scaffoldConfig.photoButton,
                    externalNavRequest = scaffoldConfig.externalNavRequest,
                    pendingReceiptFile = scaffoldConfig.pendingReceiptFile,
                    onPendingReceiptConsumed = scaffoldConfig.onPendingReceiptConsumed,
                    developerToolsEnabled = scaffoldConfig.developerToolsEnabled,
                    navControllerHook = scaffoldConfig.navControllerHook
                )
            }
            PasswordSetupPrompt(current.authService)
        } } }
    }
}

@Composable
private fun PasswordSetupPrompt(authService: AuthService) {
    val authState by authService.authState.collectAsState()
    var dismissed by remember(authService) { mutableStateOf(false) }
    var password by remember(authService) { mutableStateOf("") }
    var error by remember(authService) { mutableStateOf<String?>(null) }
    var submitting by remember(authService) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (!authState.needsPasswordSetup || dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Set your password") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Your email is confirmed. Set a password to sign in on another device.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = password.isNotBlank() && !submitting,
                onClick = {
                    scope.launch {
                        submitting = true
                        authService.setPassword(password).fold(
                            onSuccess = { password = ""; dismissed = true },
                            onFailure = { error = AuthErrors.message(it, "set_password") }
                        )
                        submitting = false
                    }
                }
            ) { Text("Set password") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Later") } }
    )
}

@Composable
private fun DefaultLoadingSurface() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
