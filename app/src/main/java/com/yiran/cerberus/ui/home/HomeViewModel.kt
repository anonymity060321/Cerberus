package com.yiran.cerberus.ui.home

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.yiran.cerberus.util.TotpUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yiran.cerberus.util.SecurityUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.rust_core.Account
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeViewModel : ViewModel() {
    private val _accounts = mutableStateListOf<Account>()
    val accounts: List<Account> = _accounts

    private var totpJob: Job? = null
    private val saveMutex = Mutex()
    private var saveVersion = 0L
    private var persistedSaveVersion = 0L

    // UI state hoisted to ViewModel
    var isAddDialogVisible by mutableStateOf(false)
    var isDeleteDialogVisible by mutableStateOf(false)
    var isEditPasswordDialogVisible by mutableStateOf(false)
    var selectedAccount by mutableStateOf<Account?>(null)
    var storageErrorMessage by mutableStateOf<String?>(null)
        private set

    // TOTP state (precomputed codes)
    var totpProgress by mutableFloatStateOf(1f)
    var totpStep by mutableLongStateOf(System.currentTimeMillis() / 30000)
    private val _otpCodes = mutableStateMapOf<Int, String>()
    val otpCodes: Map<Int, String> get() = _otpCodes

    fun loadAccounts(context: Context) {
        viewModelScope.launch {
            try {
                val loadedAccounts = withContext(Dispatchers.IO) {
                    SecurityUtil.loadAccountsOrThrow(context)
                }
                val normalizedAccounts = ensureUniqueAccountIds(loadedAccounts)
                _accounts.clear()
                _accounts.addAll(normalizedAccounts)
                if (normalizedAccounts != loadedAccounts) {
                    SecurityUtil.clearAutofillBindings(context)
                    scheduleSave(context)
                }
                startTotpTicker()
            } catch (_: Exception) {
                storageErrorMessage = "无法读取已保存的凭据，原数据尚未被覆盖"
            }
        }
    }

    private fun startTotpTicker() {
        totpJob?.cancel()
        totpJob = viewModelScope.launch {
            while (true) {
                totpProgress = TotpUtil.getProgress()
                totpStep = System.currentTimeMillis() / 30000

                val otpAccounts = _accounts
                    .filter { it.hasOtp && it.secretKey.isNotEmpty() }
                    .toList()
                val generatedCodes = withContext(Dispatchers.Default) {
                    otpAccounts.associate { account ->
                        account.id to TotpUtil.generateCode(
                            account.secretKey,
                            account.algorithm,
                            account.otpType
                        )
                    }
                }
                _otpCodes.clear()
                _otpCodes.putAll(generatedCodes)

                delay(1000)
            }
        }
    }

    private fun scheduleSave(context: Context) {
        val snapshot = _accounts.toList()
        val version = ++saveVersion
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    saveMutex.withLock {
                        if (version > persistedSaveVersion) {
                            SecurityUtil.saveAccounts(context, snapshot)
                            persistedSaveVersion = version
                        }
                    }
                }
            } catch (_: Exception) {
                storageErrorMessage = "凭据保存失败，请勿卸载应用并尽快重试"
            }
        }
    }

    fun addAccount(context: Context, account: Account) {
        val uniqueAccount = if (_accounts.any { it.id == account.id }) {
            account.copy(id = nextAvailableId())
        } else {
            account
        }
        _accounts.add(uniqueAccount)
        scheduleSave(context)
        // ensure otpCodes updated
        if (uniqueAccount.hasOtp && uniqueAccount.secretKey.isNotEmpty()) {
            _otpCodes[uniqueAccount.id] = TotpUtil.generateCode(
                uniqueAccount.secretKey,
                uniqueAccount.algorithm,
                uniqueAccount.otpType
            )
        }
    }

    fun deleteAccount(context: Context, account: Account) {
        _accounts.remove(account)
        _otpCodes.remove(account.id)
        SecurityUtil.removeAutofillBindingsForAccount(context, account.id)
        scheduleSave(context)
    }

    fun updatePassword(context: Context, accountId: Int, newPassword: String) {
        val index = _accounts.indexOfFirst { it.id == accountId }
        if (index != -1) {
            val oldAccount = _accounts[index]
            _accounts[index] = oldAccount.copy(password = newPassword)
            scheduleSave(context)
        }
    }

    fun selectAccountForEdit(account: Account) {
        selectedAccount = account
        isEditPasswordDialogVisible = true
    }

    fun closeEditPasswordDialog() {
        isEditPasswordDialogVisible = false
        selectedAccount = null
    }

    fun openAddDialog() { isAddDialogVisible = true }
    fun closeAddDialog() { isAddDialogVisible = false }

    fun selectAccountForDelete(account: Account) {
        selectedAccount = account
        isDeleteDialogVisible = true
    }

    fun closeDeleteDialog() {
        isDeleteDialogVisible = false
        selectedAccount = null
    }

    fun moveAccount(context: Context, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in _accounts.indices || toIndex !in _accounts.indices) return
        _accounts.add(toIndex, _accounts.removeAt(fromIndex))
        scheduleSave(context)
    }

    fun exportBackup(
        context: Context,
        uri: Uri,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val snapshot = _accounts.toList()
        viewModelScope.launch {
            try {
                val encrypted = withContext(Dispatchers.Default) {
                    val json = SecurityUtil.accountsToJson(snapshot)
                    SecurityUtil.encryptBackup(json, password)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output).use { writer -> writer.write(encrypted) }
                    } ?: throw IllegalStateException("无法写入备份文件")
                }
                onSuccess()
            } catch (exception: Exception) {
                onError(exception.message ?: "备份导出失败")
            }
        }
    }

    fun importBackup(
        context: Context,
        uri: Uri,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val encryptedContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val reader = InputStreamReader(inputStream)
                        val buffer = CharArray(8 * 1024)
                        buildString {
                            while (true) {
                                val read = reader.read(buffer)
                                if (read < 0) break
                                if (length + read > MAX_BACKUP_CHARACTERS) {
                                    throw IllegalArgumentException("备份文件过大")
                                }
                                append(buffer, 0, read)
                            }
                        }
                    } ?: throw Exception("无法读取文件")
                }

                val importedAccounts = withContext(Dispatchers.Default) {
                    val json = SecurityUtil.decryptBackup(encryptedContent, password)
                    validateImportedAccounts(SecurityUtil.jsonToAccounts(json))
                }

                if (importedAccounts.isNotEmpty()) {
                    _accounts.clear()
                    _accounts.addAll(ensureUniqueAccountIds(importedAccounts))
                    SecurityUtil.clearAutofillBindings(context)
                    val importedSnapshot = _accounts.toList()
                    val importVersion = ++saveVersion
                    withContext(Dispatchers.IO) {
                        saveMutex.withLock {
                            if (importVersion > persistedSaveVersion) {
                                SecurityUtil.saveAccounts(context, importedSnapshot)
                                persistedSaveVersion = importVersion
                            }
                        }
                    }
                    startTotpTicker()
                    onSuccess()
                } else {
                    onError("备份文件内容为空")
                }
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "导入失败: 备份文件无效")
            } catch (e: IllegalStateException) {
                onError(e.message ?: "导入失败: 备份版本不兼容或未知错误")
            } catch (_ : Exception) {
                onError("导入失败: 密码错误或文件损坏")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        totpJob?.cancel()
    }

    fun consumeStorageError() {
        storageErrorMessage = null
    }

    fun checkUpdate(
        currentVersion: String,
        onResult: (hasUpdate: Boolean, latestVersion: String, downloadUrl: String?) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = URL(RELEASE_API_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val latestTag = json.getString("tag_name").removePrefix("v")
                        val hasUpdate = isVersionNewer(currentVersion, latestTag)
                        
                        var downloadUrl: String? = null
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.getString("name")
                                if (name.endsWith(".apk")) {
                                    downloadUrl = asset.getString("browser_download_url")
                                    break
                                }
                            }
                        }
                        
                        Triple(hasUpdate, latestTag, downloadUrl)
                    } else {
                        throw Exception("服务器响应异常: ${connection.responseCode}")
                    }
                }
                onResult(result.first, result.second, result.third)
            } catch (_ : UnknownHostException) {
                onError("网络不可用，请检查联网设置")
            } catch (_ : SocketTimeoutException) {
                onError("连接 GitHub 超时，请稍后再试")
            } catch (e: Exception) {
                onError("检查失败: ${e.message ?: "网络请求异常"}")
            }
        }
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until minOf(currParts.size, latestParts.size)) {
            if (latestParts[i] > currParts[i]) return true
            if (latestParts[i] < currParts[i]) return false
        }
        return latestParts.size > currParts.size
    }

    private fun nextAvailableId(): Int {
        val used = _accounts.mapTo(mutableSetOf()) { it.id }
        var candidate = ((_accounts.maxOfOrNull { it.id.toLong() } ?: 0L) + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        while (candidate in used) {
            candidate = if (candidate == Int.MAX_VALUE) Int.MIN_VALUE else candidate + 1
        }
        return candidate
    }

    private fun ensureUniqueAccountIds(accounts: List<Account>): List<Account> {
        val used = mutableSetOf<Int>()
        var candidate = (accounts.maxOfOrNull { it.id.toLong() } ?: 0L) + 1L
        return accounts.map { account ->
            if (used.add(account.id)) {
                account
            } else {
                var replacement = candidate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                while (!used.add(replacement)) {
                    replacement = if (replacement == Int.MAX_VALUE) Int.MIN_VALUE else replacement + 1
                }
                candidate = replacement.toLong() + 1L
                account.copy(id = replacement)
            }
        }
    }

    private fun validateImportedAccounts(accounts: List<Account>): List<Account> {
        require(accounts.size <= MAX_IMPORTED_ACCOUNTS) { "备份中的凭据数量过多" }
        accounts.forEach { account ->
            require(account.name.length <= 256) { "备份包含过长的凭据名称" }
            require(account.username.length <= 1_024) { "备份包含过长的用户名" }
            require(account.password.length <= 65_536) { "备份包含过长的密码" }
            require(account.secretKey.length <= 4_096) { "备份包含过长的验证密钥" }
        }
        return accounts
    }

    private companion object {
        const val MAX_BACKUP_CHARACTERS = 4 * 1024 * 1024
        const val MAX_IMPORTED_ACCOUNTS = 10_000
        const val RELEASE_API_URL =
            "https://api.github.com/repos/anonymity060321/Cerberus/releases/latest"
    }
}
