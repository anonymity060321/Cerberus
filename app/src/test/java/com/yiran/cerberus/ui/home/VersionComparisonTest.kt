package com.yiran.cerberus.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparisonTest {
    @Test
    fun comparesNumericComponentsWithoutDroppingSegments() {
        assertTrue(isVersionNewer("1.5.9", "1.5.10"))
        assertFalse(isVersionNewer("1.5.10", "1.5.9"))
        assertFalse(isVersionNewer("1.5.1", "1.5.1.0"))
    }

    @Test
    fun stableReleaseIsNewerThanMatchingPreRelease() {
        assertTrue(isVersionNewer("1.6.0-alpha.4", "1.6.0"))
        assertFalse(isVersionNewer("1.6.0", "1.6.0-alpha.4"))
    }

    @Test
    fun invalidVersionDoesNotTriggerAnUpdate() {
        assertFalse(isVersionNewer("development", "1.6.0"))
        assertFalse(isVersionNewer("1.5.1", "latest"))
    }
}
