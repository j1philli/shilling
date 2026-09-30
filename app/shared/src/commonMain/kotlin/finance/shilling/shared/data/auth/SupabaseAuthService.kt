package finance.shilling.shared.data.auth

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_ACCESS_TOKEN
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_PENDING_EMAIL
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_PENDING_SIGN_IN_EMAIL
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_PASSWORD_SETUP_USER_ID
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_REFRESH_TOKEN
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_TIER
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_USER_ID
import finance.shilling.shared.data.SETTINGS_KEY_PENDING_RESTORE_USER_ID
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.parseSessionFromUrl
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

private val log = Logger.withTag("Auth")
private const val SUPABASE_BOOTSTRAP_TIMEOUT_MS = 3_000L

@OptIn(SupabaseInternal::class)
class SupabaseAuthService(
    supabaseUrl: String,
    supabaseAnonKey: String,
    private val deviceId: String,
    private val settings: Settings,
    scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val authRedirectUrl: String? = null
) : AuthService {
    private val initializationComplete = CompletableDeferred<Unit>()
    private val guestSignInMutex = Mutex()
    private val profilesUrl = supabaseUrl.trimEnd('/') + "/rest/v1/user_profiles"
    private val anonKey = supabaseAnonKey

    private val client = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseAnonKey
    ) {
        requestTimeout = 3.seconds
        httpConfig {
            install(HttpTimeout) {
                connectTimeoutMillis = SUPABASE_BOOTSTRAP_TIMEOUT_MS
                requestTimeoutMillis = SUPABASE_BOOTSTRAP_TIMEOUT_MS
                socketTimeoutMillis = SUPABASE_BOOTSTRAP_TIMEOUT_MS
            }
        }
        install(Auth)
    }

    private val _authState = MutableStateFlow(
        AuthState(
            isAuthenticated = false,
            userId = settings.getStringOrNull(SETTINGS_KEY_AUTH_USER_ID),
            email = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL),
            tier = parseStoredTier(settings.getStringOrNull(SETTINGS_KEY_AUTH_TIER)) ?: UserTier.ANONYMOUS,
            accessToken = settings.getStringOrNull(SETTINGS_KEY_AUTH_ACCESS_TOKEN),
            isAnonymous = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION) != "true",
            deviceId = deviceId,
            pendingEmailConfirmation = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION) == "true",
            pendingEmail = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL)
        )
    )
    override val authState: StateFlow<AuthState> = _authState

    init {
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val session = status.session
                        val user = session.user
                        val userId = user?.id ?: return@collect
                        updateAuthenticatedState(
                            userId = userId,
                            sessionEmail = user.email,
                            emailConfirmed = user.emailConfirmedAt != null,
                            token = session.accessToken,
                            refreshToken = session.refreshToken
                        )
                        if (!initializationComplete.isCompleted) initializationComplete.complete(Unit)
                    }
                    is SessionStatus.NotAuthenticated -> {
                        if (status.isSignOut) setUnauthenticatedState()
                        if (!initializationComplete.isCompleted) initializationComplete.complete(Unit)
                        log.i { "Not authenticated" }
                    }
                    is SessionStatus.Initializing -> {
                        log.d { "Auth initializing..." }
                    }
                    is SessionStatus.RefreshFailure -> {
                        // Keep the saved identity through a temporary network or token refresh failure.
                        if (!initializationComplete.isCompleted) initializationComplete.complete(Unit)
                        log.w { "Token refresh failed: ${status.cause}" }
                    }
                }
            }
        }
    }

    override suspend fun ensureAuthenticated(): Result<Unit> = guestSignInMutex.withLock {
        runCatching {
            check(withTimeoutOrNull(10_000) { initializationComplete.await(); true } == true) {
                "Timed out restoring the saved account"
            }
            val session = client.auth.currentSessionOrNull()
            if (_authState.value.isAuthenticated && session?.user?.id == _authState.value.userId) {
                return@runCatching
            }
            val savedRefreshToken = settings.getStringOrNull(SETTINGS_KEY_AUTH_REFRESH_TOKEN)
            if (savedRefreshToken != null) {
                client.auth.refreshSession(refreshToken = savedRefreshToken)
            } else {
                check(settings.getStringOrNull(SETTINGS_KEY_AUTH_USER_ID) == null) {
                    "Saved account is missing its refresh token"
                }
                client.auth.signInAnonymously()
            }
            check(withTimeoutOrNull(10_000) {
                authState.filter { state ->
                    state.isAuthenticated &&
                        state.userId == client.auth.currentSessionOrNull()?.user?.id &&
                        state.accessToken == client.auth.currentSessionOrNull()?.accessToken
                }.first()
                true
            } == true) { "Auth did not reach an authenticated state before timeout" }
        }.onFailure { error ->
            log.w { "Auth restoration or guest sign-in failed: ${error.message}" }
        }
    }

    override suspend fun signUp(email: String, password: String): Result<SignUpResult> = runCatching {
        if (_authState.value.isAnonymous && _authState.value.isAuthenticated) {
            val userId = requireNotNull(_authState.value.userId) { "Guest session has no user ID" }
            // Link the email to this UUID first. Supabase requires verification before setting a password.
            val updatedUser = client.auth.updateUser(redirectUrl = authRedirectUrl) {
                this.email = email
            }
            cachePendingEmailConfirmation(userId, email)
            if (updatedUser.emailConfirmedAt != null) {
                val session = client.auth.currentSessionOrNull() ?: error("No active session")
                updateAuthenticatedState(
                    userId = userId,
                    sessionEmail = updatedUser.email,
                    emailConfirmed = true,
                    token = session.accessToken,
                    refreshToken = session.refreshToken
                )
            } else {
                _authState.value = _authState.value.copy(
                    tier = UserTier.ANONYMOUS,
                    isAnonymous = true,
                    pendingEmailConfirmation = true,
                    pendingEmail = email,
                    needsPasswordSetup = false
                )
            }
            log.i { "Requested email verification for anonymous account" }
            SignUpResult(
                requiresEmailConfirmation = updatedUser.emailConfirmedAt == null,
                upgradedAnonymousSession = true
            )
        } else {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            log.i { "Signed up new user" }
            SignUpResult(
                requiresEmailConfirmation = true,
                upgradedAnonymousSession = false
            )
        }
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        log.i { "Signed in" }
    }

    override suspend fun sendSignInLink(email: String): Result<Unit> = runCatching {
        val address = email.trim()
        require(address.isNotBlank()) { "Enter your email address" }
        client.auth.signInWith(OTP, redirectUrl = authRedirectUrl) {
            this.email = address
            createUser = false
        }
        settings.putString(SETTINGS_KEY_AUTH_PENDING_SIGN_IN_EMAIL, address)
    }

    override suspend fun handleAuthCallback(url: String): Result<Unit> = runCatching {
        if (!url.startsWith("shilling.finance://auth-callback") || "access_token=" !in url) {
            refreshAccountStatus().getOrThrow()
            return@runCatching
        }
        val sessionFromLink = client.auth.parseSessionFromUrl(url)
        val linkUser = client.auth.retrieveUser(sessionFromLink.accessToken)
        val currentUserId = client.auth.currentSessionOrNull()?.user?.id
        val requestedEmail = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_SIGN_IN_EMAIL)
        val heldAccountId = settings.getStringOrNull(SETTINGS_KEY_PENDING_RESTORE_USER_ID)
        check(mayImportAuthCallback(linkUser.id, linkUser.email, currentUserId, requestedEmail, heldAccountId)) {
            "This link is for a different account"
        }
        client.auth.importSession(sessionFromLink.copy(user = linkUser))
        val imported = client.auth.currentSessionOrNull() ?: error("Could not open the sign-in link")
        updateAuthenticatedState(
            userId = linkUser.id,
            sessionEmail = linkUser.email,
            emailConfirmed = linkUser.emailConfirmedAt != null,
            token = imported.accessToken,
            refreshToken = imported.refreshToken
        )
        settings.remove(SETTINGS_KEY_AUTH_PENDING_SIGN_IN_EMAIL)
    }

    override suspend fun refreshAccountStatus(): Result<Unit> = runCatching {
        val originalUserId = client.auth.currentSessionOrNull()?.user?.id ?: error("No active session")
        val user = client.auth.retrieveUserForCurrentSession(updateSession = true)
        val session = client.auth.currentSessionOrNull() ?: error("No active session")
        if (user.id != originalUserId || user.id != session.user?.id) {
            error("Account changed while checking confirmation")
        }
        updateAuthenticatedState(
            userId = user.id,
            sessionEmail = user.email,
            emailConfirmed = user.emailConfirmedAt != null,
            token = session.accessToken,
            refreshToken = session.refreshToken
        )
    }

    override suspend fun setPassword(password: String): Result<Unit> = runCatching {
        require(password.isNotBlank()) { "Enter a password" }
        refreshAccountStatus().getOrThrow()
        val state = _authState.value
        check(state.isAuthenticated && !state.isAnonymous && state.needsPasswordSetup) {
            "Confirm your email before setting a password"
        }
        client.auth.updateUser { this.password = password }
        settings.remove(SETTINGS_KEY_AUTH_PASSWORD_SETUP_USER_ID)
        settings.remove(SETTINGS_KEY_AUTH_PENDING_SIGN_IN_EMAIL)
        _authState.value = _authState.value.copy(needsPasswordSetup = false)
    }

    override suspend fun signOut() {
        try {
            client.auth.signOut()
        } catch (e: Exception) {
            log.w { "Sign out error: ${e.message}" }
        }
        setUnauthenticatedState()
    }

    override suspend fun deleteAccount(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Use server endpoint for account deletion"))

    override suspend fun refreshTokenIfNeeded(): String? = try {
        client.auth.currentSessionOrNull()?.accessToken
    } catch (e: Exception) {
        log.w { "Token refresh failed: ${e.message}" }
        _authState.value.accessToken
    }

    private fun clearCachedAuth() {
        settings.remove(SETTINGS_KEY_AUTH_ACCESS_TOKEN)
        settings.remove(SETTINGS_KEY_AUTH_REFRESH_TOKEN)
        settings.remove(SETTINGS_KEY_AUTH_USER_ID)
        settings.remove(SETTINGS_KEY_AUTH_TIER)
        settings.remove(SETTINGS_KEY_AUTH_PENDING_SIGN_IN_EMAIL)
        clearPendingEmailConfirmation()
        settings.remove(SETTINGS_KEY_AUTH_PASSWORD_SETUP_USER_ID)
    }

    private fun setUnauthenticatedState() {
        clearCachedAuth()
        _authState.value = AuthState(
            isAuthenticated = false,
            userId = null,
            email = null,
            tier = UserTier.ANONYMOUS,
            accessToken = null,
            isAnonymous = true,
            deviceId = deviceId
        )
    }

    private suspend fun updateAuthenticatedState(
        userId: String,
        sessionEmail: String?,
        emailConfirmed: Boolean,
        token: String,
        refreshToken: String
    ) {
        val pendingEmail = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL)
        val pendingConfirmation = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION) == "true"
        val passwordSetupUserId = settings.getStringOrNull(SETTINGS_KEY_AUTH_PASSWORD_SETUP_USER_ID)
        val profile = if (emailConfirmed) fetchUserProfile(userId, token) else null
        val state = resolveAuthenticatedState(
            userId = userId,
            sessionEmail = sessionEmail,
            emailConfirmed = emailConfirmed,
            token = token,
            deviceId = deviceId,
            profileTier = profile?.tier?.let(::parseStoredTier),
            pendingEmail = pendingEmail,
            pendingEmailConfirmation = pendingConfirmation,
            needsPasswordSetup = when (profile?.hasPassword) {
                true -> false
                false -> true
                null -> passwordSetupUserId == userId
            }
        )
        settings.putString(SETTINGS_KEY_AUTH_ACCESS_TOKEN, token)
        settings.putString(SETTINGS_KEY_AUTH_REFRESH_TOKEN, refreshToken)
        settings.putString(SETTINGS_KEY_AUTH_USER_ID, userId)
        settings.putString(SETTINGS_KEY_AUTH_TIER, state.tier.name)
        if (!state.pendingEmailConfirmation) clearPendingEmailConfirmation()
        _authState.value = state
        log.i { "Authenticated: userId=$userId, anon=${state.isAnonymous}, tier=${state.tier}, pendingEmailConfirmation=${state.pendingEmailConfirmation}" }
    }

    private suspend fun fetchUserProfile(userId: String, token: String): UserProfileAuthRow? = runCatching {
        httpClient.get(profilesUrl) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $token")
            parameter("select", "tier,has_password")
            parameter("user_id", "eq.$userId")
        }.body<List<UserProfileAuthRow>>()
            .firstOrNull()
    }.getOrElse { error ->
        log.w { "Supabase profile auth state fetch failed: ${error.message}" }
        null
    }

    private fun cachePendingEmailConfirmation(userId: String, email: String) {
        settings.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION, "true")
        settings.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL, email)
        settings.putString(SETTINGS_KEY_AUTH_TIER, UserTier.ANONYMOUS.name)
        settings.putString(SETTINGS_KEY_AUTH_PASSWORD_SETUP_USER_ID, userId)
    }

    private fun clearPendingEmailConfirmation() {
        settings.remove(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION)
        settings.remove(SETTINGS_KEY_AUTH_PENDING_EMAIL)
    }
}

