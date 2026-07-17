package com.yiran.cerberus.passkey

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class HardwareKeyMaterial(
    val alias: String,
    val publicKeyX: String,
    val publicKeyY: String
)

/**
 * Owns all Passkey private-key operations.
 *
 * Key material is generated inside Android Keystore, must be hardware-backed,
 * requires user authentication for every signature, and is never exportable to
 * application memory or .cerb backups.
 */
object PasskeyKeyStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "cerberus.passkey."

    fun createKeyMaterial(): HardwareKeyMaterial {
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        val keyPair = try {
            generateKeyPair(alias, strongBox = true)
        } catch (_: Exception) {
            // StrongBox support varies by device and algorithm. The fallback
            // is accepted only after KeyInfo confirms TEE-grade protection.
            delete(alias)
            generateKeyPair(alias, strongBox = false)
        }

        try {
            requireHardwareBacked(keyPair.private)
            val publicKey = keyPair.public as? ECPublicKey
                ?: throw GeneralSecurityException("Passkey public key is not EC")
            return HardwareKeyMaterial(
                alias = alias,
                publicKeyX = encodeCoordinate(publicKey.w.affineX),
                publicKeyY = encodeCoordinate(publicKey.w.affineY)
            )
        } catch (exception: Exception) {
            delete(alias)
            throw exception
        }
    }

    fun createSigningSignature(alias: String): Signature {
        val privateKey = keyStore().getKey(alias, null) as? PrivateKey
            ?: throw GeneralSecurityException("Passkey hardware key is missing")
        requireHardwareBacked(privateKey)
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
        }
    }

    fun contains(alias: String): Boolean =
        alias.isNotBlank() && runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String) {
        if (alias.isBlank()) return
        runCatching {
            keyStore().deleteEntry(alias)
        }
    }

    private fun generateKeyPair(alias: String, strongBox: Boolean): KeyPair {
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or
                    KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

        return KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            PROVIDER
        ).run {
            initialize(specification)
            generateKeyPair()
        }
    }

    private fun requireHardwareBacked(privateKey: PrivateKey) {
        val keyInfo = KeyFactory.getInstance(privateKey.algorithm, PROVIDER)
            .getKeySpec(privateKey, KeyInfo::class.java)
        when (keyInfo.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX,
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> Unit
            else -> throw GeneralSecurityException(
                "This device cannot provide hardware-backed Passkey storage"
            )
        }
    }

    private fun encodeCoordinate(value: BigInteger): String {
        val signed = value.toByteArray()
        val unsigned = if (signed.size == 33 && signed[0] == 0.toByte()) {
            signed.copyOfRange(1, signed.size)
        } else {
            signed
        }
        if (unsigned.size > 32) {
            throw GeneralSecurityException("Invalid P-256 public-key coordinate")
        }
        val fixed = ByteArray(32)
        unsigned.copyInto(fixed, destinationOffset = fixed.size - unsigned.size)
        return Base64.encodeToString(
            fixed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }
}
