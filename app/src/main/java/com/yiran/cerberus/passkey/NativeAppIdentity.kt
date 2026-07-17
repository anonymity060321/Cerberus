package com.yiran.cerberus.passkey

import android.content.pm.Signature
import androidx.credentials.provider.CallingAppInfo
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.IDN
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

object NativeAppIdentity {
    private const val MAX_ASSET_LINKS_BYTES = 256 * 1024

    fun origin(callingAppInfo: CallingAppInfo): String {
        val certificate = callingAppInfo.signingInfo.apkContentsSigners.firstOrNull()
            ?: throw IllegalArgumentException("调用应用没有签名证书")
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
        val encoded = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "android:apk-key-hash:$encoded"
    }

    suspend fun verifyRpAssociation(rpId: String, callingAppInfo: CallingAppInfo): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedRpId = normalizeRpId(rpId) ?: return@withContext false
            if (!hasPublicNetworkAddress(normalizedRpId)) return@withContext false

            val connection = URL("https://$normalizedRpId/.well-known/assetlinks.json")
                .openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                    return@withContext false
                }

                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(MAX_ASSET_LINKS_BYTES + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val read = input.read(buffer, total, buffer.size - total)
                        if (read < 0) break
                        total += read
                    }
                    if (total > MAX_ASSET_LINKS_BYTES) return@withContext false
                    buffer.copyOf(total)
                }
                matchesAssetLinks(
                    JSONArray(bytes.toString(Charsets.UTF_8)),
                    callingAppInfo
                )
            } catch (_: Exception) {
                false
            } finally {
                connection.disconnect()
            }
        }

    private fun matchesAssetLinks(
        statements: JSONArray,
        callingAppInfo: CallingAppInfo
    ): Boolean {
        val fingerprints = signingCertificates(callingAppInfo)
            .map(::sha256Fingerprint)
            .toSet()

        for (index in 0 until statements.length()) {
            val statement = statements.optJSONObject(index) ?: continue
            val relations = statement.optJSONArray("relation") ?: continue
            var hasCredentialRelation = false
            for (relationIndex in 0 until relations.length()) {
                val relation = relations.optString(relationIndex)
                if (relation == "delegate_permission/common.get_login_creds") {
                    hasCredentialRelation = true
                    break
                }
            }
            if (!hasCredentialRelation) continue

            val target = statement.optJSONObject("target") ?: continue
            if (target.optString("namespace") != "android_app") continue
            if (target.optString("package_name") != callingAppInfo.packageName) continue

            val allowed = target.optJSONArray("sha256_cert_fingerprints") ?: continue
            for (fingerprintIndex in 0 until allowed.length()) {
                if (allowed.optString(fingerprintIndex).uppercase() in fingerprints) {
                    return true
                }
            }
        }
        return false
    }

    private fun signingCertificates(callingAppInfo: CallingAppInfo): List<Signature> {
        val signingInfo = callingAppInfo.signingInfo
        return if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners.toList()
        } else {
            signingInfo.signingCertificateHistory.toList()
        }
    }

    private fun sha256Fingerprint(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }

    fun normalizeRpId(value: String): String? = runCatching {
        val ascii = IDN.toASCII(value.trim(), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
        val labels = ascii.split('.')
        val isIpv4Literal = labels.size == 4 && labels.all { label ->
            label.toIntOrNull()?.let { it in 0..255 } == true
        }
        if (
            ascii.length !in 1..253 ||
            '.' !in ascii ||
            isIpv4Literal ||
            ascii.startsWith('.') ||
            ascii.endsWith('.') ||
            labels.any { label ->
                label.length !in 1..63 ||
                    label.startsWith('-') ||
                    label.endsWith('-') ||
                    label.any { character ->
                        character !in 'a'..'z' &&
                            character !in '0'..'9' &&
                            character != '-'
                    }
            }
        ) {
            null
        } else {
            ascii
        }
    }.getOrNull()

    private fun hasPublicNetworkAddress(host: String): Boolean = runCatching {
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all { address ->
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress &&
                !isIpv6UniqueLocal(address.address)
        }
    }.getOrDefault(false)

    private fun isIpv6UniqueLocal(bytes: ByteArray): Boolean =
        bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
}
