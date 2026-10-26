package com.edureminder.quicktube.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateParsingTest {
    @Test
    fun versionCodes() {
        assertEquals(12L, UpdateParsing.versionCodeOf("v1.0.12"))
        assertEquals(40L, UpdateParsing.versionCodeOf("1.1.40"))
        assertNull(UpdateParsing.versionCodeOf("nightly"))
        assertNull(UpdateParsing.versionCodeOf("v1.0.12-beta"))
    }

    @Test
    fun parsesRelease() {
        val json = """{"tag_name":"v1.0.7","html_url":"https://github.com/o/r/releases/tag/v1.0.7","draft":false,"prerelease":false,
            "body":"## What's Changed\n* Add update checker by @dev in https://github.com/o/r/pull/3\n\n**Full Changelog**: https://x",
            "assets":[{"name":"QuickTube-v1.0.7.apk","size":123,"browser_download_url":"https://g/a.apk"},
                      {"name":"QuickTube-v1.0.7.apk.sha256","size":90,"browser_download_url":"https://g/a.apk.sha256"}]}"""
        val u = UpdateParsing.parseRelease(json)!!
        assertEquals(7L, u.versionCode)
        assertEquals("1.0.7", u.versionName)
        assertEquals("https://g/a.apk", u.apkUrl)
        assertEquals("https://g/a.apk.sha256", u.sha256Url)
        assertEquals("• Add update checker", u.notes)
    }

    @Test
    fun ignoresPrereleaseAndMissingApk() {
        assertNull(UpdateParsing.parseRelease("""{"tag_name":"v1.0.8","prerelease":true,"assets":[]}"""))
        assertNull(UpdateParsing.parseRelease("""{"tag_name":"v1.0.8","assets":[{"name":"notes.txt"}]}"""))
    }

    @Test
    fun sha256() {
        val h = "a".repeat(64)
        assertEquals(h, UpdateParsing.parseSha256("$h  QuickTube-v1.0.7.apk\n"))
    }
}
