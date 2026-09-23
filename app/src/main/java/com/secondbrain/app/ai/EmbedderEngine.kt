package com.secondbrain.app.ai

import android.content.Context
import android.util.Log
import com.nexa.sdk.EmbedderWrapper
import com.nexa.sdk.bean.EmbeddingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * On-Device Embedder Engine.
 * Supports Nexa AI native embedding models (e.g. embeddinggemma-300m)
 * and includes a deterministic fast local vectorizer fallback for zero-downtime offline use.
 */
class EmbedderEngine(private val context: Context) {

    private var embedder: EmbedderWrapper? = null
    var activeModelName: String = "FastLocal-256"
        private set

    fun isReady(): Boolean = true

    /**
     * Initializes Nexa embedder from a local model directory if present.
     */
    suspend fun loadNexaModel(modelDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!modelDir.exists()) error("Model dir does not exist: ${modelDir.absolutePath}")
            val input = com.nexa.sdk.bean.EmbedderCreateInput(
                model_name = modelDir.name,
                model_path = modelDir.absolutePath,
                tokenizer_path = null,
                config = com.nexa.sdk.bean.ModelConfig(),
                plugin_id = com.nexa.sdk.bean.PluginIdValue.CPU_GPU.value,
                device_id = com.nexa.sdk.bean.DeviceIdValue.CPU.value
            )
            val instance = EmbedderWrapper.builder()
                .embedderCreateInput(input)
                .build()
                .getOrThrow()
            embedder?.close()
            embedder = instance
            activeModelName = modelDir.name
            Log.i(TAG, "Nexa Embedder loaded from ${modelDir.name}")
            Unit
        }
    }

    /**
     * Generates a 256-dimensional normalized vector for given text.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val nexa = embedder
        if (nexa != null) {
            val res = runCatching {
                nexa.embed(arrayOf(text), EmbeddingConfig())
            }.getOrNull()

            val raw = res?.getOrNull()?.embeddings
            if (raw != null && raw.isNotEmpty()) {
                return@withContext normalizeAndProject(raw, DIM)
            }
        }

        // Fast on-device deterministic semantic projection (zero-dependency fallback)
        fallbackEmbed(text, DIM)
    }

    private fun normalizeAndProject(input: FloatArray, targetDim: Int): FloatArray {
        val out = FloatArray(targetDim)
        for (i in 0 until minOf(input.size, targetDim)) {
            out[i] = input[i]
        }
        var sumSq = 0f
        for (v in out) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-9f)
        for (i in out.indices) out[i] /= norm
        return out
    }

    /**
     * Deterministic character & word trigram feature hashing with L2 normalization.
     * Produces semantic proximity for overlapping concepts even without external model weights.
     */
    private fun fallbackEmbed(text: String, dim: Int): FloatArray {
        val vec = FloatArray(dim)
        val clean = text.lowercase().trim()
        if (clean.isEmpty()) return vec

        // Word tokens
        val words = clean.split(Regex("\\W+")).filter { it.length > 1 }
        for (w in words) {
            val h = (w.hashCode() and 0x7fffffff) % dim
            vec[h] += 1.5f
            // Character trigrams
            if (w.length >= 3) {
                for (i in 0..w.length - 3) {
                    val tri = w.substring(i, i + 3)
                    val th = (tri.hashCode() and 0x7fffffff) % dim
                    vec[th] += 0.8f
                }
            }
        }

        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-9f)
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    fun close() {
        runCatching { embedder?.close() }
        embedder = null
    }

    companion object {
        private const val TAG = "EmbedderEngine"
        const val DIM = 256
    }
}
