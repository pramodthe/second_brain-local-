package com.secondbrain.app.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nexa.sdk.AsrWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.AsrConfig
import com.nexa.sdk.bean.AsrCreateInput
import com.nexa.sdk.bean.AsrTranscribeInput
import com.nexa.sdk.bean.DeviceIdValue
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.PluginIdValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class SpeechTranscript(
    val text: String,
    val audioDurationMs: Long,
    val elapsedMs: Long,
    val realTimeFactor: Double,
    val device: String
)

/** Offline speech recognition through the Nexa whisper.cpp plugin. */
class SpeechEngine(private val context: Context) {

    private val mutex = Mutex()
    private var asr: AsrWrapper? = null
    private var sdkInitialized = false
    private var loadedDevice: DeviceIdValue? = null

    val modelDir: File
        get() = File(context.filesDir, "models/whisper").apply { mkdirs() }

    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    fun isModelReady(): Boolean = modelFile.isFile && modelFile.length() == MODEL_BYTES

    suspend fun downloadModel(onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isModelReady()) return@runCatching Unit
            val target = modelFile
            val part = File(target.parentFile, "${target.name}.part")
            val have = if (part.isFile) part.length() else 0L
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (have > 0L) setRequestProperty("Range", "bytes=$have-")
            }
            val responseCode = connection.responseCode
            check(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Speech model download failed (HTTP $responseCode)"
            }
            val append = have > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            val startingBytes = if (append) have else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = startingBytes
                    var previousProgress = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val progress = ((received * 100L) / MODEL_BYTES).toInt().coerceIn(0, 100)
                        if (progress != previousProgress) {
                            previousProgress = progress
                            onProgress(progress)
                        }
                    }
                }
            }
            connection.disconnect()
            check(part.length() == MODEL_BYTES) {
                "Speech model has an unexpected size (${part.length()} of $MODEL_BYTES bytes)"
            }
            if (target.exists()) check(target.delete()) { "Could not replace the speech model" }
            check(part.renameTo(target)) { "Could not finish installing the speech model" }
            onProgress(100)
        }
    }

    suspend fun load(): Result<String> = mutex.withLock { loadLocked() }

    suspend fun transcribe(wav: File, language: String = "en"): Result<SpeechTranscript> =
        mutex.withLock {
            runCatching {
                if (asr == null) loadLocked().getOrThrow()
                check(wav.isFile) { "Recording file is missing" }
                val engine = asr ?: error("Speech model is not loaded")
                val started = SystemClock.elapsedRealtime()
                val output = engine.transcribe(
                    AsrTranscribeInput(wav.absolutePath, language, AsrConfig())
                ).getOrThrow()
                val elapsedMs = SystemClock.elapsedRealtime() - started
                val audioDurationMs = output.profileData.audioDurationMs
                    .takeIf { it > 0L }
                    ?: wavPcmDurationMs(wav)
                SpeechTranscript(
                    text = output.result.transcript.orEmpty().trim(),
                    audioDurationMs = audioDurationMs,
                    elapsedMs = elapsedMs,
                    realTimeFactor = output.profileData.realTimeFactor
                        .takeIf { it > 0.0 }
                        ?: if (audioDurationMs > 0L) elapsedMs.toDouble() / audioDurationMs else 0.0,
                    device = loadedDevice?.name ?: "UNKNOWN"
                )
            }
        }

    private suspend fun loadLocked(): Result<String> = runCatching {
        loadedDevice?.let { return@runCatching it.name }
        check(isModelReady()) { "Speech model is not installed" }
        check(ensureSdkInit()) { "Failed to initialize Nexa SDK for speech recognition" }

        val failures = mutableListOf<String>()
        for (device in listOf(DeviceIdValue.NPU, DeviceIdValue.GPU, DeviceIdValue.CPU)) {
            val attempt = runCatching {
                val input = AsrCreateInput(
                    MODEL_NAME,
                    modelFile.absolutePath,
                    null,
                    ModelConfig(),
                    "en",
                    PluginIdValue.WHISPER_CPP.value,
                    device.value,
                    null,
                    null
                )
                AsrWrapper.builder().asrCreateInput(input).build().getOrThrow()
            }
            if (attempt.isSuccess) {
                asr = attempt.getOrThrow()
                loadedDevice = device
                Log.i(TAG, "$MODEL_NAME loaded on ${device.name}")
                return@runCatching device.name
            }
            failures += "${device.name}: ${attempt.exceptionOrNull()?.message ?: "unavailable"}"
        }
        error("Could not load speech recognition (${failures.joinToString()})")
    }

    private suspend fun ensureSdkInit(): Boolean {
        if (sdkInitialized) return true
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                NexaSdk.getInstance().init(
                    context,
                    object : NexaSdk.InitCallback {
                        override fun onSuccess() {
                            sdkInitialized = true
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onFailure(msg: String) {
                            Log.e(TAG, "Nexa initialization failed: $msg")
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                )
            }.onFailure {
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    fun release() {
        runCatching { asr?.close() }
        asr = null
        loadedDevice = null
    }

    private fun wavPcmDurationMs(wav: File): Long {
        val pcmBytes = (wav.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
        return pcmBytes * 1_000L / PCM_BYTES_PER_SECOND
    }

    companion object {
        private const val TAG = "SpeechEngine"
        private const val WAV_HEADER_BYTES = 44L
        private const val PCM_BYTES_PER_SECOND = 32_000L
        const val MODEL_NAME = "whisper-tiny"
        const val MODEL_FILENAME = "whisper-tiny.bin"
        const val MODEL_BYTES = 77_691_730L
        const val MODEL_URL =
            "https://huggingface.co/unslothai/whisper-tiny-GGUF/resolve/main/whisper-tiny.bin"
    }
}
