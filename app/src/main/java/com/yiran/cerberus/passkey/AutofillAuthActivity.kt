package com.yiran.cerberus.passkey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.yiran.cerberus.ui.theme.CerberusTheme
import com.yiran.cerberus.util.SecurityUtil
import uniffi.rust_core.Account

/**
 * Authentication and account selection UI for an Autofill dataset.
 *
 * No account metadata is shown until the user verifies the Cerberus master password or completes
 * strong biometric authentication when biometric unlock is enabled in Cerberus settings.
 */
class AutofillAuthActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))

        val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL)
            ?.trim()
            ?.take(100)
            ?.takeIf(String::isNotEmpty)
            ?: "当前登录页面"

        setContent {
            CerberusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AutofillAuthScreen(targetLabel)
                }
            }
        }
    }

    @Composable
    private fun AutofillAuthScreen(targetLabel: String) {
        var authenticated by remember { mutableStateOf(false) }
        var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
        var masterPassword by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf("") }
        val biometricAvailable = remember {
            SecurityUtil.isBiometricEnabled(this) &&
                BiometricManager.from(this).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                ) == BiometricManager.BIOMETRIC_SUCCESS
        }

        val unlock = {
            masterPassword = ""
            errorText = ""
            accounts = SecurityUtil.loadAccounts(this)
                .filter { it.username.isNotBlank() && it.password.isNotBlank() }
            authenticated = true
        }

        LaunchedEffect(biometricAvailable) {
            if (biometricAvailable) {
                showBiometricPrompt(onSuccess = unlock)
            }
        }

        BackHandler { cancelAndFinish() }

        if (!authenticated) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "验证后使用 Cerberus 填充",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "填充目标：$targetLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(
                    value = masterPassword,
                    onValueChange = {
                        masterPassword = it
                        errorText = ""
                    },
                    label = { Text("主密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = errorText.isNotEmpty(),
                    supportingText = if (errorText.isNotEmpty()) {
                        { Text(errorText) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (SecurityUtil.verifyMasterPassword(this@AutofillAuthActivity, masterPassword)) {
                            unlock()
                        } else {
                            errorText = "主密码不正确"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("验证并选择凭据")
                }
                if (biometricAvailable) {
                    TextButton(onClick = { showBiometricPrompt(onSuccess = unlock) }) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Text(" 使用生物识别")
                    }
                }
                TextButton(onClick = ::cancelAndFinish) {
                    Text("取消")
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "选择要填充的凭据",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "目标：$targetLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (accounts.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("没有可用于自动填充的账号密码")
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = ::cancelAndFinish) { Text("返回") }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(accounts, key = { it.id }) { account ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { finishWithAccount(account) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = account.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = account.username,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onSuccess()
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("验证 Cerberus")
            .setSubtitle("验证后选择要填充的凭据")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("使用主密码")
            .build()
        prompt.authenticate(promptInfo)
    }

    @Suppress("DEPRECATION")
    private fun finishWithAccount(account: Account) {
        val usernameId = intent.getParcelableExtra(EXTRA_USERNAME_ID, AutofillId::class.java)
        val passwordId = intent.getParcelableExtra(EXTRA_PASSWORD_ID, AutofillId::class.java)
        val datasetBuilder = Dataset.Builder()

        usernameId?.let {
            datasetBuilder.setValue(it, AutofillValue.forText(account.username))
        }
        passwordId?.let {
            datasetBuilder.setValue(it, AutofillValue.forText(account.password))
        }
        if (usernameId == null && passwordId == null) {
            cancelAndFinish()
            return
        }

        val result = Intent()
            .putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, datasetBuilder.build())
            .putExtras(Bundle.EMPTY)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(Activity.RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
        finish()
    }

    companion object {
        const val EXTRA_USERNAME_ID = "cerberus.autofill.USERNAME_ID"
        const val EXTRA_PASSWORD_ID = "cerberus.autofill.PASSWORD_ID"
        const val EXTRA_TARGET_LABEL = "cerberus.autofill.TARGET_LABEL"
    }
}
