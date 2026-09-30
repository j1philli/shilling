package finance.shilling.shared.presentation

import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.HostedCredentialsSubmitResult

/** Result of an email/password submit, ready for a UI to show. */
data class CredentialsOutcome(
    val succeeded: Boolean,
    val message: String,
    /** Non-null when a "Confirm your email" prompt should appear. */
    val confirmEmailMessage: String?
)

/** Copy for the email/password form (Welcome and Settings), shared by every UI. */
object CredentialsCopy {
    fun prompt(mode: HostedCredentialsMode, guestUpgrade: Boolean = false): String = when (mode) {
        HostedCredentialsMode.SIGN_IN -> "Sign in to an existing account."
        HostedCredentialsMode.CREATE_ACCOUNT -> if (guestUpgrade) {
            "Add an email to this guest account. After confirming it, set a password in Account settings."
        } else {
            "Create a new account with email and password."
        }
    }

    fun submitLabel(mode: HostedCredentialsMode, signInLabel: String = "Sign in"): String = when (mode) {
        HostedCredentialsMode.SIGN_IN -> signInLabel
        HostedCredentialsMode.CREATE_ACCOUNT -> "Create account"
    }

    fun switchLabel(mode: HostedCredentialsMode): String = when (mode) {
        HostedCredentialsMode.SIGN_IN -> "Create a new account instead"
        HostedCredentialsMode.CREATE_ACCOUNT -> "Already have an account? Sign in"
    }

    fun other(mode: HostedCredentialsMode): HostedCredentialsMode = when (mode) {
        HostedCredentialsMode.SIGN_IN -> HostedCredentialsMode.CREATE_ACCOUNT
        HostedCredentialsMode.CREATE_ACCOUNT -> HostedCredentialsMode.SIGN_IN
    }

    /** Status line after a submit. */
    fun resultMessage(result: Result<HostedCredentialsSubmitResult>): String = result.fold(
        onSuccess = { submit ->
            when {
                submit.mode == HostedCredentialsMode.SIGN_IN -> "Signed in."
                submit.signUpResult?.requiresEmailConfirmation == true ->
                    "Check your email to confirm this account change."
                submit.signUpResult?.upgradedAnonymousSession == true ->
                    "Email linked. Set a password in Account settings."
                else -> "Account created."
            }
        },
        onFailure = { "Error: ${it.message ?: "Unknown error"}" }
    )

    /** Whether to show the "Confirm your email" sheet after [result]. */
    fun needsEmailConfirmation(result: Result<HostedCredentialsSubmitResult>): Boolean =
        result.getOrNull()?.signUpResult?.requiresEmailConfirmation == true

    const val CONFIRM_EMAIL_TITLE = "Confirm your email"

    fun confirmEmailMessage(email: String, upgradedFromGuest: Boolean): String =
        if (upgradedFromGuest) {
            "We sent a confirmation email to $email. Open it, then return to Account settings to set a password. Your guest data stays with this account."
        } else {
            "We sent a confirmation email to $email. Open it to finish creating your account."
        }

    const val CONFIRM_EMAIL_NOTE = "Until you confirm it, this account change is still pending in Supabase Auth."

    /** [result] of submitting [email], as a status line plus the confirm-email prompt if needed. */
    fun outcome(result: Result<HostedCredentialsSubmitResult>, email: String): CredentialsOutcome = CredentialsOutcome(
        succeeded = result.isSuccess,
        message = resultMessage(result),
        confirmEmailMessage = if (needsEmailConfirmation(result)) {
            confirmEmailMessage(
                email,
                upgradedFromGuest = result.getOrNull()?.signUpResult?.upgradedAnonymousSession == true
            ) + "\n\n" + CONFIRM_EMAIL_NOTE
        } else {
            null
        }
    )
}
