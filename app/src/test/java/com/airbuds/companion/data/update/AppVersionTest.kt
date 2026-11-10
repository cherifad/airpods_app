package com.airbuds.companion.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `parses a tag with a v prefix`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
    }

    @Test
    fun `defaults missing components`() {
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("0.2"))
    }

    @Test
    fun `ignores a pre-release suffix`() {
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("v0.2.0-beta.1"))
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v1"))
        assertNull(AppVersion.parse("release"))
    }

    @Test
    fun `compares numerically, not as strings`() {
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.9")!!)
        assertTrue(AppVersion.parse("0.2.0")!! > AppVersion.parse("0.1.9")!!)
        assertEquals(0, AppVersion.parse("0.1.0")!!.compareTo(AppVersion.parse("v0.1.0")!!))
    }
}
