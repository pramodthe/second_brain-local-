package com.nexa.sdk.bean

/**
 * Drop-in replacement for ai.nexa:core's TtsConfig to prevent NoSuchFieldError
 * when libnpu_jni inspects boxed Float/Integer.
 */
data class TtsConfig(
    val voice: String? = null,
    val speed: Float? = 1.0f,
    val seed: Int? = 0,
    val sampleRate: Int? = 24_000
)
