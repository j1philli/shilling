package finance.shilling.shared.data.auth

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class UserTier { ANONYMOUS, FREE, PAID }

@Serializable
data class AuthState(
    val isAuthenticated: Boolean,
    val userId: String?,
    val email: String?,
    val tier: UserTier,
    val accessToken: String?,
    val isAnonymous: Boolean,
    val deviceId: String,
    val pendingEmailConfirmation: Boolean = false,
    val pendingEmail: String? = null
)

@Serializable
data class SignUpResult(
    val requiresEmailConfirmation: Boolean,
    val upgradedAnonymousSession: Boolean
)

interface AuthService {
    val authState: StateFlow<AuthState>
    suspend fun ensureAuthenticated(): Result<Unit>
    suspend fun signUp(email: String, password: String): Result<SignUpResult>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun signOut()
    suspend fun deleteAccount(): Result<Unit>
    suspend fun refreshTokenIfNeeded(): String?
}
