package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import finance.shilling.shared.data.initKoin
import finance.shilling.shared.session.AppSession
import finance.shilling.shared.session.SessionPhase
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.koinInject

data class AppBootstrapScaffoldConfig(
    val onboardingTopPadding: Dp = 0.dp,
    val navRailTopPadding: Dp = 0.dp,
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
    /** Native tab bar drawn by the platform (iOS); null draws the Compose bar / rail. */
    val platformTabBar: PlatformTabBar? = null,
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

        is SessionPhase.Ready -> ShillingTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                scaffoldConfig.preScaffoldContent()
                ShillingScaffold(
                    authService = current.authService,
                    featureGate = current.featureGate,
                    selfHosted = current.selfHosted,
                    onRetryHostedBootstrap = session::retryBootstrap,
                    navRailTopPadding = scaffoldConfig.navRailTopPadding,
                    cameraButton = scaffoldConfig.cameraButton,
                    photoButton = scaffoldConfig.photoButton,
                    externalNavRequest = scaffoldConfig.externalNavRequest,
                    pendingReceiptFile = scaffoldConfig.pendingReceiptFile,
                    onPendingReceiptConsumed = scaffoldConfig.onPendingReceiptConsumed,
                    developerToolsEnabled = scaffoldConfig.developerToolsEnabled,
                    navControllerHook = scaffoldConfig.navControllerHook,
                    platformTabBar = scaffoldConfig.platformTabBar
                )
            }
        }
    }
}

@Composable
private fun DefaultLoadingSurface() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
