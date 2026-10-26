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

    fun parseRelease(json: String): UpdateInfo? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        val code = versionCodeOf(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk.sha256") -> sha = a.optString("browser_download_url")
                name.endsWith(".apk") -> apk = a
            }
        }
        val asset = apk ?: return null
        return UpdateInfo(
            versionCode = code,
            versionName = tag.removePrefix("v"),
            notes = cleanNotes(o.optString("body")),
            apkUrl = asset.optString("browser_download_url"),
            apkSize = asset.optLong("size", -1),
            sha256Url = sha,
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
