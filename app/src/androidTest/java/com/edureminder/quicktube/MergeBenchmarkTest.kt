package com.edureminder.quicktube

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edureminder.quicktube.core.download.MediaMerger
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Manual timing check: where does remuxing a large file spend its time? Needs a file path argument. */
@RunWith(AndroidJUnit4::class)
class MergeBenchmarkTest {
    @Test
    fun remuxTimings() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("mergeInput")
        assumeTrue(path != null)
        val src = File(path!!)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        fun t(label: String, block: () -> Unit) {
            val s = System.nanoTime(); block(); Log.i("MergeBench", "$label: ${(System.nanoTime() - s) / 1_000_000} ms")
        }
        val pubOut = File(src.parentFile, "bench-out.mp4")
        val local = File(ctx.cacheDir, "bench-in.mp4")
        t("copy public -> cache") { src.copyTo(local, overwrite = true) }
        val localOut = File(ctx.cacheDir, "bench-out.mp4")
        t("cache -> cache") { MediaMerger.remux(local, localOut) }
        t("copy cache -> public") { localOut.copyTo(pubOut, overwrite = true) }
        local.delete(); localOut.delete(); pubOut.delete()
    }
}
