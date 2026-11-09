package com.edureminder.quicktube.core.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * VP9 in MP4 needs a `vpcC` box (profile, level, bit depth, colour). YouTube's VP9 streams (all 1440p/4K/8K and
 * HDR) arrive as WebM without the CodecPrivate it is made from, and Media3's MP4 writer then refuses them
 * ("csd-0 is not found in the format for vpcC box"). Build it from what the stream does say.
 */
@OptIn(UnstableApi::class)
internal object Vp9Config {
    fun ensure(format: Format): Format {
        if (format.sampleMimeType != MimeTypes.VIDEO_VP9 || format.initializationData.isNotEmpty()) return format
        return format.buildUpon().setInitializationData(listOf(vpcC(format))).build()
    }

    /** The vpcC payload (version 1, flags 0, then the VP9 codec configuration record). */
    internal fun vpcC(format: Format): ByteArray {
        // "vp09.PP.LL.DD…" when the source says it (DASH manifests do); otherwise from colour info and size.
        val parts = format.codecs?.takeIf { it.startsWith("vp09.") }?.split('.')
        val color = format.colorInfo
        val hdr = color?.colorTransfer == C.COLOR_TRANSFER_ST2084 || color?.colorTransfer == C.COLOR_TRANSFER_HLG
        val bitDepth = parts?.getOrNull(3)?.toIntOrNull() ?: color?.lumaBitdepth?.takeIf { it > 0 } ?: if (hdr) 10 else 8
        val profile = parts?.getOrNull(1)?.toIntOrNull() ?: if (bitDepth > 8) 2 else 0
        val level = parts?.getOrNull(2)?.toIntOrNull() ?: levelFor(format.width, format.height, format.frameRate)
        val fullRange = color?.colorRange == C.COLOR_RANGE_FULL
        // ISO/IEC 23001-8 codes; BT.709 unless the stream says otherwise.
        val primaries = when (color?.colorSpace) { C.COLOR_SPACE_BT2020 -> 9; C.COLOR_SPACE_BT601 -> 6; else -> 1 }
        val transfer = when (color?.colorTransfer) { C.COLOR_TRANSFER_ST2084 -> 16; C.COLOR_TRANSFER_HLG -> 18; C.COLOR_TRANSFER_SDR -> 1; else -> 1 }
        val matrix = when (color?.colorSpace) { C.COLOR_SPACE_BT2020 -> 9; C.COLOR_SPACE_BT601 -> 6; else -> 1 }
        // Chroma 1 = 4:2:0 co-located with luma (what VP9 encoders, YouTube's included, produce).
        val chroma = 1
        return byteArrayOf(
            1, 0, 0, 0,
            profile.toByte(), level.toByte(),
            ((bitDepth shl 4) or (chroma shl 1) or (if (fullRange) 1 else 0)).toByte(),
            primaries.toByte(), transfer.toByte(), matrix.toByte(),
            0, 0, // no codec initialization data
        )
    }

    /** VP9 level (×10) for a picture size and frame rate, per the VP9 level table; players use it only as a hint. */
    internal fun levelFor(width: Int, height: Int, fps: Float): Int {
        val samples = width.toLong().coerceAtLeast(1) * height.toLong().coerceAtLeast(1)
        val rate = samples * (if (fps > 0) fps else 30f)
        return when {
            samples <= 36_864 -> 10
            samples <= 122_880 -> 20
            samples <= 245_760 -> 21
            samples <= 552_960 -> 30
            samples <= 983_040 -> 31
            samples <= 2_228_224 -> if (rate <= 83_558_400) 40 else 41
            samples <= 8_912_896 -> if (rate <= 311_951_360) 50 else if (rate <= 588_251_136) 51 else 52
            else -> if (rate <= 1_176_502_272) 60 else if (rate <= 4_706_009_088) 61 else 62
        }
    }
}
