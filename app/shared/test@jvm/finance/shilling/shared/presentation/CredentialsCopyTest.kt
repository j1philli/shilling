package finance.shilling.shared.presentation

import finance.shilling.shared.data.auth.SignUpResult
import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.HostedCredentialsSubmitResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialsCopyTest {
    @Test
    fun existingPasswordAccountOpensPasswordStep() {
        val result = Result.success(
            HostedCredentialsSubmitResult(
                mode = HostedCredentialsMode.CREATE_ACCOUNT,
                signUpResult = SignUpResult(
                    requiresEmailConfirmation = false,
                    upgradedAnonymousSession = false,
                    existingAccount = true,
                    existingAccountHasPassword = true
                )
            )
        )

        val outcome = CredentialsCopy.outcome(result, "user@example.com")
        assertTrue(outcome.showPasswordInput)
        assertFalse(outcome.emailSent)
        assertTrue(outcome.message.contains("password"))
        assertFalse(CredentialsCopy.needsEmailConfirmation(result))
    }

    @Test
    fun existingPasswordlessAccountGetsEmailWithoutPasswordStep() {
        val result = Result.success(
            HostedCredentialsSubmitResult(
                mode = HostedCredentialsMode.CREATE_ACCOUNT,
                signUpResult = SignUpResult(
                    requiresEmailConfirmation = false,
                    upgradedAnonymousSession = false,
                    existingAccount = true,
                    signInLinkSent = true
                )
            )
        )

        val outcome = CredentialsCopy.outcome(result, "user@example.com")
        assertFalse(outcome.showPasswordInput)
        assertTrue(outcome.emailSent)
        assertTrue(outcome.message.contains("sign-in link"))
        assertFalse(CredentialsCopy.needsEmailConfirmation(result))
    }

    @Test
    fun newEmailGetsAnExplicitConfirmationStep() {
        val result = Result.success(HostedCredentialsSubmitResult(
            mode = HostedCredentialsMode.CREATE_ACCOUNT,
            signUpResult = SignUpResult(requiresEmailConfirmation = true, upgradedAnonymousSession = true)
        ))
        val outcome = CredentialsCopy.outcome(result, "user@example.com")
        assertTrue(outcome.emailSent)
        assertTrue(outcome.confirmEmailMessage!!.contains("user@example.com"))
        assertFalse(outcome.showPasswordInput)
    }
}
