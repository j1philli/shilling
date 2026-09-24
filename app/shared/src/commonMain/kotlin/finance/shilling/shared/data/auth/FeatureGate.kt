package finance.shilling.shared.data.auth

enum class Feature {
    LOCAL_BUDGETING,
    P2P_SYNC,
    SIGNALING,
    FEEDBACK,
    SERVER_BACKUP,
    TURN_RELAY,
    AI_FEATURES
}

class FeatureGate(
    private val authService: AuthService,
    private val selfHosted: Boolean
) {
    fun isEnabled(feature: Feature): Boolean {
        if (selfHosted) return true
        val tier = authService.authState.value.tier
        return when (feature) {
            Feature.LOCAL_BUDGETING, Feature.P2P_SYNC -> true
            Feature.SIGNALING -> tier >= UserTier.ANONYMOUS
            Feature.FEEDBACK -> tier >= UserTier.FREE
            Feature.SERVER_BACKUP, Feature.TURN_RELAY, Feature.AI_FEATURES -> tier >= UserTier.PAID
        }
    }
}
