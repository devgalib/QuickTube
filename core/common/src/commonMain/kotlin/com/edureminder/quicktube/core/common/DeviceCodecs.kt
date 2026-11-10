package com.edureminder.quicktube.core.common

/** What this device can play, so downloads never offer a format the user couldn't watch. */
expect object DeviceCodecs {
    /** AV1 (Facebook's and YouTube's HD ladders). */
    val canDecodeAv1: Boolean
}
