package com.yiran.cerberus.passkey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uniffi.rust_core.getPasskeyResponse

class PasskeyGetActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val extras = intent.getBundleExtra(
            CerberusCredentialProviderService.EXTRA_PASSKEY_DATA
        ) ?: return fail("Passkey 选择信息缺失")
        val credentialId = extras.getString(
            CerberusCredentialProviderService.EXTRA_CREDENTIAL_ID
        ) ?: return fail("Passkey ID 缺失")
        val passkey = PasskeyStore.findByCredentialId(this, credentialId)
            ?: return fail("Passkey 已不存在")
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            ?: return fail("无法读取 Passkey 登录请求")
        val request = providerRequest.credentialOptions
            .filterIsInstance<GetPublicKeyCredentialOption>()
            .firstOrNull() ?: return fail("不支持的凭据类型")

        lifecycleScope.launch {
            if (!NativeAppIdentity.verifyRpAssociation(passkey.rpId, providerRequest.callingAppInfo)) {
                fail("调用应用未通过 ${passkey.rpId} 的 Digital Asset Links 验证")
                return@launch
            }
            authenticate(passkey.rpId) {
                signAndReturn(providerRequest, request, passkey)
            }
        }
    }

    private fun signAndReturn(
        providerRequest: androidx.credentials.provider.ProviderGetCredentialRequest,
        request: GetPublicKeyCredentialOption,
        passkey: StoredPasskey
    ) {
        try {
            val responseJson = getPasskeyResponse(
                requestJson = request.requestJson,
                origin = NativeAppIdentity.origin(providerRequest.callingAppInfo),
                packageName = providerRequest.callingAppInfo.packageName,
                expectedRpId = passkey.rpId,
                credentialId = passkey.credentialId,
                userId = passkey.userId,
                privateKey = passkey.privateKey
            )
            PasskeyStore.touch(this, passkey.credentialId)

            val result = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                result,
                GetCredentialResponse(PublicKeyCredential(responseJson)),
                providerRequest
            )
            setResult(Activity.RESULT_OK, result)
            finish()
        } catch (exception: Exception) {
            fail("Passkey 签名失败: ${exception.message ?: "未知错误"}")
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
                .setTitle("使用 Passkey 登录")
                .setSubtitle("验证身份后登录 $rpId")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun fail(message: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(
            result,
            GetCredentialUnknownException(message)
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
