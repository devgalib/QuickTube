package com.edureminder.quicktube.core.download

import java.io.File
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong

/** How a file is split into ranged chunks fetched by several connections at once. */
internal object SegmentPlan {
    /** Small enough that a lost connection costs little, big enough to keep request overhead low. */
    const val CHUNK = 4L * 1024 * 1024

    fun chunkCount(total: Long, chunk: Long = CHUNK): Int = ((total + chunk - 1) / chunk).toInt()

    /** Inclusive byte range of chunk [i]. */
    fun range(i: Int, total: Long, chunk: Long = CHUNK): LongRange {
        val start = i * chunk
        return start..minOf(start + chunk, total) - 1
    }

    /** Parallel connections for a file of [total] bytes: more for big files, one for tiny ones. */
    fun connectionsFor(total: Long): Int = when {
        total < 2L * 1024 * 1024 -> 1
        total < 24L * 1024 * 1024 -> 4
        else -> 8
    }
}

/**
 * Which chunks of a part file are complete, persisted next to it as "<total> <chunk> <bitset hex>" so a
 * paused or killed download resumes where it was. A legacy part file (downloaded front-to-back by older
 * versions) counts its fully written prefix as done.
 */
internal class ChunkState private constructor(
    private val file: File,
    val total: Long,
    val chunk: Long,
    private val done: BitSet,
) {
    val count = SegmentPlan.chunkCount(total, chunk)

    @Synchronized fun isDone(i: Int) = done[i]

    @Synchronized fun markDone(i: Int) {
        done.set(i)
        save()
    }

    @Synchronized fun pending(): List<Int> = (0 until count).filter { !done[it] }

    @Synchronized fun doneBytes(): Long = (0 until count).filter { done[it] }.sumOf { SegmentPlan.range(it, total, chunk).let { r -> r.last - r.first + 1 } }

    @Synchronized fun isComplete() = done.cardinality() == count

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText("$total $chunk ${done.toByteArray().joinToString("") { "%02x".format(it) }}")
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun delete() { file.delete() }

    companion object {
        fun stateFile(part: File) = File(part.path + ".chunks")

        fun load(part: File, total: Long, chunk: Long = SegmentPlan.CHUNK): ChunkState {
            val f = stateFile(part)
            val saved = runCatching { f.readText().trim().split(' ') }.getOrNull()
            if (saved != null && saved.size == 3 && saved[0].toLongOrNull() == total && saved[1].toLongOrNull() == chunk && part.exists()) {
                val bytes = saved[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                return ChunkState(f, total, chunk, BitSet.valueOf(bytes))
            }
            val bits = BitSet()
            if (saved == null && part.exists()) {
                // Front-to-back part from an older version: everything before its length is valid.
                val prefix = part.length()
                for (i in 0 until SegmentPlan.chunkCount(total, chunk)) {
                    if (SegmentPlan.range(i, total, chunk).last < prefix) bits.set(i) else break
                }
            }
            return ChunkState(f, total, chunk, bits).also { it.save() }
        }
    }
}

/** Live counters for one download task; a ticker copies them to the database twice a second. */
internal class TaskProgress(video: Long, audio: Long, videoTotal: Long, audioTotal: Long) {
    val video = AtomicLong(video)
    val audio = AtomicLong(audio)
    val videoTotal = AtomicLong(videoTotal)
    val audioTotal = AtomicLong(audioTotal)
    val meter = SpeedMeter()

    fun counter(isAudio: Boolean) = if (isAudio) audio else video
    fun totalOf(isAudio: Boolean) = if (isAudio) audioTotal else videoTotal
}

/** Thread-safe bytes/second over a sliding 2 s window. */
internal class SpeedMeter {
    private val window = ArrayDeque<Pair<Long, Int>>()

    @Synchronized fun add(n: Int) {
        val now = System.currentTimeMillis()
        window.addLast(now to n)
        while (window.isNotEmpty() && now - window.first().first > 2000) window.removeFirst()
    }

    @Synchronized fun bytesPerSecond(): Long {
        if (window.isEmpty()) return 0
        val now = System.currentTimeMillis()
        while (window.isNotEmpty() && now - window.first().first > 2000) window.removeFirst()
        if (window.isEmpty()) return 0
        val span = (now - window.first().first).coerceAtLeast(250)
        return window.sumOf { it.second.toLong() } * 1000 / span
    }
}
