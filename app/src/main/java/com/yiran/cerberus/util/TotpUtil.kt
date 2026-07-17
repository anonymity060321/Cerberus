package com.yiran.cerberus.util

import uniffi.rust_core.OtpHashAlgorithm
import uniffi.rust_core.OtpType
import uniffi.rust_core.generateTotp as rustGenerateTotp
import uniffi.rust_core.generateSteamGuardCode as rustGenerateSteamGuardCode
import java.util.Base64

object TotpUtil {

    /**
     * 直接使用 Rust 导出的 OtpHashAlgorithm
     */
    fun generateTOTP(secret: String, algorithm: OtpHashAlgorithm = OtpHashAlgorithm.SHA1): String {
        return try {
            // 调用 Rust 实现的标准 TOTP (RFC 6238)
            rustGenerateTotp(secret, algorithm, 6.toUInt(), 30.toULong())
        } catch (e: Exception) {
            e.printStackTrace()
            "ERROR"
        }
    }

    fun generateCode(
        secret: String,
        algorithm: OtpHashAlgorithm = OtpHashAlgorithm.SHA1,
        otpType: OtpType = OtpType.TOTP
    ): String {
        return try {
            when (otpType) {
                OtpType.TOTP -> rustGenerateTotp(secret, algorithm, 6.toUInt(), 30.toULong())
                OtpType.STEAM -> rustGenerateSteamGuardCode(secret)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "ERROR"
        }
    }

    /**
     * 简单的 Base32 格式检查
     */
    fun isValidSecret(secret: String): Boolean {
        if (secret.isBlank()) return false
        val cleanSecret = secret.trim().uppercase().replace(" ", "")
        // Base32 字符集检查: A-Z, 2-7
        val regex = "^[A-Z2-7]*={0,6}$".toRegex()
        return regex.matches(cleanSecret)
    }

    fun isValidSteamSharedSecret(secret: String): Boolean {
        val cleanSecret = secret.filterNot(Char::isWhitespace)
        if (cleanSecret.isEmpty()) return false

        return try {
            Base64.getDecoder().decode(cleanSecret).isNotEmpty()
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getDecoder().decode(padBase64(cleanSecret)).isNotEmpty()
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    private fun padBase64(value: String): String {
        val missingPadding = (4 - value.length % 4) % 4
        return value + "=".repeat(missingPadding)
    }

    fun getProgress(): Float {
        val time = System.currentTimeMillis() / 1000
        val remaining = 30 - (time % 30)
        return remaining / 30f
    }

    fun getRemainingSeconds(): Long {
        val time = System.currentTimeMillis() / 1000
        return 30 - (time % 30)
    }
}
