package com.yiran.cerberus.passkey

import android.content.ComponentName
import android.content.Context
import android.view.autofill.AutofillManager
import androidx.credentials.CredentialManager

/**
 * Reports the system authorization state for Cerberus as a passkey provider.
 *
 * Credential Manager is authoritative for passkeys. HyperOS may authorize only the separate
 * Autofill Service, so that state must never be presented as an enabled passkey provider.
 */
class CredentialProviderController(context: Context) {
    private val appContext = context.applicationContext
    private val providerComponent = ComponentName(
        appContext,
        CerberusCredentialProviderService::class.java
    )

    fun currentStatus(): CredentialProviderStatus = resolveCredentialProviderStatus(
        isCredentialProviderEnabled = ::isCredentialProviderEnabled,
        isCerberusAutofillEnabled = ::isAutofillServiceEnabled,
        isSettingsEntryAvailable = ::isSettingsEntryAvailable
    )

    private fun isCredentialProviderEnabled(): Boolean = runCatching {
        appContext.getSystemService(android.credentials.CredentialManager::class.java)
            ?.isEnabledCredentialProviderService(providerComponent) == true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun isAutofillServiceEnabled(): Boolean = runCatching {
        appContext.getSystemService(AutofillManager::class.java)
            ?.hasEnabledAutofillServices() == true
    }.getOrDefault(false)

    private fun isSettingsEntryAvailable(): Boolean = runCatching {
        CredentialManager.create(appContext).createSettingsPendingIntent()
        true
    }.getOrDefault(false)
}

enum class CredentialProviderStatus(val displayText: String) {
    ENABLED("已启用"),
    AUTOFILL_ONLY("仅自动填充"),
    DISABLED("未启用"),
    UNSUPPORTED("系统不支持")
}

internal fun resolveCredentialProviderStatus(
    isCredentialProviderEnabled: () -> Boolean,
    isCerberusAutofillEnabled: () -> Boolean,
    isSettingsEntryAvailable: () -> Boolean
): CredentialProviderStatus = when {
    isCredentialProviderEnabled() -> CredentialProviderStatus.ENABLED
    isCerberusAutofillEnabled() -> CredentialProviderStatus.AUTOFILL_ONLY
    isSettingsEntryAvailable() -> CredentialProviderStatus.DISABLED
    else -> CredentialProviderStatus.UNSUPPORTED
}
