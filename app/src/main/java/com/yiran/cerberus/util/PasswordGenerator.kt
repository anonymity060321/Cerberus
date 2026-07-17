package com.yiran.cerberus.util

import java.security.SecureRandom

object PasswordGenerator {
    private val secureRandom = SecureRandom()
    
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SPECIAL = "!@#$%^&*()-_=+[]{}|;:,.<>?"
    
    fun generate(
        length: Int = 16,
        includeUpper: Boolean = true,
        includeLower: Boolean = true,
        includeDigits: Boolean = true,
        includeSpecial: Boolean = true
    ): String {
        require(length >= 0) { "Password length cannot be negative" }
        val selectedGroups = buildList {
            if (includeUpper) add(UPPER)
            if (includeLower) add(LOWER)
            if (includeDigits) add(DIGITS)
            if (includeSpecial) add(SPECIAL)
        }
        if (selectedGroups.isEmpty()) return ""
        require(length >= selectedGroups.size) {
            "Password length must fit every selected character group"
        }

        val charPool = selectedGroups.joinToString("")
        val password = selectedGroups
            .mapTo(mutableListOf()) { group -> randomCharacter(group) }
        repeat(length - password.size) {
            password += randomCharacter(charPool)
        }
        for (index in password.lastIndex downTo 1) {
            val swapIndex = secureRandom.nextInt(index + 1)
            val value = password[index]
            password[index] = password[swapIndex]
            password[swapIndex] = value
        }
        return password.joinToString("")
    }

    private fun randomCharacter(group: String): Char =
        group[secureRandom.nextInt(group.length)]
}
