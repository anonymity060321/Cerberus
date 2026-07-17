package com.yiran.cerberus.passkey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import uniffi.rust_core.createPasskey

class PasskeyCreateActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val providerRequest =
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
                ?: return fail("无法读取通行密钥创建请求")
        val request = providerRequest.callingRequest as? CreatePublicKeyCredentialRequest
            ?: return fail("不支持的凭据类型")
        val callingAppInfo = providerRequest.callingAppInfo
            ?: return fail("调用应用身份缺失")
        val requestedRpId = runCatching {
            JSONObject(request.requestJson).getJSONObject("rp").getString("id")
        }.getOrNull() ?: return fail("通行密钥请求信息不完整")
        val rpId = NativeAppIdentity.normalizeRpId(requestedRpId)
            ?: return fail("通行密钥请求包含无效的服务域名")

        lifecycleScope.launch {
            if (!NativeAppIdentity.verifyRpAssociation(rpId, callingAppInfo)) {
                fail("无法验证调用应用与目标服务的关联关系")
                return@launch
            }
            authenticate {
                createAndReturn(request, callingAppInfo)
            }
        }
    }

    private fun createAndReturn(
        request: CreatePublicKeyCredentialRequest,
        callingAppInfo: androidx.credentials.provider.CallingAppInfo
    ) {
        var keyMaterial: HardwareKeyMaterial? = null
        var storedCredentialId: String? = null
        try {
            val rpId = NativeAppIdentity.normalizeRpId(
                JSONObject(request.requestJson).getJSONObject("rp").getString("id")
            ) ?: throw IllegalArgumentException("Invalid RP ID")
            val excludedIds = excludedCredentialIds(request.requestJson)
            if (
                PasskeyStore.findForRp(this, rpId)
                    .any { passkey -> passkey.credentialId in excludedIds }
            ) {
                fail("该账号已经保存过通行密钥")
                return
            }
            val generatedKeyMaterial = PasskeyKeyStore.createKeyMaterial()
            keyMaterial = generatedKeyMaterial
            val created = createPasskey(
                requestJson = request.requestJson,
                origin = NativeAppIdentity.origin(callingAppInfo),
                packageName = callingAppInfo.packageName,
                publicKeyX = generatedKeyMaterial.publicKeyX,
                publicKeyY = generatedKeyMaterial.publicKeyY
            )
            val response = CreatePublicKeyCredentialResponse(created.responseJson)
            val now = System.currentTimeMillis()
            PasskeyStore.save(
                this,
                StoredPasskey(
                    credentialId = created.credentialId,
                    rpId = created.rpId,
                    userId = created.userId,
                    username = created.username,
                    displayName = created.displayName,
                    keyAlias = generatedKeyMaterial.alias,
                    createdAt = now,
                    lastUsedAt = now
                )
            )
            storedCredentialId = created.credentialId

            val result = Intent()
            PendingIntentHandler.setCreateCredentialResponse(result, response)
            setResult(Activity.RESULT_OK, result)
            keyMaterial = null
            finish()
        } catch (exception: Exception) {
            storedCredentialId?.let { PasskeyStore.remove(this, it) }
            keyMaterial?.let { PasskeyKeyStore.delete(it.alias) }
            Log.e(TAG, "Passkey creation failed", exception)
            fail("创建通行密钥失败，请重试")
        }
    }

    private fun excludedCredentialIds(requestJson: String): Set<String> = runCatching {
        val array = JSONObject(requestJson).optJSONArray("excludeCredentials")
            ?: return@runCatching emptySet()
        buildSet {
            for (index in 0 until array.length()) {
                val id = array.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id.trimEnd('='))
            }
        }
    }.getOrDefault(emptySet())

    private fun authenticate(onSuccess: () -> Unit) {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            fail("请先为手机设置锁屏 PIN、密码或生物识别")
            return
        }

        val prompt = BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    fail(errString.toString())
                }

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onSuccess()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("保存通行密钥")
                .setSubtitle("验证身份后创建硬件保护的通行密钥")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun fail(message: String) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(
            result,
            CreateCredentialUnknownException(message)
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private companion object {
        const val TAG = "PasskeyCreate"
    }
}
