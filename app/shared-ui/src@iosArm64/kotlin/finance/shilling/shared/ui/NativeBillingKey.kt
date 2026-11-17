package finance.shilling.shared.ui

import finance.shilling.core.auth.HostedBillingConfig

internal actual fun nativeBillingPublicKey(config: HostedBillingConfig): String? = config.iosPublicKey
