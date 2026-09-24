package finance.shilling.core.auth

import kotlinx.serialization.Serializable

@Serializable
enum class AuthMode { NONE, SUPABASE }

@Serializable
data class ServerConfig(
    val authMode: AuthMode,
    val supabaseUrl: String? = null,
    val supabaseAnonKey: String? = null
)

@Serializable
data class HostedHouseholdResponse(
    val householdId: String
)
