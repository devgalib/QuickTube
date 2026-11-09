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

    /**
     * Every link in [text] (a pasted list or a .txt file), in order and each once: full addresses anywhere in a line,
     * plus bare "youtu.be/…" style lines. Trailing punctuation is dropped; a link differing only by "www."/"m." or a
     * trailing slash counts as the same.
     */
    fun extractUrls(text: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val found = urlRegex.findAll(line).map { it.value }.toList().ifEmpty {
                line.split(Regex("""\s+""")).filter { it.contains('.') && it.contains('/') && looksLikeUrl(it) }.map(::normalize)
            }
            for (raw in found) {
                val u = raw.trimEnd('.', ',', ')', ';', ']', '"', '\'')
                // The same YouTube video by any address (youtu.be, watch?v=, shorts/) counts once.
                val key = youtubeVideoId(u)?.let { "yt:$it" } ?: u.lowercase().replace(Regex("""^https?://(www\.|m\.)?"""), "").trimEnd('/')
                if (key.length > 3 && seen.add(key)) out += u
            }
        }
        return out
    }

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

    /**
     * The address of a media file without its query (CDNs put expiring tokens there), when its name says which video
     * it is (Facebook's "…_n.mp4"); null for shared names like YouTube's "/videoplayback" or playlists.
     */
    fun mediaFileKey(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        // TikTok's CDN names each video by a long id folder ("…/video/tos/<region>/<bucket>/<id>/?…") without an extension.
        if ("/video/tos/" in path && path.trimEnd('/').substringAfterLast('/').length >= 16) return path.trimEnd('/')
        return path.takeIf { ext in setOf("mp4", "webm", "m4a", "mov", "mkv", "mp3") && name.length >= 12 }
    }

    /**
     * Whether [url] is the page of one video or post (a TikTok video, an Instagram post or reel, a YouTube video, a
     * tweet…), as opposed to a feed, profile or home page where many videos play under one address.
     */
    fun namesOneMedia(url: String?): Boolean {
        val u = url ?: return false
        val path = runCatching { URI(u).path.orEmpty() }.getOrDefault("").trimEnd('/')
        val full = u.lowercase()
        return when (platformOf(u)) {
            Platform.YOUTUBE -> youtubeVideoId(u) != null
            Platform.TIKTOK -> Regex("""/(video|photo)/\d+""").containsMatchIn(path) || host(u)?.let { it.startsWith("vm.") || it.startsWith("vt.") } == true
            Platform.INSTAGRAM -> Regex("""/(p|reel|reels|tv)/[^/]+|/stories/[^/]+/\d+""").containsMatchIn(path)
            Platform.FACEBOOK -> Regex("""/(videos|reel|reels|posts|permalink|watch|share)/|story_fbid=|[?&]v=\d|/photo""").containsMatchIn(full)
            else -> if (host(u)?.let { it == "x.com" || it == "twitter.com" } == true) "/status/" in path else path.isNotEmpty()
        }
    }

    /** A title that says nothing about the video: blank, "video", or just the site's name ("Facebook"). */
    fun isGenericTitle(title: String): Boolean {
        val t = title.trim().lowercase()
        return t.isEmpty() || t == "video" || Platform.entries.any { t == it.title.lowercase() || t == "${it.title.lowercase()} video" }
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
