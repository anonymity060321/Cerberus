package com.yiran.cerberus.passkey

import android.content.ComponentName
import android.content.Context
import android.view.autofill.AutofillManager
import androidx.credentials.CredentialManager

/**
 * Single source of truth for Credential Manager provider state.
 *
 * Autofill and Credential Manager are separate system services. Some OEM settings screens only
 * update Autofill even when they present a combined picker, so Autofill must never be treated as
 * proof that the passkey provider is enabled.
 */
class CredentialProviderController(context: Context) {
    private val appContext = context.applicationContext
    private val providerComponent = ComponentName(
        appContext,
        CerberusCredentialProviderService::class.java
    )

    fun currentStatus(): CredentialProviderStatus {
        if (isCredentialProviderEnabled()) {
            return CredentialProviderStatus.ENABLED
        }
        if (isAutofillServiceEnabled()) {
            return CredentialProviderStatus.AUTOFILL_ONLY
        }
        return if (canOpenCredentialProviderSettings()) {
            CredentialProviderStatus.DISABLED
        } else {
            CredentialProviderStatus.SETTINGS_UNAVAILABLE
        }
    }

    fun openCredentialProviderSettings(): Boolean = runCatching {
        CredentialManager.create(appContext)
            .createSettingsPendingIntent()
            .send()
        true
    }.getOrDefault(false)

    private fun isCredentialProviderEnabled(): Boolean = runCatching {
        appContext.getSystemService(android.credentials.CredentialManager::class.java)
            ?.isEnabledCredentialProviderService(providerComponent) == true
    }.getOrDefault(false)

    private fun isAutofillServiceEnabled(): Boolean = runCatching {
        appContext.getSystemService(AutofillManager::class.java)
            ?.hasEnabledAutofillServices() == true
    }.getOrDefault(false)

    private fun canOpenCredentialProviderSettings(): Boolean = runCatching {
        CredentialManager.create(appContext).createSettingsPendingIntent()
        true
    }.getOrDefault(false)
}

enum class CredentialProviderStatus(val displayText: String) {
    ENABLED("已启用"),
    AUTOFILL_ONLY("仅自动填充"),
    DISABLED("未启用"),
    SETTINGS_UNAVAILABLE("系统不支持")
}
