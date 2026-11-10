package com.edureminder.quicktube.core.common

import java.io.File

/**
 * Every temporary file a download leaves while it runs, so whichever screen deletes it (Library, Downloading, the
 * engine itself) removes all of them: the video and audio parts, their resume indexes, segmented-download state,
 * the in-place merge marker, and the task's private parts (`noBackupFilesDir/parts/task-<id>.*`).
 */
object PartFiles {
    fun of(filePath: String, taskId: Long, partsDir: File?): List<File> = buildList {
        for (p in listOf("$filePath.part", "$filePath.audio.part")) {
            add(File(p)); add(File("$p.idx")); add(File("$p.chunks")); add(File("$p.merging"))
        }
        partsDir?.listFiles { f -> f.name.startsWith("task-$taskId.") }?.let { addAll(it) }
    }

    fun delete(filePath: String, taskId: Long, partsDir: File?) = of(filePath, taskId, partsDir).forEach { it.delete() }
}
