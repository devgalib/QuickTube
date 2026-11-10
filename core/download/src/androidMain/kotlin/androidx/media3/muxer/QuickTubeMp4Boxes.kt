package androidx.media3.muxer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Bridge to Media3's package-private MP4 box builders, so QuickTube's streaming MP4 writer can reuse its codec
 * sample entries (avcC, hvcC, av1C, vpcC, esds, dOps…) and Annex-B conversion instead of re-implementing them.
 */
@OptIn(UnstableApi::class)
object QuickTubeMp4Boxes {
    fun ftyp(): ByteArray = Boxes.ftyp().bytes()

    fun hdlr(isVideo: Boolean): ByteArray =
        (if (isVideo) Boxes.hdlr("vide", "VideoHandle") else Boxes.hdlr("soun", "SoundHandle")).bytes()

    fun mediaHeader(isVideo: Boolean): ByteArray = (if (isVideo) Boxes.vmhd() else Boxes.smhd()).bytes()

    fun dinf(): ByteArray = Boxes.dinf(Boxes.dref(Boxes.localUrl())).bytes()

    fun stsd(format: Format, isVideo: Boolean): ByteArray =
        Boxes.stsd(if (isVideo) Boxes.videoSampleEntry(format) else Boxes.audioSampleEntry(format)).bytes()

    /** H.264/H.265 samples from MPEG-TS use start codes; MP4 needs length prefixes. Other samples pass through. */
    fun toAvcc(format: Format, sample: ByteArray): ByteArray {
        if (!AnnexBUtils.doesSampleContainAnnexBNalUnits(format)) return sample
        return AnnexBToAvccConverter.DEFAULT.process(ByteBuffer.wrap(sample)).bytes()
    }

    private fun ByteBuffer.bytes(): ByteArray {
        val b = duplicate()
        if (b.hasArray() && b.arrayOffset() == 0 && b.position() == 0 && b.remaining() == b.array().size) return b.array()
        return ByteArray(b.remaining()).also { b.get(it) }
    }
}
