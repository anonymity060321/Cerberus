package com.yiran.cerberus.passkey

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class StoredPasskey(
    val credentialId: String,
    val rpId: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val keyAlias: String,
    val createdAt: Long,
    val lastUsedAt: Long
)

/**
 * Stores only Passkey metadata. Private keys remain non-exportable inside
 * Android Keystore and are deliberately excluded from .cerb backups.
 *
 * Records created by versions before 1.3.0 contain an exportable privateKey.
 * They are reported as legacy records but are never loaded for authentication.
 * Saving the first hardware-backed Passkey rewrites this store without them.
 */
object PasskeyStore {
    private const val PREF_NAME = "passkey_secure_prefs"
    private const val KEY_PASSKEYS = "passkeys"

    @Volatile
    private var preferences: SharedPreferences? = null

    private fun preferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return preferences ?: synchronized(this) {
            preferences ?: EncryptedSharedPreferences.create(
                appContext,
                PREF_NAME,
                MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { preferences = it }
        }
    }

    @Synchronized
    fun save(context: Context, passkey: StoredPasskey) {
        require(PasskeyKeyStore.contains(passkey.keyAlias)) {
            "Passkey hardware key is missing"
        }
        val allPasskeys = loadAll(context)
        val existing = allPasskeys.firstOrNull { it.credentialId == passkey.credentialId }
        val current = allPasskeys
            .filterNot { it.credentialId == passkey.credentialId }
            .toMutableList()
        current.add(passkey)
        writeAll(context, current)
        if (existing != null && existing.keyAlias != passkey.keyAlias) {
            PasskeyKeyStore.delete(existing.keyAlias)
        }
    }

    @Synchronized
    fun remove(context: Context, credentialId: String) {
        val current = loadAll(context)
        val removed = current.firstOrNull { it.credentialId == credentialId }
        writeAll(context, current.filterNot { it.credentialId == credentialId })
        removed?.let { PasskeyKeyStore.delete(it.keyAlias) }
    }

    @Synchronized
    fun findByCredentialId(context: Context, credentialId: String): StoredPasskey? =
        loadAll(context).firstOrNull { it.credentialId == credentialId }

    @Synchronized
    fun findForRp(context: Context, rpId: String): List<StoredPasskey> =
        loadAll(context).filter { it.rpId == rpId }

    @Synchronized
    fun touch(context: Context, credentialId: String) {
        val updated = loadAll(context).map { passkey ->
            if (passkey.credentialId == credentialId) {
                passkey.copy(lastUsedAt = System.currentTimeMillis())
            } else {
                passkey
            }
        }
        writeAll(context, updated)
    }

    @Synchronized
    fun count(context: Context): Int = loadAll(context).size

    @Synchronized
    fun legacyCount(context: Context): Int {
        val array = readArray(context) ?: return 0
        var count = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.has("privateKey") && item.optString("keyAlias").isBlank()) {
                count += 1
            }
        }
        return count
    }

    private fun loadAll(context: Context): List<StoredPasskey> {
        val array = readArray(context) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val keyAlias = item.optString("keyAlias")
                if (keyAlias.isBlank() || !PasskeyKeyStore.contains(keyAlias)) {
                    continue
                }
                runCatching {
                    StoredPasskey(
                        credentialId = item.getString("credentialId"),
                        rpId = item.getString("rpId"),
                        userId = item.getString("userId"),
                        username = item.getString("username"),
                        displayName = item.getString("displayName"),
                        keyAlias = keyAlias,
                        createdAt = item.getLong("createdAt"),
                        lastUsedAt = item.getLong("lastUsedAt")
                    )
                }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun readArray(context: Context): JSONArray? {
        val encoded = preferences(context).getString(KEY_PASSKEYS, null) ?: return null
        return runCatching { JSONArray(encoded) }.getOrNull()
    }

    private fun writeAll(context: Context, passkeys: List<StoredPasskey>) {
        val array = JSONArray()
        passkeys.forEach { passkey ->
            array.put(
                JSONObject()
                    .put("credentialId", passkey.credentialId)
                    .put("rpId", passkey.rpId)
                    .put("userId", passkey.userId)
                    .put("username", passkey.username)
                    .put("displayName", passkey.displayName)
                    .put("keyAlias", passkey.keyAlias)
                    .put("createdAt", passkey.createdAt)
                    .put("lastUsedAt", passkey.lastUsedAt)
            )
        }
        check(
            preferences(context)
                .edit()
                .putString(KEY_PASSKEYS, array.toString())
                .commit()
        ) {
            "Unable to persist Passkey metadata"
        }
    }
}
