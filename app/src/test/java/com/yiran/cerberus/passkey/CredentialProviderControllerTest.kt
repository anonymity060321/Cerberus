package com.yiran.cerberus.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialProviderControllerTest {
    @Test
    fun credentialManagerConfirmationTakesPriority() {
        var autofillChecked = false
        var settingsChecked = false
        assertEquals(
            CredentialProviderStatus.ENABLED,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = { true },
                isCerberusAutofillEnabled = {
                    autofillChecked = true
                    true
                },
                isSettingsEntryAvailable = {
                    settingsChecked = true
                    true
                }
            )
        )
        assertFalse(autofillChecked)
        assertFalse(settingsChecked)
    }

    @Test
    fun autofillWithoutCredentialManagerIsAutofillOnly() {
        var settingsChecked = false
        assertEquals(
            CredentialProviderStatus.AUTOFILL_ONLY,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = { false },
                isCerberusAutofillEnabled = { true },
                isSettingsEntryAvailable = {
                    settingsChecked = true
                    true
                }
            )
        )
        assertFalse(settingsChecked)
    }

    @Test
    fun availableSettingsWithoutAuthorizationIsDisabled() {
        assertEquals(
            CredentialProviderStatus.DISABLED,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = { false },
                isCerberusAutofillEnabled = { false },
                isSettingsEntryAvailable = { true }
            )
        )
    }

    @Test
    fun missingSettingsEntryIsUnsupported() {
        assertEquals(
            CredentialProviderStatus.UNSUPPORTED,
            resolveCredentialProviderStatus(
                isCredentialProviderEnabled = { false },
                isCerberusAutofillEnabled = { false },
                isSettingsEntryAvailable = { false }
            )
        )
    }
}
