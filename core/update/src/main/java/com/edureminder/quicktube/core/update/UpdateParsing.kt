package com.edureminder.quicktube.core.update

import org.json.JSONObject

/** A newer release found on GitHub. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String?,
    val pageUrl: String,
)

/** Pure parsing helpers (unit-tested): GitHub release JSON → [UpdateInfo]. */
object UpdateParsing {
    /**
     * Releases are tagged `v<major>.<minor>.<versionCode>` by the release workflow (versionCode = CI run number),
     * so the last number of the tag is the versionCode.
     */
    fun versionCodeOf(tag: String): Long? =
        Regex("""^v?\d+(?:\.\d+)*\.(\d+)$""").matchEntire(tag.trim())?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Picks the APK built for this phone's CPU ([abis], most preferred first, e.g. `Build.SUPPORTED_ABIS`):
     * `QuickTube-v1.0.9-arm64-v8a.apk`, falling back to the universal `QuickTube-v1.0.9.apk`. Each APK's
     * checksum is the asset named `<apk>.sha256`.
     */
    fun parseRelease(json: String, abis: List<String> = emptyList()): UpdateInfo? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        val code = versionCodeOf(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        val apks = all.filter { it.optString("name").endsWith(".apk") }
        if (apks.isEmpty()) return null
        val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        fun abiOf(name: String) = knownAbis.firstOrNull { name.endsWith("-$it.apk") }
        val asset = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { abiOf(it.optString("name")) == abi } }
            ?: apks.firstOrNull { abiOf(it.optString("name")) == null }
            ?: return null
        val shaName = asset.optString("name") + ".sha256"
        return UpdateInfo(
            versionCode = code,
            versionName = tag.removePrefix("v"),
            notes = cleanNotes(o.optString("body")),
            apkUrl = asset.optString("browser_download_url"),
            apkSize = asset.optLong("size", -1),
            sha256Url = all.firstOrNull { it.optString("name") == shaName }?.optString("browser_download_url"),
            pageUrl = o.optString("html_url"),
        )
    }

    /** GitHub's generated notes → short plain text: drop headings/links/"Full Changelog", keep the bullet titles. */
    fun cleanNotes(body: String): String = body.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog**") && !it.startsWith("<!--") }
        .map { line ->
            line.removePrefix("#").removePrefix("#").removePrefix("#").trim()
                .replace(Regex("""\s+by @[\w-]+ in https?://\S+"""), "")
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace(Regex("""^[*-]\s+"""), "• ")
                .replace("**", "")
        }
        .filter { !it.equals("What's Changed", true) && !it.equals("New Contributors", true) }
        .take(12)
        .joinToString("\n")

    /** First 64 hex chars of a `sha256sum` line ("<hash>  file.apk"). */
    fun parseSha256(text: String): String? = Regex("""\b([0-9a-fA-F]{64})\b""").find(text)?.groupValues?.get(1)?.lowercase()
}
