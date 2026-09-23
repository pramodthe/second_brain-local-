package com.secondbrain.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.max

data class VoiceRecording(
    val file: File,
    val durationMs: Long
)

/** Records durable 16 kHz mono PCM WAV files suitable for Whisper. */
class VoiceRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var output: RandomAccessFile? = null
    private var outputFile: File? = null
    private var writerJob: Job? = null
    @Volatile private var recording = false

    val isRecording: Boolean get() = recording

    fun start(scope: CoroutineScope): Result<Unit> = runCatching {
        check(!recording) { "A recording is already in progress" }
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required" }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        check(minBuffer > 0) { "This device could not provide an audio recording buffer" }
        val bufferSize = max(minBuffer * 2, SAMPLE_RATE * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Could not initialize the microphone"
        }

        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "voice-${UUID.randomUUID()}.wav")
        val wav = RandomAccessFile(file, "rw").apply {
            setLength(0)
            writeWavHeader(this, 0L)
        }

        audioRecord = recorder
        output = wav
        outputFile = file
        recording = true
        recorder.startRecording()

        writerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive && recording) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) wav.write(buffer, 0, count)
            }
        }
    }.onFailure { abort(deleteFile = true) }

    suspend fun stop(): Result<VoiceRecording> = withContext(Dispatchers.IO) {
        runCatching {
            check(recording) { "No recording is in progress" }
            recording = false
            runCatching { audioRecord?.stop() }
            writerJob?.join()
            writerJob = null
            audioRecord?.release()
            audioRecord = null

            val wav = requireNotNull(output)
            val file = requireNotNull(outputFile)
            val audioBytes = (wav.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
            writeWavHeader(wav, audioBytes)
            wav.close()
            output = null
            outputFile = null

            val durationMs = audioBytes * 1_000L / BYTES_PER_SECOND.toLong()
            check(audioBytes > 0L) { "No audio was captured" }
            VoiceRecording(file, durationMs)
        }.onFailure { abort(deleteFile = true) }
    }

    suspend fun cancel() {
        if (recording) stop().getOrNull()?.file?.delete()
    }

    fun abort(deleteFile: Boolean = false) {
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        writerJob?.cancel()
        writerJob = null
        runCatching { output?.close() }
        output = null
        if (deleteFile) runCatching { outputFile?.delete() }
        outputFile = null
    }

    private fun writeWavHeader(file: RandomAccessFile, audioBytes: Long) {
        file.seek(0)
        file.writeBytes("RIFF")
        writeLittleEndianInt(file, (audioBytes + 36L).toInt())
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        writeLittleEndianInt(file, 16)
        writeLittleEndianShort(file, 1)
        writeLittleEndianShort(file, 1)
        writeLittleEndianInt(file, SAMPLE_RATE)
        writeLittleEndianInt(file, BYTES_PER_SECOND)
        writeLittleEndianShort(file, 2)
        writeLittleEndianShort(file, 16)
        file.writeBytes("data")
        writeLittleEndianInt(file, audioBytes.toInt())
    }

    private fun writeLittleEndianInt(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write(value shr 8 and 0xff)
        file.write(value shr 16 and 0xff)
        file.write(value shr 24 and 0xff)
    }

    private fun writeLittleEndianShort(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write(value shr 8 and 0xff)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val WAV_HEADER_BYTES = 44L
    }
}
