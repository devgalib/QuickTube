package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.Chapter
import com.edureminder.quicktube.core.model.SkipSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Chapters written in a description, the way YouTube itself reads them: a line per chapter starting at 0:00. */
object Chapters {
    private val LINE = Regex("""^\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*[-–—:|•]?\s*(.+?)\s*$""")

    /** At least three times in order, the first at 0:00, all within the video; otherwise none. */
    fun fromDescription(html: String, durationMs: Long): List<Chapter> {
        val text = html.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        val found = text.lines().mapNotNull { line ->
            val m = LINE.find(line) ?: return@mapNotNull null
            val ms = m.groupValues[1].split(':').fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
            Chapter(m.groupValues[2].trim(), ms)
        }
        if (found.size < 3 || found.first().startMs != 0L) return emptyList()
        if (found.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return emptyList()
        if (durationMs > 0 && found.last().startMs >= durationMs) return emptyList()
        return found
    }
}

/**
 * SponsorBlock (sponsor.ajay.app): parts of YouTube videos other viewers marked as sponsor, self-promotion,
 * "subscribe" reminders, intros and outros. Asked by the first 4 characters of the id's SHA-256, so the server never
 * learns which video is being watched.
 */
object SponsorBlock {
    val CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "music_offtopic")

    fun hashPrefix(videoId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)

    fun url(videoId: String) = "https://sponsor.ajay.app/api/skipSegments/${hashPrefix(videoId)}?categories=" +
        java.net.URLEncoder.encode(CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")

    /** The answer's segments for [videoId] (it lists every video sharing the prefix), in order. */
    fun parse(json: String, videoId: String): List<SkipSegment> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
            .firstOrNull { it["videoID"]?.jsonPrimitive?.content == videoId }
            ?.get("segments")?.let { it as? JsonArray }.orEmpty()
            .mapNotNull { s ->
                val o = s.jsonObject
                val seg = o["segment"]?.jsonArray ?: return@mapNotNull null
                val start = seg.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = seg.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                if (end - start < 1) null else SkipSegment((start * 1000).toLong(), (end * 1000).toLong(), o["category"]?.jsonPrimitive?.content ?: "sponsor")
            }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    /** "Skipped sponsor", "Skipped intro"… */
    fun label(category: String) = "Skipped " + when (category) {
        "selfpromo" -> "self-promotion"; "interaction" -> "subscribe reminder"; "music_offtopic" -> "non-music part"; else -> category
    }
}

/** Fetches [SponsorBlock] segments (empty when there are none, or on any failure). */
class SponsorBlockClient(private val client: okhttp3.OkHttpClient) {
    suspend fun segments(videoId: String): List<SkipSegment> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder().url(SponsorBlock.url(videoId)).header("User-Agent", "QuickTube").build()
            client.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute()
                .use { r -> if (r.isSuccessful) SponsorBlock.parse(r.body.string(), videoId) else emptyList() }
        }.getOrDefault(emptyList())
    }
}