internal fun parseStoredTier(value: String?): UserTier? =
    when (value?.trim()?.uppercase()) {
        UserTier.ANONYMOUS.name -> UserTier.ANONYMOUS
        UserTier.FREE.name -> UserTier.FREE
        UserTier.PAID.name -> UserTier.PAID
        else -> null
    }

internal fun mayImportAuthCallback(
    linkUserId: String,
    linkEmail: String?,
    currentUserId: String?,
    requestedEmail: String?,
    heldAccountId: String?
): Boolean =
    (heldAccountId == null || heldAccountId == linkUserId) &&
        (linkUserId == currentUserId ||
            (!requestedEmail.isNullOrBlank() && linkEmail?.equals(requestedEmail, ignoreCase = true) == true))

internal fun resolveAuthenticatedState(
    userId: String,
    sessionEmail: String?,
    emailConfirmed: Boolean,
    token: String,
    deviceId: String,
    profileTier: UserTier?,
    pendingEmail: String?,
    pendingEmailConfirmation: Boolean,
    needsPasswordSetup: Boolean
): AuthState {
    val keepPendingConfirmation =
        pendingEmailConfirmation &&
            !pendingEmail.isNullOrBlank() &&
            !emailConfirmed
    val effectiveEmail = if (keepPendingConfirmation) pendingEmail else sessionEmail
    val effectiveTier = when {
        !emailConfirmed -> UserTier.ANONYMOUS
        profileTier == UserTier.PAID -> UserTier.PAID
        else -> UserTier.FREE
    }
    return AuthState(
        isAuthenticated = true,
        userId = userId,
        email = effectiveEmail,
        tier = effectiveTier,
        accessToken = token,
        isAnonymous = !emailConfirmed,
        deviceId = deviceId,
        pendingEmailConfirmation = keepPendingConfirmation,
        pendingEmail = if (keepPendingConfirmation) pendingEmail else null,
        needsPasswordSetup = emailConfirmed && needsPasswordSetup
    )
}

@Serializable
private data class UserProfileAuthRow(
    @SerialName("tier")
    val tier: String? = null,
    @SerialName("has_password")
    val hasPassword: Boolean? = null
)
