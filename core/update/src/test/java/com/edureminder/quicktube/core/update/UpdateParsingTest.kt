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
    fun picksTheApkForThisPhonesCpu() {
        fun asset(name: String) = """{"name":"$name","size":1,"browser_download_url":"https://g/$name"}"""
        val json = """{"tag_name":"v1.0.9","assets":[
            ${asset("QuickTube-v1.0.9-arm64-v8a.apk")}, ${asset("QuickTube-v1.0.9-arm64-v8a.apk.sha256")},
            ${asset("QuickTube-v1.0.9-armeabi-v7a.apk")}, ${asset("QuickTube-v1.0.9-armeabi-v7a.apk.sha256")},
            ${asset("QuickTube-v1.0.9.apk")}, ${asset("QuickTube-v1.0.9.apk.sha256")}]}"""
        val arm64 = UpdateParsing.parseRelease(json, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("https://g/QuickTube-v1.0.9-arm64-v8a.apk", arm64.apkUrl)
        assertEquals("https://g/QuickTube-v1.0.9-arm64-v8a.apk.sha256", arm64.sha256Url)
        val arm32 = UpdateParsing.parseRelease(json, listOf("armeabi-v7a", "armeabi"))!!
        assertEquals("https://g/QuickTube-v1.0.9-armeabi-v7a.apk", arm32.apkUrl)
        // Unknown CPU: the universal APK.
        val other = UpdateParsing.parseRelease(json, listOf("x86_64"))!!
        assertEquals("https://g/QuickTube-v1.0.9.apk", other.apkUrl)
        assertEquals("https://g/QuickTube-v1.0.9.apk.sha256", other.sha256Url)
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
