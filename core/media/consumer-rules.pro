
# VideoDecoderPack loads FFmpeg's downloaded video libraries itself and then marks nextlib's LibraryLoader as loaded
# through these fields, so their names must survive shrinking.
-keepclassmembers class androidx.media3.common.util.LibraryLoader {
    boolean loadAttempted;
    boolean isAvailable;
}
-keepclassmembers class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary {
    androidx.media3.common.util.LibraryLoader LOADER;
}
