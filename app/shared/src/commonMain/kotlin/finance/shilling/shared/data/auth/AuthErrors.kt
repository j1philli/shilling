package finance.shilling.shared.data.auth

import co.touchlab.kermit.Logger
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException
import finance.shilling.shared.session.NonMatchingAccountException
import kotlinx.coroutines.TimeoutCancellationException

/** Keep provider responses, request bodies and credentials out of UI and logs. */
object AuthErrors {
    private val log = Logger.withTag("AuthFailure")

    fun logFailure(operation: String, error: Throwable) {
        log.w { diagnostic(operation, error) }
    }

    internal fun diagnostic(operation: String, error: Throwable): String =
        "operation=$operation type=${error::class.simpleName} " +
            "code=${(error as? AuthRestException)?.errorCode?.value ?: "unknown"} " +
            "status=${(error as? RestException)?.statusCode ?: "none"}"

    fun message(error: Throwable, operation: String): String {
        logFailure(operation, error)
        if (error is NonMatchingAccountException) return "Sign in with the account that owns the budget saved on this device."
        if (error is TimeoutCancellationException) return "This is taking longer than expected. Please try again."
        return messageForCode((error as? AuthRestException)?.errorCode?.value, (error as? RestException)?.statusCode)
    }

    internal fun messageForCode(code: String?, status: Int? = null): String = when (code) {
        "over_email_send_rate_limit" -> "We can't send another email right now. Please try again later."
        "over_request_rate_limit" -> "Too many attempts. Please wait a little before trying again."
        "invalid_credentials" -> "That email and password don't match. Please try again."
        "email_address_invalid", "validation_failed" -> "Check your email address and try again."
        "email_not_confirmed" -> "Confirm your email before signing in. Check your inbox for the confirmation link."
        "weak_password" -> "Choose a stronger password with a mix of letters, numbers, and symbols."
        "same_password" -> "Choose a different password."
        "otp_expired", "flow_state_expired" -> "This link has expired or was already used. Please request a new one."
        "session_expired", "session_not_found", "refresh_token_not_found" -> "Your session has expired. Please sign in again."
        "request_timeout" -> "This is taking longer than expected. Please try again."
        "email_address_not_authorized", "email_provider_disabled", "hook_timeout", "hook_timeout_after_retry" ->
            "We couldn't send your email right now. Please try again later."
        else -> if (status == 429) "Too many attempts. Please wait a little before trying again."
            else "We couldn't complete that request. Please check your connection and try again."
    }
}
