package com.yiran.cerberus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {
    @Test
    fun generatedPasswordContainsEverySelectedCharacterGroup() {
        val password = PasswordGenerator.generate(length = 16)

        assertEquals(16, password.length)
        assertTrue(password.any(Char::isUpperCase))
        assertTrue(password.any(Char::isLowerCase))
        assertTrue(password.any(Char::isDigit))
        assertTrue(password.any { !it.isLetterOrDigit() })
    }

    @Test
    fun excludedCharacterGroupIsNotUsed() {
        val password = PasswordGenerator.generate(
            length = 12,
            includeSpecial = false
        )

        assertFalse(password.any { !it.isLetterOrDigit() })
    }

    @Test
    fun lengthMustFitSelectedGroups() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordGenerator.generate(length = 3)
        }
    }
}
