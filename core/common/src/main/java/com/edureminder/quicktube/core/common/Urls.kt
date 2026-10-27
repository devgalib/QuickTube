package com.edureminder.quicktube.core.common

import java.net.URI

enum class Platform(val title: String, val home: String, val packageName: String?) {
    YOUTUBE("YouTube", "https://m.youtube.com/", "com.google.android.youtube"),
    INSTAGRAM("Instagram", "https://www.instagram.com/", "com.instagram.android"),
    FACEBOOK("Facebook", "https://m.facebook.com/", "com.facebook.katana"),
    TIKTOK("TikTok", "https://www.tiktok.com/", "com.zhiliaoapp.musically"),
    TWITTER("Twitter/X", "https://x.com/", "com.twitter.android"),
    WHATSAPP("WhatsApp Status", "", "com.whatsapp"),
    SOUNDCLOUD("SoundCloud", "https://m.soundcloud.com/", null),
    OTHER("Web", "", null);
}

object Urls {
    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')') }

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        if (t.contains(' ')) return false
        return t.startsWith("http://", true) || t.startsWith("https://", true) ||
            Regex("""^[\w-]+(\.[\w-]+)+(/.*)?$""").matches(t)
    }

    fun normalize(text: String): String {
        val t = text.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    fun host(url: String?): String? = try {
        url?.let { URI(it).host?.removePrefix("www.")?.removePrefix("m.") }
    } catch (_: Exception) {
        null
    }

    fun platformOf(url: String?): Platform {
        val h = host(url) ?: return Platform.OTHER
        return when {
            h.contains("youtube.com") || h.contains("youtu.be") -> Platform.YOUTUBE
            h.contains("instagram.com") -> Platform.INSTAGRAM
            h.contains("facebook.com") || h.contains("fb.watch") || h.contains("fb.com") -> Platform.FACEBOOK
            h.contains("tiktok.com") -> Platform.TIKTOK
            h.contains("twitter.com") || h == "x.com" || h.contains("t.co") -> Platform.TWITTER
            h.contains("soundcloud.com") -> Platform.SOUNDCLOUD
            else -> Platform.OTHER
        }
    }

    /** Characters that are not allowed in file names on Android storage. */
    /**
     * A file name from a title. Android allows 255 *bytes* per name and Bengali/emoji take 3–4 bytes per
     * character, so it's cut by UTF-8 size (never inside a character), leaving room for " (2)", the quality
     * and the temporary ".part.chunks.tmp" suffixes.
     */
    fun safeFileName(name: String, maxBytes: Int = 150): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim()
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd(' ', '.', '_').ifBlank { "download" }
    }

    private val ytId = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""")

    /** The 11-character video id of any YouTube watch/shorts/live/youtu.be link, or null. */
    fun youtubeVideoId(url: String?): String? = url?.let { ytId.find(it)?.groupValues?.get(1) }

    fun googleSearch(q: String) = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
}
