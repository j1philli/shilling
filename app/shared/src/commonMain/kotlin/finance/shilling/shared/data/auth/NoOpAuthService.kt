package finance.shilling.shared.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NoOpAuthService(deviceId: String) : AuthService {
    private val _authState = MutableStateFlow(
        AuthState(
            isAuthenticated = false,
            userId = null,
            email = null,
            tier = UserTier.ANONYMOUS,
            accessToken = null,
            isAnonymous = true,
            deviceId = deviceId
        )
    )
    override val authState: StateFlow<AuthState> = _authState

    override suspend fun ensureAuthenticated(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Auth not available"))

    override suspend fun signUp(email: String, password: String): Result<SignUpResult> =
        Result.failure(UnsupportedOperationException("Auth not available"))

    override suspend fun signIn(email: String, password: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Auth not available"))

    override suspend fun signOut() {}

    override suspend fun deleteAccount(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Auth not available"))

    override suspend fun refreshTokenIfNeeded(): String? = null
}
