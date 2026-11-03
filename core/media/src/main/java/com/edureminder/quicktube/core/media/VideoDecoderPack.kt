package com.edureminder.quicktube.core.media

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.LibraryLoader
import androidx.media3.common.util.UnstableApi
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One native library of the pack: its file name, size in bytes and SHA-256. */
internal data class PackFile(val name: String, val size: Long, val sha256: String)

/**
 * FFmpeg's software video decoders (10-bit HEVC, H.264 High 10, MPEG-2, VP9…), downloaded on demand instead of shipped:
 * most videos play on the phone's own decoders, so only people who hit one that doesn't pay the ~8 MB.
 *
 * The pack is nextlib-media3ext's native libraries for this phone's CPU, published unchanged as assets of the QuickTube
 * prerelease [FfmpegPackManifest.TAG]. Each file's size and SHA-256 are compiled into the app (generated from the same
 * AAR), so a file that doesn't match is deleted and never loaded. Once loaded, PlaybackService's FFmpeg video renderer
 * plays what the phone's decoders can't; the phone's decoders stay first for everything else.
 */
@OptIn(UnstableApi::class)
class VideoDecoderPack(private val context: Context, private val http: OkHttpClient) {
    sealed interface Status {
        /** No pack for this phone's CPU type. */
        data object Unavailable : Status
        data object Missing : Status
        /** [progress] is 0..1. */
        data class Downloading(val progress: Float) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val files = packFiles()
    /** Download size in bytes, 0 when [Status.Unavailable]. */
    val sizeBytes: Long = files?.sumOf { it.size } ?: 0

    private val _status = MutableStateFlow(
        when {
            files == null -> Status.Unavailable
            loadIfInstalled(context) -> Status.Ready
            else -> Status.Missing
        },
    )
    val status: StateFlow<Status> = _status.asStateFlow()
    private val installing = Mutex()

    /** Whether the pack can decode video of this [mimeType] (FFmpeg's video decoders in the pack). */
    fun decodes(mimeType: String?) = mimeType in DECODED

    /** Downloads, verifies and loads the pack; true once it's ready. Safe to call again after a failure. */
    suspend fun install(): Boolean = installing.withLock {
        val list = files ?: return false
        if (_status.value == Status.Ready) return true
        _status.value = Status.Downloading(0f)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = packDir(context)
                // Older packs (another nextlib version) are no longer needed.
                dir.parentFile?.parentFile?.listFiles()?.filter { it.name != FfmpegPackManifest.TAG }?.forEach { it.deleteRecursively() }
                dir.mkdirs()
                var done = 0L
                for (f in list) {
                    val target = File(dir, f.name)
                    if (target.length() == f.size) { done += f.size; continue }
                    download(f, target) { read -> _status.value = Status.Downloading(((done + read).toFloat() / sizeBytes).coerceIn(0f, 1f)) }
                    done += f.size
                }
                load(dir, list)
            }.onFailure { Log.w(TAG, "Video decoder pack install failed", it) }.getOrDefault(false)
        }
        _status.value = if (ok) Status.Ready else Status.Failed("Couldn't download the video decoder. Check your connection and try again.")
        ok
    }

    private fun download(f: PackFile, target: File, onProgress: (Long) -> Unit) {
        val url = "${FfmpegPackManifest.BASE_URL}/${FfmpegPackManifest.TAG}/${abi()}.${f.name}"
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for ${f.name}" }
            var read = 0L
            response.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        check(read <= f.size) { "${f.name} is larger than expected" }
                        onProgress(read)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (read != f.size || sha != f.sha256) {
                part.delete()
                error("${f.name} doesn't match the expected checksum")
            }
        }
        check(part.renameTo(target)) { "Couldn't save ${f.name}" }
    }

    companion object {
        private const val TAG = "VideoDecoderPack"
        /** Dependencies first: each library's DT_NEEDED ones must already be loaded, as they aren't on the linker path. */
        private val LOAD_ORDER = listOf("libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so", "libmedia3ext.so")
        private val DECODED = setOf(
            MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_MPEG, MimeTypes.VIDEO_MPEG2,
            MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9,
        )
        private val loaded = AtomicBoolean(false)

        /** The CPU type this process runs as (a 32-bit install on a 64-bit phone needs the 32-bit libraries). */
        private fun abi(): String? {
            val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            return abis.firstOrNull { it in FfmpegPackManifest.files }
        }

        private fun packFiles(): List<PackFile>? = abi()?.let { FfmpegPackManifest.files[it] }

        private fun packDir(context: Context) = File(context.filesDir, "ffmpeg/${FfmpegPackManifest.TAG}/${abi()}")

        /**
         * Loads an already downloaded pack, so the player can use it from its first video. Called when the player service
         * starts; cheap when there's no pack. Files are only ever written after their checksum matched.
         */
        fun loadIfInstalled(context: Context): Boolean {
            if (loaded.get()) return true
            val list = packFiles() ?: return false
            val dir = packDir(context)
            if (list.any { File(dir, it.name).length() != it.size }) return false
            return runCatching { load(dir, list) }.onFailure { Log.w(TAG, "Video decoder pack load failed", it) }.getOrDefault(false)
        }

        @Synchronized
        private fun load(dir: File, list: List<PackFile>): Boolean {
            if (loaded.get()) return true
            val names = list.map { it.name }.toSet()
            LOAD_ORDER.filter { it in names }.forEach { name ->
                // Android is phasing out loading native code from writable files; these were verified, so lock them.
                val file = File(dir, name).apply { setWritable(false, false) }
                System.load(file.absolutePath)
            }
            markLoaded()
            // getVersion calls into the library, so this also proves its JNI functions are bound.
            return (FfmpegLibrary.isAvailable() && FfmpegLibrary.getVersion() != null).also { loaded.set(it) }
        }

        /**
         * nextlib's FfmpegLibrary loads its libraries with System.loadLibrary, which only looks inside the APK. They're
         * loaded from [packDir] above instead, so tell its LibraryLoader that loading already happened and worked.
         */
        private fun markLoaded() {
            val loader = FfmpegLibrary::class.java.getDeclaredField("LOADER").apply { isAccessible = true }.get(null) as LibraryLoader
            synchronized(loader) {
                for (name in listOf("loadAttempted", "isAvailable")) {
                    LibraryLoader::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(loader, true)
                }
            }
        }
    }
}
