package com.secondbrain.app

import android.app.Application
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.ai.SpeechEngine

/** Owns the process-wide native runtimes so UI and workers share one model instance. */
class SecondBrainApplication : Application() {
    val llmEngine: LlmEngine by lazy { LlmEngine(this) }
    val speechEngine: SpeechEngine by lazy { SpeechEngine(this) }
}
