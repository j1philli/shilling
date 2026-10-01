package finance.shilling.core.auth

import kotlinx.serialization.Serializable

@Serializable
data class HostedSpace(val id: String, val name: String, val kind: String, val role: String)
@Serializable
data class HostedSpaceMember(val userId: String, val email: String?, val role: String)
@Serializable
data class HostedSpaceInvitation(val id: String, val email: String, val role: String, val expiresAt: String)
@Serializable
data class HostedSpacesResponse(
    val activeSpaceId: String? = null,
    val spaces: List<HostedSpace> = emptyList(),
    val members: List<HostedSpaceMember> = emptyList(),
    val invitations: List<HostedSpaceInvitation> = emptyList(),
    val invitationCode: String? = null,
    val requiresSpaceSelection: Boolean = false
)
@Serializable
data class HostedSpaceCommand(
    val action: String,
    val spaceId: String? = null,
    val name: String? = null,
    val kind: String? = null,
    val email: String? = null,
    val role: String? = null,
    val userId: String? = null,
    val invitationId: String? = null,
    val invitationCode: String? = null
)
