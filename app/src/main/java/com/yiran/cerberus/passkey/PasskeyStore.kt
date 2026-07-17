package com.yiran.cerberus.passkey

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
    val privateKey: String,
    val createdAt: Long,
    val lastUsedAt: Long
)

/**
 * Device-local passkey storage. The private scalar returned by the Rust FIDO
 * implementation is encrypted by an Android Keystore-protected master
 * key before it reaches disk. Passkeys are deliberately excluded from .cerb
 * account backups.
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
        val current = loadAll(context)
            .filterNot { it.credentialId == passkey.credentialId }
            .toMutableList()
        current.add(passkey)
        writeAll(context, current)
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

    private fun loadAll(context: Context): List<StoredPasskey> {
        val encoded = preferences(context).getString(KEY_PASSKEYS, null) ?: return emptyList()
        return try {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        StoredPasskey(
                            credentialId = item.getString("credentialId"),
                            rpId = item.getString("rpId"),
                            userId = item.getString("userId"),
                            username = item.getString("username"),
                            displayName = item.getString("displayName"),
                            privateKey = item.getString("privateKey"),
                            createdAt = item.getLong("createdAt"),
                            lastUsedAt = item.getLong("lastUsedAt")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
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
                    .put("privateKey", passkey.privateKey)
                    .put("createdAt", passkey.createdAt)
                    .put("lastUsedAt", passkey.lastUsedAt)
            )
        }
        preferences(context).edit(commit = true) {
            putString(KEY_PASSKEYS, array.toString())
        }
    }
}
