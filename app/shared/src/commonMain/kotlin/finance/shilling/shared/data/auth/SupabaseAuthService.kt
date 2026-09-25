package finance.shilling.shared.data.auth

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_ACCESS_TOKEN
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_PENDING_EMAIL
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_REFRESH_TOKEN
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_TIER
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_USER_ID
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val httpClient: HttpClient
) : AuthService {
    private val initializationComplete = CompletableDeferred<Unit>()
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
                        if (!initializationComplete.isCompleted) initializationComplete.complete(Unit)
                        val session = status.session
                        val user = session.user
                        val userId = user?.id ?: return@collect
                        val token = session.accessToken
                        val storedTier = parseStoredTier(settings.getStringOrNull(SETTINGS_KEY_AUTH_TIER))
                        val pendingEmail = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL)
                        val pendingConfirmation = settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION) == "true"
                        val profileTier = if (user.email.isNullOrBlank() && !pendingConfirmation) {
                            null
                        } else {
                            fetchProfileTier(userId, token)
                        }
                        val authState = resolveAuthenticatedState(
                            userId = userId,
                            sessionEmail = user.email,
                            token = token,
                            deviceId = deviceId,
                            storedTier = storedTier,
                            profileTier = profileTier,
                            pendingEmail = pendingEmail,
                            pendingEmailConfirmation = pendingConfirmation
                        )

                        settings.putString(SETTINGS_KEY_AUTH_ACCESS_TOKEN, token)
                        settings.putString(SETTINGS_KEY_AUTH_REFRESH_TOKEN, session.refreshToken)
                        settings.putString(SETTINGS_KEY_AUTH_USER_ID, userId)
                        settings.putString(SETTINGS_KEY_AUTH_TIER, authState.tier.name)
                        if (authState.pendingEmailConfirmation) {
                            settings.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION, "true")
                            authState.pendingEmail?.let { settings.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL, it) }
                        } else {
                            clearPendingEmailConfirmation()
                        }

                        _authState.value = authState
                        log.i {
                            "Authenticated: userId=$userId, anon=${authState.isAnonymous}, tier=${authState.tier}, pendingEmailConfirmation=${authState.pendingEmailConfirmation}"
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        if (!initializationComplete.isCompleted) initializationComplete.complete(Unit)
                        setUnauthenticatedState()
                        log.i { "Not authenticated" }
                    }
                    is SessionStatus.Initializing -> {
                        log.d { "Auth initializing..." }
                    }
                    is SessionStatus.RefreshFailure -> {
                        if (!initializationComplete.isCompleted) initializationComplete.complete(Unit)
                        setUnauthenticatedState()
                        log.w { "Token refresh failed: ${status.cause}" }
                    }
                }
            }
        }
    }

    override suspend fun ensureAuthenticated(): Result<Unit> = runCatching {
        withTimeoutOrNull(5_000) { initializationComplete.await() }
        if (_authState.value.isAuthenticated) return@runCatching
        client.auth.signInAnonymously()
        withTimeoutOrNull(10_000) {
            authState
                .filter { it.isAuthenticated }
                .first()
        }
        if (_authState.value.isAuthenticated) {
            log.i { "Anonymous sign-in successful" }
        } else {
            log.w { "Anonymous sign-in did not reach authenticated state before timeout" }
            error("Anonymous sign-in did not complete before timeout")
        }
    }.onFailure { error ->
        log.w { "Anonymous sign-in failed: ${error.message}" }
    }

    override suspend fun signUp(email: String, password: String): Result<SignUpResult> = runCatching {
        if (_authState.value.isAnonymous && _authState.value.isAuthenticated) {
            // Upgrade anonymous user — preserves UUID
            client.auth.updateUser {
                this.email = email
                this.password = password
            }
            cachePendingEmailConfirmation(email)
            _authState.value = _authState.value.copy(
                email = email,
                tier = UserTier.FREE,
                isAnonymous = false,
                pendingEmailConfirmation = true,
                pendingEmail = email
            )
            log.i { "Upgraded anonymous user to email account" }
            SignUpResult(
                requiresEmailConfirmation = true,
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
        clearPendingEmailConfirmation()
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

    private suspend fun fetchProfileTier(userId: String, token: String): UserTier? = runCatching {
        httpClient.get(profilesUrl) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $token")
            parameter("select", "tier")
            parameter("user_id", "eq.$userId")
        }.body<List<UserProfileTierRow>>()
            .firstOrNull()
            ?.tier
            ?.let(::parseStoredTier)
    }.getOrElse { error ->
        log.w { "Supabase profile tier fetch failed: ${error.message}" }
        null
    }

    private fun cachePendingEmailConfirmation(email: String) {
        settings.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION, "true")
        settings.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL, email)
        settings.putString(SETTINGS_KEY_AUTH_TIER, UserTier.FREE.name)
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

internal fun resolveAuthenticatedState(
    userId: String,
    sessionEmail: String?,
    token: String,
    deviceId: String,
    storedTier: UserTier?,
    profileTier: UserTier?,
    pendingEmail: String?,
    pendingEmailConfirmation: Boolean
): AuthState {
    val keepPendingConfirmation =
        pendingEmailConfirmation &&
            !pendingEmail.isNullOrBlank() &&
            sessionEmail.isNullOrBlank()
    val effectiveEmail = if (keepPendingConfirmation) pendingEmail else sessionEmail
    val effectiveTier = when {
        keepPendingConfirmation -> UserTier.FREE
        profileTier != null -> profileTier
        sessionEmail.isNullOrBlank() -> UserTier.ANONYMOUS
        storedTier == UserTier.PAID -> UserTier.PAID
        else -> UserTier.FREE
    }
    return AuthState(
        isAuthenticated = true,
        userId = userId,
        email = effectiveEmail,
        tier = effectiveTier,
        accessToken = token,
        isAnonymous = sessionEmail.isNullOrBlank() && !keepPendingConfirmation,
        deviceId = deviceId,
        pendingEmailConfirmation = keepPendingConfirmation,
        pendingEmail = if (keepPendingConfirmation) pendingEmail else null
    )
}

@Serializable
private data class UserProfileTierRow(
    @SerialName("tier")
    val tier: String? = null
)
