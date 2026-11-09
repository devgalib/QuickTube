package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind

/** Bitrate of the MP3s we make: transparent for the AAC/Opus sources sites serve (≤ 160K), half the size of 320K. */
internal const val MP3_KBPS = 192

/**
 * Audio formats made on the phone from the site's best audio, by the file extension the download asks for (see
 * DownloadEngine.finish): MP3 at three bitrates (LAME), AAC as a plain .aac stream and Opus in an .opus (Ogg) file
 * (both repackaged, no quality lost), and lossless FLAC and WAV (decoded). [kbps] is the result's bitrate, 0 = lossless.
 */
private data class Made(val ext: String, val mime: String, val title: String, val info: String, val kbps: Int, val from: String? = null)

private val made = listOf(
    Made("mp3", "audio/mpeg", "MP3 (320K)", "Best MP3 quality", 320),
    Made("mp3", "audio/mpeg", "MP3 (128K)", "Smallest MP3", 128),
    Made("aac", "audio/aac", "AAC (.aac)", "Same sound as M4A, plain .aac file", -1, from = "m4a"),
    Made("opus", "audio/ogg", "Opus (.opus)", "Small and clear, .opus file", -1, from = "webm"),
    Made("flac", "audio/flac", "FLAC (lossless)", "Studio-quality, bigger file", 0),
    Made("wav", "audio/wav", "WAV (uncompressed)", "For editing apps, largest file", 0),
)

/**
 * Adds the formats people ask for to a video's audio choices: MP3 192K right after the best audio (so the short list
 * shows both: it plays in every car, speaker and old phone), and MP3 320K/128K, AAC, Opus, FLAC and WAV in "All
 * formats". Each is downloaded like the site's audio it comes from, then converted on the phone. Nothing changes when
 * the site already offers MP3 or has no sound at all.
 */
internal fun withMp3(options: List<DownloadOption>): List<DownloadOption> {
    val audio = options.filter { it.kind == FormatKind.AUDIO }
    if (audio.isEmpty() || audio.any { it.ext == "mp3" }) return options
    val native = audio.filter { !it.extractAudio }
    val best = native.maxWithOrNull(compareBy({ it.ext == "m4a" }, { it.bitrate })) ?: audio.first()
    fun from(source: DownloadOption, m: Made, id: String) = source.copy(
        id = id, title = m.title, info = m.info, ext = m.ext, mimeType = m.mime, label = null,
        bitrate = when { m.kbps > 0 -> m.kbps; m.kbps == 0 -> 0; else -> source.bitrate },
        sizeBytes = estimate(source, m.kbps, m.ext),
    )
    val mp3 = from(best, Made("mp3", "audio/mpeg", "MP3 (${MP3_KBPS}K)", "Plays everywhere: cars, speakers, any phone", MP3_KBPS), "mp3-" + best.id)
    val extra = made.mapNotNull { m ->
        // AAC and Opus are the site's own stream repackaged: only from a stream of that kind.
        val source = if (m.from == null) best else native.filter { it.ext == m.from }.maxByOrNull { it.bitrate } ?: return@mapNotNull null
        from(source, m, "${m.ext}${m.kbps.takeIf { it > 0 } ?: ""}-" + source.id)
    }
    val at = options.indexOf(audio.first()) + 1
    val lastAudio = options.indexOf(audio.last()) + 1
    return options.toMutableList().apply {
        addAll(lastAudio, extra)
        add(at, mp3)
    }
}

/** The made file's size from the source's: by bitrate for MP3, about 1411K (CD audio) for WAV, ~60% of that for FLAC. */
private fun estimate(source: DownloadOption, kbps: Int, ext: String): Long {
    val size = source.sizeBytes
    if (size <= 0) return size
    val sourceKbps = source.bitrate.takeIf { it > 0 } ?: return -1
    val seconds = size * 8 / 1000.0 / sourceKbps
    return when {
        kbps > 0 -> (seconds * kbps * 1000 / 8).toLong()
        kbps == 0 -> (seconds * 1411 * 1000 / 8 * if (ext == "flac") 0.6 else 1.0).toLong()
        else -> size
    }
}
