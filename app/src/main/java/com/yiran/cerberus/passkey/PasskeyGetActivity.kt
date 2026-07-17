package com.yiran.cerberus.passkey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
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
import java.security.Signature
import kotlinx.coroutines.launch
import uniffi.rust_core.PasskeyAssertionPreparation
import uniffi.rust_core.finishPasskeyResponse
import uniffi.rust_core.preparePasskeyAssertion

class PasskeyGetActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val extras = intent.getBundleExtra(
            CerberusCredentialProviderService.EXTRA_PASSKEY_DATA
        ) ?: return fail("通行密钥选择信息缺失")
        val credentialId = extras.getString(
            CerberusCredentialProviderService.EXTRA_CREDENTIAL_ID
        ) ?: return fail("通行密钥标识缺失")
        val passkey = PasskeyStore.findByCredentialId(this, credentialId)
            ?: return fail("通行密钥已不存在或需要重新创建")
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            ?: return fail("无法读取通行密钥登录请求")
        val request = providerRequest.credentialOptions
            .filterIsInstance<GetPublicKeyCredentialOption>()
            .firstOrNull() ?: return fail("不支持的凭据类型")

        lifecycleScope.launch {
            if (!NativeAppIdentity.verifyRpAssociation(passkey.rpId, providerRequest.callingAppInfo)) {
                fail("无法验证调用应用与目标服务的关联关系")
                return@launch
            }
            prepareAndAuthenticate(providerRequest, request, passkey)
        }
    }

    private fun prepareAndAuthenticate(
        providerRequest: androidx.credentials.provider.ProviderGetCredentialRequest,
        request: GetPublicKeyCredentialOption,
        passkey: StoredPasskey
    ) {
        try {
            val preparation = preparePasskeyAssertion(
                requestJson = request.requestJson,
                origin = NativeAppIdentity.origin(providerRequest.callingAppInfo),
                packageName = providerRequest.callingAppInfo.packageName,
                expectedRpId = passkey.rpId,
                credentialId = passkey.credentialId,
                userId = passkey.userId
            )
            val signature = PasskeyKeyStore.createSigningSignature(passkey.keyAlias)
            authenticate(signature) { authenticatedSignature ->
                signAndReturn(
                    providerRequest,
                    passkey,
                    preparation,
                    authenticatedSignature
                )
            }
        } catch (exception: Exception) {
            fail("准备通行密钥签名失败: ${exception.message ?: "未知错误"}")
        }
    }

    private fun signAndReturn(
        providerRequest: androidx.credentials.provider.ProviderGetCredentialRequest,
        passkey: StoredPasskey,
        preparation: PasskeyAssertionPreparation,
        signature: Signature
    ) {
        try {
            val signedData = Base64.decode(preparation.dataToSign, Base64.DEFAULT)
            signature.update(signedData)
            val signatureDer = Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
            val responseJson = finishPasskeyResponse(
                credentialId = passkey.credentialId,
                userId = passkey.userId,
                clientDataJson = preparation.clientDataJson,
                authenticatorData = preparation.authenticatorData,
                signatureDer = signatureDer
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
            fail("通行密钥签名失败: ${exception.message ?: "未知错误"}")
        }
    }

    private fun authenticate(
        signature: Signature,
        onSuccess: (Signature) -> Unit
    ) {
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
                    val authenticatedSignature = result.cryptoObject?.signature
                    if (authenticatedSignature == null) {
                        fail("硬件密钥认证失败")
                        return
                    }
                    onSuccess(authenticatedSignature)
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("使用通行密钥登录")
            .setSubtitle("验证身份后使用硬件密钥登录")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
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
