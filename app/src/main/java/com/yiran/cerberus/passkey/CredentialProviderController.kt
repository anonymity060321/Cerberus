package com.yiran.cerberus.passkey

import android.content.ComponentName
import android.content.Context
import androidx.credentials.CredentialManager

/**
 * Android 16 Credential Manager provider state and settings entry point.
 *
 * Passwords and passkeys are exposed by the same CredentialProviderService. Legacy Autofill state
 * is deliberately not consulted because it is a separate system service and cannot prove that the
 * credential provider is enabled.
 */
class CredentialProviderController(context: Context) {
    private val appContext = context.applicationContext
    private val providerComponent = ComponentName(
        appContext,
        CerberusCredentialProviderService::class.java
    )

    fun currentStatus(): CredentialProviderStatus {
        if (isEnabled()) return CredentialProviderStatus.ENABLED
        return if (settingsPendingIntentAvailable()) {
            CredentialProviderStatus.DISABLED
        } else {
            CredentialProviderStatus.SETTINGS_UNAVAILABLE
        }
    }

    fun openSettings(): Boolean = runCatching {
        CredentialManager.create(appContext)
            .createSettingsPendingIntent()
            .send()
        true
    }.getOrDefault(false)

    private fun isEnabled(): Boolean = runCatching {
        appContext.getSystemService(android.credentials.CredentialManager::class.java)
            ?.isEnabledCredentialProviderService(providerComponent) == true
    }.getOrDefault(false)

    private fun settingsPendingIntentAvailable(): Boolean = runCatching {
        CredentialManager.create(appContext).createSettingsPendingIntent()
        true
    }.getOrDefault(false)
}

enum class CredentialProviderStatus(val displayText: String) {
    ENABLED("已启用"),
    DISABLED("未启用"),
    SETTINGS_UNAVAILABLE("系统设置不可用")
}
