package finance.shilling.core.auth

import kotlinx.serialization.Serializable

/** Server-owned registration metadata. Connection state is observed only on this client. */
@Serializable
data class RegisteredDevice(
    val deviceId: String,
    val ownerUserId: String? = null,
    val registeredAt: String,
    val lastSeenAt: String? = null
)
