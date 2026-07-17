package com.yiran.cerberus.passkey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
                ?: return fail("无法读取 Passkey 创建请求")
        val request = providerRequest.callingRequest as? CreatePublicKeyCredentialRequest
            ?: return fail("不支持的凭据类型")
        val callingAppInfo = providerRequest.callingAppInfo
            ?: return fail("调用应用身份缺失")
        val rpId = runCatching {
            JSONObject(request.requestJson).getJSONObject("rp").getString("id")
        }.getOrNull() ?: return fail("Passkey 请求缺少 RP ID")

        lifecycleScope.launch {
            if (!NativeAppIdentity.verifyRpAssociation(rpId, callingAppInfo)) {
                fail("调用应用未通过 $rpId 的 Digital Asset Links 验证")
                return@launch
            }
            authenticate(rpId) {
                createAndReturn(request, callingAppInfo)
            }
        }
    }

    private fun createAndReturn(
        request: CreatePublicKeyCredentialRequest,
        callingAppInfo: androidx.credentials.provider.CallingAppInfo
    ) {
        try {
            val created = createPasskey(
                requestJson = request.requestJson,
                origin = NativeAppIdentity.origin(callingAppInfo),
                packageName = callingAppInfo.packageName
            )
            val now = System.currentTimeMillis()
            PasskeyStore.save(
                this,
                StoredPasskey(
                    credentialId = created.credentialId,
                    rpId = created.rpId,
                    userId = created.userId,
                    username = created.username,
                    displayName = created.displayName,
                    privateKey = created.privateKey,
                    createdAt = now,
                    lastUsedAt = now
                )
            )

            val result = Intent()
            PendingIntentHandler.setCreateCredentialResponse(
                result,
                CreatePublicKeyCredentialResponse(created.responseJson)
            )
            setResult(Activity.RESULT_OK, result)
            finish()
        } catch (exception: Exception) {
            fail("创建 Passkey 失败: ${exception.message ?: "未知错误"}")
        }
    }

    private fun authenticate(rpId: String, onSuccess: () -> Unit) {
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
                .setTitle("保存 Passkey")
                .setSubtitle("验证身份后为 $rpId 创建 Passkey")
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
}
