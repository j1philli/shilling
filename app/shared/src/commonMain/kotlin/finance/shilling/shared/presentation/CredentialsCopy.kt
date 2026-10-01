package finance.shilling.shared.presentation

import finance.shilling.shared.data.auth.AuthErrors

import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.HostedCredentialsSubmitResult

/** Result of an email/password submit, ready for a UI to show. */
data class CredentialsOutcome(
    val succeeded: Boolean,
    val message: String,
    /** Non-null when a "Confirm your email" prompt should appear. */
    val confirmEmailMessage: String?,
    val showPasswordInput: Boolean = false,
    val emailSent: Boolean = false
)

/** Copy for the email/password form (Welcome and Settings), shared by every UI. */
object CredentialsCopy {
    fun prompt(mode: HostedCredentialsMode, guestUpgrade: Boolean = false): String = when (mode) {
        HostedCredentialsMode.SIGN_IN -> "Enter your password to sign in."
        HostedCredentialsMode.CREATE_ACCOUNT -> if (guestUpgrade) {
            "Enter your email to continue with this guest budget."
        } else {
            "Enter your email to create or find your account."
        }
    }

    fun submitLabel(mode: HostedCredentialsMode): String = "Continue"

    /** Status line after a submit. */
    fun resultMessage(result: Result<HostedCredentialsSubmitResult>): String = result.fold(
        onSuccess = { submit ->
            when {
                submit.signUpResult?.existingAccountHasPassword == true -> "Enter your password to sign in."
                submit.signUpResult?.existingAccount == true && submit.signUpResult?.signInLinkSent == true ->
                    "Check your email for a sign-in link. Open it on this device."
                submit.signUpResult?.existingAccount == true -> "Could not send a sign-in link. Try again."
                submit.mode == HostedCredentialsMode.SIGN_IN -> "Signed in."
                submit.signUpResult?.requiresEmailConfirmation == true ->
                    "Check your email to confirm this account change."
                submit.signUpResult?.upgradedAnonymousSession == true ->
                    "Email linked. Set a password in Account settings."
                else -> "Account created."
            }
        },
        onFailure = { AuthErrors.message(it, "submit_credentials") }
    )

    /** Whether to show the "Confirm your email" sheet after [result]. */
    fun needsEmailConfirmation(result: Result<HostedCredentialsSubmitResult>): Boolean =
        result.getOrNull()?.signUpResult?.requiresEmailConfirmation == true

    const val CONFIRM_EMAIL_TITLE = "Confirm your email"

    fun confirmEmailMessage(email: String, upgradedFromGuest: Boolean): String =
        if (upgradedFromGuest) {
            "We sent a confirmation email to $email. Open it on this device, then the app will ask you to set a password. Your guest data stays with this account."
        } else {
            "We sent a confirmation email to $email. Open it to finish creating your account."
        }

    const val CONFIRM_EMAIL_NOTE = "Until you confirm it, this account change is still pending in Supabase Auth."

    /** [result] of submitting [email], as a status line plus the confirm-email prompt if needed. */
    fun outcome(result: Result<HostedCredentialsSubmitResult>, email: String): CredentialsOutcome = CredentialsOutcome(
        succeeded = result.isSuccess,
        message = resultMessage(result),
        showPasswordInput = result.getOrNull()?.signUpResult?.existingAccountHasPassword == true,
        emailSent = needsEmailConfirmation(result) || result.getOrNull()?.signUpResult?.signInLinkSent == true,
        confirmEmailMessage = if (needsEmailConfirmation(result)) {
            confirmEmailMessage(
                email,
                upgradedFromGuest = result.getOrNull()?.signUpResult?.upgradedAnonymousSession == true
            )
        } else {
            null
        }
    )
}
