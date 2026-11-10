package com.airbuds.companion.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppReleaseParserTest {

    @Test
    fun `selects the release apk and strips the digest prefix`() {
        val release = AppReleaseParser.parse(
            """
            {
              "tag_name": "v0.2.0",
              "assets": [
                {"name": "app-debug.apk", "browser_download_url": "https://example/app-debug.apk", "size": 10},
                {"name": "app-release.apk", "browser_download_url": "https://example/app-release.apk", "size": 1024, "digest": "sha256:abc123"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("v0.2.0", release?.tag)
        assertEquals(AppVersion(0, 2, 0), release?.version)
        assertEquals("app-release.apk", release?.asset?.name)
        assertEquals("https://example/app-release.apk", release?.asset?.url)
        assertEquals(1024L, release?.asset?.size)
        assertEquals("abc123", release?.asset?.sha256)
    }

    @Test
    fun `falls back to the first apk`() {
        val release = AppReleaseParser.parse(
            """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://example/notes.txt"},
                {"name": "build.apk", "browser_download_url": "https://example/build.apk", "size": 5}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("build.apk", release?.asset?.name)
    }

    @Test
    fun `rejects a release without an apk`() {
        assertNull(
            AppReleaseParser.parse(
                """
                {"tag_name": "v1.0.0", "assets": [{"name": "notes.txt", "browser_download_url": "https://example/notes.txt"}]}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `rejects an invalid tag`() {
        assertNull(AppReleaseParser.parse("""{"tag_name": "nightly", "assets": []}"""))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(AppReleaseParser.parse("<html>not json</html>"))
        assertNull(AppReleaseParser.parse(""))
    }
}
