package finance.shilling.shared.session

import finance.shilling.shared.data.auth.SignUpResult

enum class HostedCredentialsMode { SIGN_IN, CREATE_ACCOUNT }

data class HostedCredentialsSubmitResult(
    val mode: HostedCredentialsMode,
    val signUpResult: SignUpResult? = null
)

/**
 * Thrown by welcome credential submit when auth succeeded but the account does not match
 * held local data. The Welcome screen prompts before wiping.
 */
class NonMatchingAccountException(
    message: String = "That account doesn't match the budget on this device."
) : Exception(message)
