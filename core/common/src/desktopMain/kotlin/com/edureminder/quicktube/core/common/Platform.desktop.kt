package com.edureminder.quicktube.core.common

import java.util.concurrent.TimeUnit

actual object QtLog {
    actual fun i(tag: String, message: String) { System.err.println("I/$tag: $message") }
    actual fun w(tag: String, message: String, error: Throwable?) {
        System.err.println("W/$tag: $message" + (error?.let { " ($it)" } ?: ""))
    }
}

actual object MediaProbe {
    /** Uses ffprobe (installed with ffmpeg, which the desktop app needs anyway). */
    actual fun durationSec(path: String): Long = runCatching {
        val p = ProcessBuilder(Tools.ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", path)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 }
        out.lineSequence().firstNotNullOfOrNull { it.toDoubleOrNull() }?.toLong() ?: -1
    }.getOrDefault(-1)
}

/** The command-line tools the desktop app runs: bundled next to the app when packaged, else from PATH. */
object Tools {
    private fun find(name: String): String {
        val exe = if (DesktopDirs.isWindows) "$name.exe" else name
        // Packaged app: tools are copied into the app's resources folder (see desktopApp/build.gradle.kts).
        System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, exe) }?.takeIf { it.canExecute() }?.let { return it.path }
        java.io.File(DesktopDirs.data, "bin/$exe").takeIf { it.canExecute() }?.let { return it.path }
        return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it, exe) }.firstOrNull { it.canExecute() }?.path ?: exe
    }

    val ffmpeg: String by lazy { find("ffmpeg") }
    val ffprobe: String by lazy { find("ffprobe") }
}
