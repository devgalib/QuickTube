package com.edureminder.quicktube.core.download

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Vp9ConfigTest {
    private fun vp9(width: Int, height: Int, fps: Float = 30f, codecs: String? = null, color: ColorInfo? = null) =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(width).setHeight(height).setFrameRate(fps)
            .setCodecs(codecs).setColorInfo(color).build()

    @Test
    fun youtube4kWithoutCodecPrivateGetsAnSdrConfig() {
        val f = Vp9Config.ensure(vp9(3840, 2160, 30f))
        // version 1/flags 0, profile 0, level 5.0 (4K30), 8-bit 4:2:0 limited range, BT.709 primaries/transfer/matrix, no init data.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 50, 0x82.toByte(), 1, 1, 1, 0, 0), f.initializationData.single())
    }

    @Test
    fun hdrIsProfileTwoTenBit() {
        val hdr = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED).build()
        val c = Vp9Config.vpcC(vp9(3840, 2160, 60f, color = hdr))
        assertEquals(2, c[4].toInt())
        assertEquals(51, c[5].toInt()) // 4K60 is level 5.1
        assertEquals(0xA2, c[6].toInt() and 0xFF)
        assertEquals(9, c[7].toInt()); assertEquals(16, c[8].toInt()); assertEquals(9, c[9].toInt())
    }

    @Test
    fun codecStringWinsAndOtherFormatsAreUntouched() {
        val c = Vp9Config.vpcC(vp9(1920, 1080, codecs = "vp09.00.40.08"))
        assertEquals(0, c[4].toInt()); assertEquals(40, c[5].toInt()); assertEquals(0x82, c[6].toInt() and 0xFF)
        val avc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        assertSame(avc, Vp9Config.ensure(avc))
        val withCsd = vp9(1280, 720).buildUpon().setInitializationData(listOf(byteArrayOf(9))).build()
        assertSame(withCsd, Vp9Config.ensure(withCsd))
    }
}
