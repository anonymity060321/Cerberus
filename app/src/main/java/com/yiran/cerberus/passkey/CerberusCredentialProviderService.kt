package com.yiran.cerberus.passkey

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.yiran.cerberus.util.SecurityUtil
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CerberusCredentialProviderService : CredentialProviderService() {
    private val requestCode = AtomicInteger()

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException("Cerberus 仅支持 Passkey"))
            return
        }

        val passkeyCount = PasskeyStore.count(applicationContext)
        val entry = CreateEntry.Builder(
            getString(com.yiran.cerberus.R.string.app_name),
            pendingIntent(PasskeyCreateActivity::class.java)
        )
            .setDescription("使用 Cerberus 的安全硬件保存通行密钥")
            .setPublicKeyCredentialCount(passkeyCount)
            .setTotalCredentialCount(passkeyCount)
            .build()

        callback.onResult(
            BeginCreateCredentialResponse.Builder()
                .addCreateEntry(entry)
                .build()
        )
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return
        val response = BeginGetCredentialResponse.Builder()
        var matchCount = 0

        // Keep account metadata locked. HyperOS only sees one generic password entry; usernames and
        // passwords are loaded after Cerberus authentication in AutofillAuthActivity.
        request.beginGetCredentialOptions
            .filterIsInstance<BeginGetPasswordOption>()
            .forEach { option ->
                if (cancellationSignal.isCanceled) return
                if (
                    !SecurityUtil.isPasswordAutofillEnabled(applicationContext) ||
                    !SecurityUtil.isMasterPasswordSet(applicationContext) ||
                    !hasMatchingPasswordCredential(option)
                ) {
                    return@forEach
                }

                val entry = PasswordCredentialEntry.Builder(
                    applicationContext,
                    getString(com.yiran.cerberus.R.string.app_name),
                    passwordPendingIntent(),
                    option
                )
                    .setDisplayName("验证后选择账号")
                    .setAutoSelectAllowed(false)
                    .build()
                response.addCredentialEntry(entry)
                matchCount++
            }

        request.beginGetCredentialOptions
            .filterIsInstance<BeginGetPublicKeyCredentialOption>()
            .forEach { option ->
                if (cancellationSignal.isCanceled) return
                val requestedRpId = runCatching {
                    JSONObject(option.requestJson).getString("rpId")
                }.getOrNull() ?: return@forEach
                val rpId = NativeAppIdentity.normalizeRpId(requestedRpId)
                    ?: return@forEach
                val allowedIds = allowedCredentialIds(option.requestJson)

                PasskeyStore.findForRp(applicationContext, rpId)
                    .filter { allowedIds.isEmpty() || it.credentialId in allowedIds }
                    .forEach { passkey ->
                        val extras = Bundle().apply {
                            putString(EXTRA_CREDENTIAL_ID, passkey.credentialId)
                        }
                        val entry = PublicKeyCredentialEntry.Builder(
                            applicationContext,
                            passkey.username,
                            pendingIntent(PasskeyGetActivity::class.java, extras),
                            option
                        )
                            .setDisplayName(passkey.displayName)
                            .setLastUsedTime(Instant.ofEpochMilli(passkey.lastUsedAt))
                            .build()
                        response.addCredentialEntry(entry)
                        matchCount++
                    }
            }

        if (matchCount == 0) {
            callback.onError(NoCredentialException("没有匹配的 Passkey"))
        } else {
            callback.onResult(response.build())
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return
        callback.onResult(null)
    }

    private fun passwordPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, AutofillAuthActivity::class.java)
            .putExtra(
                AutofillAuthActivity.EXTRA_MODE,
                AutofillAuthActivity.MODE_CREDENTIAL_MANAGER_PASSWORD
            )
        return PendingIntent.getActivity(
            applicationContext,
            requestCode.incrementAndGet(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun pendingIntent(
        activityClass: Class<*>,
        extras: Bundle? = null
    ): PendingIntent {
        val intent = Intent(applicationContext, activityClass)
        extras?.let { intent.putExtra(EXTRA_PASSKEY_DATA, it) }
        return PendingIntent.getActivity(
            applicationContext,
            requestCode.incrementAndGet(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun hasMatchingPasswordCredential(option: BeginGetPasswordOption): Boolean {
        val allowedUserIds = option.allowedUserIds
        return SecurityUtil.loadAccounts(applicationContext).any { account ->
            account.username.isNotBlank() &&
                account.password.isNotBlank() &&
                (allowedUserIds.isEmpty() || account.username in allowedUserIds)
        }
    }

    private fun allowedCredentialIds(requestJson: String): Set<String> = runCatching {
        val array = JSONObject(requestJson).optJSONArray("allowCredentials")
            ?: return@runCatching emptySet()
        buildSet {
            for (index in 0 until array.length()) {
                val id = array.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id.trimEnd('='))
            }
        }
    }.getOrDefault(emptySet())

    companion object {
        const val EXTRA_PASSKEY_DATA = "cerberus_passkey_data"
        const val EXTRA_CREDENTIAL_ID = "credential_id"
    }
}
