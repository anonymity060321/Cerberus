package com.yiran.cerberus.ui.home

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yiran.cerberus.passkey.PasskeyStore
import com.yiran.cerberus.util.SecurityUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, homeViewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val versionName = remember(context) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        ).versionName ?: "未知"
    }

    val isBiometricEnabled = remember {
        mutableStateOf(SecurityUtil.isBiometricEnabled(context))
    }
    val canUseBiometric = remember { SecurityUtil.canUseBiometric(context) }
    val isPasswordAutofillEnabled = remember {
        mutableStateOf(SecurityUtil.isPasswordAutofillEnabled(context))
    }
    
    val autoLockTime = remember {
        mutableLongStateOf(SecurityUtil.getAutoLockTime(context))
    }
    val showTimeMenu = remember { mutableStateOf(false) }
    val passkeyCount = remember { mutableIntStateOf(PasskeyStore.count(context)) }
    val legacyPasskeyCount = remember { mutableIntStateOf(PasskeyStore.legacyCount(context)) }
    val isPasskeyProviderEnabled = remember {
        mutableStateOf(isCerberusCredentialProviderEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                passkeyCount.intValue = PasskeyStore.count(context)
                legacyPasskeyCount.intValue = PasskeyStore.legacyCount(context)
                isPasskeyProviderEnabled.value =
                    isCerberusCredentialProviderEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showExportDialog = remember { mutableStateOf(false) }
    val showImportDialog = remember { mutableStateOf(false) }
    val backupPassword = remember { mutableStateOf("") }
    val pendingImportUri = remember { mutableStateOf<android.net.Uri?>(null) }
    
    val isUpdateCheckAllowed = remember { mutableStateOf(SecurityUtil.isUpdateCheckAllowed(context)) }
    val showConsentDialog = remember { mutableStateOf(false) }
    val isCheckingUpdate = remember { mutableStateOf(false) }

    val storageError = homeViewModel.storageErrorMessage
    LaunchedEffect(storageError) {
        storageError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            homeViewModel.consumeStorageError()
        }
    }

    val autofillAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val enabledBySystem = runCatching {
            context.getSystemService(AutofillManager::class.java)
                ?.hasEnabledAutofillServices() == true
        }.getOrDefault(false)
        if (!enabledBySystem) {
            SecurityUtil.setPasswordAutofillEnabled(context, false)
            isPasswordAutofillEnabled.value = false
            Toast.makeText(
                context,
                "未在系统中启用 Cerberus，账号密码自动填充保持关闭",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            val password = backupPassword.value
            backupPassword.value = ""
            homeViewModel.exportBackup(
                context = context,
                uri = uri,
                password = password,
                onSuccess = {
                    Toast.makeText(context, "加密备份导出成功", Toast.LENGTH_SHORT).show()
                },
                onError = { message ->
                    Toast.makeText(context, "导出失败: $message", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            backupPassword.value = ""
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportUri.value = it
            showImportDialog.value = true
        }
    }

    if (showExportDialog.value) {
        StyledDialog(
            onDismissRequest = {
                showExportDialog.value = false
                backupPassword.value = ""
            },
            title = "设置备份密码",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入用于加密备份文件的密码，恢复时需要此密码。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StyledTextField(
                        value = backupPassword.value,
                        onValueChange = { backupPassword.value = it },
                        label = "密码"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (backupPassword.value.isNotBlank()) {
                            val fileName = "Cerberus_Backup_${System.currentTimeMillis()}.cerb"
                            createDocumentLauncher.launch(fileName)
                            showExportDialog.value = false
                        }
                    }
                ) { Text("确定", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog.value = false
                    backupPassword.value = ""
                }) { Text("取消") }
            }
        )
    }

    if (showImportDialog.value) {
        StyledDialog(
            onDismissRequest = {
                showImportDialog.value = false
                backupPassword.value = ""
            },
            title = "输入备份密码",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("该备份文件已加密，请输入正确的密码进行解密并恢复数据。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StyledTextField(
                        value = backupPassword.value,
                        onValueChange = { backupPassword.value = it },
                        label = "密码"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri.value?.let { uri ->
                            homeViewModel.importBackup(
                                context = context,
                                uri = uri,
                                password = backupPassword.value,
                                onSuccess = {
                                    showImportDialog.value = false
                                    backupPassword.value = ""
                                    Toast.makeText(context, "数据恢复成功", Toast.LENGTH_SHORT).show()
                                },
                                onError = { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                ) { Text("恢复", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog.value = false
                    backupPassword.value = ""
                }) { Text("取消") }
            }
        )
    }

    if (showConsentDialog.value) {
        StyledDialog(
            onDismissRequest = { showConsentDialog.value = false },
            title = "联网偏好说明",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Cerberus 默认禁用所有联网功能。为了您可以及时获取安全修复与新特性，您可以选择开启“检查更新”服务：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• 开启后，仅在您手动点击时访问 GitHub API 获取版本号\n• 我们郑重承诺：应用绝不会收集或上传您的任何令牌数据\n• 未经您的明确允许，应用绝不会在后台静默使用联网权限\n• 支持 Steam 协议的登录验证码完全在本机生成，不需要联网\n• 账号密码自动填充仅在您主动选择并完成身份验证后执行，不需要联网\n• 通行密钥仅在您主动创建或使用时进行必要的在线安全验证，私钥始终保留在本机安全硬件中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SecurityUtil.setUpdateCheckAllowed(context, true)
                    isUpdateCheckAllowed.value = true
                    showConsentDialog.value = false
                }) { Text("同意开启", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    SecurityUtil.setUpdateCheckAllowed(context, false)
                    isUpdateCheckAllowed.value = false
                    showConsentDialog.value = false
                }) { Text("保持离线") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "安全",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AboutItem(
                            icon = Icons.Default.Timer,
                            label = "自动锁定超时",
                            value = when(autoLockTime.longValue) {
                                0L -> "立即"
                                15000L -> "15 秒"
                                30000L -> "30 秒"
                                60000L -> "60 秒"
                                else -> "${autoLockTime.longValue / 1000} 秒"
                            },
                            onClick = { showTimeMenu.value = true }
                        )
                        
                        Box(modifier = Modifier.align(Alignment.TopEnd)) {
                            DropdownMenu(
                                expanded = showTimeMenu.value,
                                onDismissRequest = { showTimeMenu.value = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                listOf(0L, 15000L, 30000L, 60000L).forEach { time ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (time == 0L) "立即" else "${time / 1000} 秒",
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        onClick = {
                                            SecurityUtil.setAutoLockTime(context, time)
                                            autoLockTime.longValue = time
                                            showTimeMenu.value = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "指纹/生物识别解锁", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (canUseBiometric) "使用设备生物识别快速解锁应用" else "您的设备不支持生物识别",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = isBiometricEnabled.value,
                            onCheckedChange = { enabled ->
                                SecurityUtil.setBiometricEnabled(context, enabled)
                                isBiometricEnabled.value = enabled
                            },
                            enabled = canUseBiometric
                        )
                    }

                    AboutItem(
                        icon = Icons.Default.Key,
                        label = "通行密钥服务",
                        value = if (legacyPasskeyCount.intValue > 0) {
                            "${legacyPasskeyCount.intValue} 个旧版"
                        } else {
                            val status = if (isPasskeyProviderEnabled.value) {
                                "已启用"
                            } else {
                                "未启用"
                            }
                            "${passkeyCount.intValue} 个 · $status"
                        },
                        onClick = {
                            runCatching {
                                androidx.credentials.CredentialManager
                                    .create(context)
                                    .createSettingsPendingIntent()
                                    .send()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "无法打开通行密钥服务设置",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "账号密码自动填充",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isPasswordAutofillEnabled.value) {
                                    "验证身份后才会提供由您选择的账号密码"
                                } else {
                                    "关闭时不会向系统提供任何账号密码"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = isPasswordAutofillEnabled.value,
                            onCheckedChange = { enabled ->
                                SecurityUtil.setPasswordAutofillEnabled(context, enabled)
                                isPasswordAutofillEnabled.value = enabled
                                if (enabled) {
                                    val enabledBySystem = runCatching {
                                        context.getSystemService(AutofillManager::class.java)
                                            ?.hasEnabledAutofillServices() == true
                                    }.getOrDefault(false)
                                    if (!enabledBySystem) {
                                        runCatching {
                                            autofillAuthorizationLauncher.launch(
                                                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                                                    data = "package:${context.packageName}".toUri()
                                                }
                                            )
                                        }.onFailure {
                                            SecurityUtil.setPasswordAutofillEnabled(context, false)
                                            isPasswordAutofillEnabled.value = false
                                            Toast.makeText(
                                                context,
                                                "无法请求系统自动填充授权",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "数据管理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    AboutItem(
                        icon = Icons.Default.FileDownload,
                        label = "导出加密备份",
                        value = "导出",
                        onClick = { showExportDialog.value = true }
                    )

                    AboutItem(
                        icon = Icons.Default.FileUpload,
                        label = "导入加密备份",
                        value = "恢复",
                        onClick = { openDocumentLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "关于 Cerberus",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Cerberus 是一款注重隐私与本地安全的凭据管理和身份验证工具，支持账号密码管理、标准动态验证码、Steam 协议、经身份验证的账号密码自动填充，以及系统通行密钥服务。\n\n账号密码和动态验证码数据均加密保存在本机；自动填充默认关闭，仅在用户主动开启、完成身份验证并选择账号后执行；通行密钥私钥由设备安全硬件保护，不会写入加密备份。除用户主动检查更新或使用通行密钥所需的在线安全验证外，应用不会主动联网。",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    AboutItem(
                        icon = if (isUpdateCheckAllowed.value) Icons.Default.Update else Icons.Default.CloudOff,
                        label = "检查更新",
                        value = if (isCheckingUpdate.value) "检查中..." else if (isUpdateCheckAllowed.value) "获取最新版" else "已禁用",
                        onClick = if (isUpdateCheckAllowed.value && !isCheckingUpdate.value) {
                            {
                                isCheckingUpdate.value = true
                                homeViewModel.checkUpdate(
                                    currentVersion = versionName,
                                    onResult = { hasUpdate, latest, downloadUrl ->
                                        isCheckingUpdate.value = false
                                        if (hasUpdate) {
                                            val targetUrl = downloadUrl ?: "https://github.com/anonymity060321/Cerberus/releases/latest"
                                            val intent = Intent(Intent.ACTION_VIEW, targetUrl.toUri())
                                            context.startActivity(intent)
                                            val msg = if (downloadUrl != null) "发现新版本: v$latest，正在下载..." else "发现新版本: v$latest，正在跳转 GitHub..."
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onError = { error ->
                                        isCheckingUpdate.value = false
                                        Toast.makeText(context, "检查失败: $error", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        } else null
                    )
                    
                    if (!isUpdateCheckAllowed.value) {
                        TextButton(
                            onClick = { showConsentDialog.value = true },
                            modifier = Modifier.padding(start = 48.dp)
                        ) {
                            Text(
                                "为何禁用？查看详情并开启",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { 
                                SecurityUtil.setUpdateCheckAllowed(context, false)
                                isUpdateCheckAllowed.value = false
                                Toast.makeText(context, "已取消联网授权，恢复离线状态", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.padding(start = 48.dp)
                        ) {
                            Text(
                                "取消联网授权并恢复离线",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    AboutItem(
                        icon = Icons.Default.Person, 
                        label = "作者", 
                        value = "Yiran",
                        highlightValue = true
                    )
                    AboutItem(Icons.Default.Email, "反馈邮箱", "yi_ran@aliyun.com") {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = "mailto:yi_ran@aliyun.com".toUri()
                            putExtra(Intent.EXTRA_SUBJECT, "Cerberus 意见反馈")
                        }
                        context.startActivity(Intent.createChooser(intent, "发送邮件"))
                    }
                    AboutItem(Icons.Default.Link, "GitHub", "项目仓库") {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/Ranpers/Cerberus".toUri())
                        context.startActivity(intent)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Version $versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Made with ❤️ for Privacy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun isCerberusCredentialProviderEnabled(
    context: android.content.Context
): Boolean = runCatching {
    val manager =
        context.getSystemService(android.credentials.CredentialManager::class.java)
    val component = android.content.ComponentName(
        context,
        com.yiran.cerberus.passkey.CerberusCredentialProviderService::class.java
    )
    manager?.isEnabledCredentialProviderService(component) == true
}.getOrDefault(false)

@Composable
fun AboutItem(
    icon: ImageVector, 
    label: String, 
    value: String, 
    highlightValue: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null || highlightValue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (onClick != null || highlightValue) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
