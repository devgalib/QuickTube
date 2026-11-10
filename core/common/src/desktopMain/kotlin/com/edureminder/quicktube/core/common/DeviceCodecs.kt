package com.edureminder.quicktube.core.common

/** The desktop player (libVLC) decodes AV1 in software on every supported OS. */
actual object DeviceCodecs {
    actual val canDecodeAv1: Boolean = true
}
