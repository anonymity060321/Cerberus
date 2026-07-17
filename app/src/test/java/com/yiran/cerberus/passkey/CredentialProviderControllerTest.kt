package com.yiran.cerberus.passkey

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialProviderControllerTest {
    @Test
    fun credentialManagerConfirmationTakesPriority() {
        assertEquals(
            CredentialProviderStatus.ENABLED,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = true,
                isCerberusAutofillEnabled = true,
                isSettingsEntryAvailable = true
            )
        )
    }

    @Test
    fun autofillWithoutCredentialManagerIsAutofillOnly() {
        assertEquals(
            CredentialProviderStatus.AUTOFILL_ONLY,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = false,
                isCerberusAutofillEnabled = true,
                isSettingsEntryAvailable = true
            )
        )
    }

    @Test
    fun availableSettingsWithoutAuthorizationIsDisabled() {
        assertEquals(
            CredentialProviderStatus.DISABLED,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = false,
                isCerberusAutofillEnabled = false,
                isSettingsEntryAvailable = true
            )
        )
    }

    @Test
    fun missingSettingsEntryIsUnsupported() {
        assertEquals(
            CredentialProviderStatus.UNSUPPORTED,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = false,
                isCerberusAutofillEnabled = false,
                isSettingsEntryAvailable = false
            )
        )
    }
}
