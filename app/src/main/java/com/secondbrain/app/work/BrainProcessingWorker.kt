package com.secondbrain.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.secondbrain.app.SecondBrainApplication
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.ProcessingJob
import com.secondbrain.app.data.ProcessingJobStatus
import com.secondbrain.app.data.ProcessingJobType
import com.secondbrain.app.data.TranscriptionStatus
import com.secondbrain.app.domain.IngestionPipeline
import com.secondbrain.app.domain.NoteTitleGenerator
import kotlinx.coroutines.CancellationException
import java.io.File

class BrainProcessingWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val store = BrainStore(applicationContext)
        val embedder = EmbedderEngine(applicationContext)
        val app = applicationContext as SecondBrainApplication
        val llm = app.llmEngine
        val speech = app.speechEngine
        val pipeline = IngestionPipeline(store, embedder, llm)

        return try {
            store.open().getOrThrow()
            val recovered = store.recoverInterruptedProcessingJobs().getOrThrow()
            if (recovered > 0) Log.i(TAG, "Recovered $recovered interrupted processing jobs")

            while (!isStopped) {
                val queued = store.getNextQueuedProcessingJob().getOrThrow() ?: break
                val running = queued.copy(
                    status = ProcessingJobStatus.RUNNING,
                    progress = 1,
                    message = "Starting",
                    error = "",
                    attempt = queued.attempt + 1,
                    updatedTimestamp = now()
                )
                store.putProcessingJob(running).getOrThrow()

                try {
                    when (running.type) {
                        ProcessingJobType.TRANSCRIBE -> processTranscription(store, speech, running)
                        ProcessingJobType.ORGANIZE -> processOrganization(store, pipeline, llm, running)
                    }
                    if (!isCancelled(store, running.id)) {
                        updateJob(
                            store,
                            running,
                            ProcessingJobStatus.COMPLETED,
                            100,
                            "Completed"
                        )
                    }
                } catch (cancelled: JobCancelledException) {
                    Log.i(TAG, "Job ${running.id} was cancelled")
                } catch (superseded: JobSupersededException) {
                    Log.i(TAG, "Job ${running.id} was superseded by newer note content")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "Job ${running.id} failed", error)
                    if (!isCancelled(store, running.id)) {
                        if (running.type == ProcessingJobType.TRANSCRIBE) {
                            store.getNote(running.noteId).getOrNull()?.let { note ->
                                store.putNote(note.copy(transcriptionStatus = TranscriptionStatus.FAILED))
                            }
                        }
                        updateJob(
                            store,
                            running,
                            ProcessingJobStatus.FAILED,
                            100,
                            "Needs attention",
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Queue drain failed", error)
            Result.retry()
        } finally {
            embedder.close()
            store.close()
        }
    }

    private suspend fun processTranscription(
        store: BrainStore,
        speech: com.secondbrain.app.ai.SpeechEngine,
        job: ProcessingJob
    ) {
        var note = store.getNote(job.noteId).getOrThrow()
            ?: error("The voice note no longer exists")
        val path = note.audioPath ?: error("The voice note has no recording")
        val recording = File(path)
        check(recording.isFile) { "The original recording is missing" }
        ensureNotCancelled(store, job.id)

        if (!speech.isModelReady()) {
            note = note.copy(transcriptionStatus = TranscriptionStatus.DOWNLOADING)
            store.putNote(note).getOrThrow()
            updateJob(store, job, ProcessingJobStatus.RUNNING, 8, "Downloading speech model")
            speech.downloadModel { progress ->
                if (progress % 5 == 0 || progress == 100) {
                    updateJob(
                        store,
                        job,
                        ProcessingJobStatus.RUNNING,
                        progress.coerceIn(8, 40),
                        "Downloading speech model · $progress%"
                    )
                }
            }.getOrThrow()
        }

        ensureNotCancelled(store, job.id)
        updateJob(store, job, ProcessingJobStatus.RUNNING, 45, "Loading speech model")
        val device = speech.load().getOrThrow()
        note = note.copy(transcriptionStatus = TranscriptionStatus.TRANSCRIBING)
        store.putNote(note).getOrThrow()
        updateJob(store, job, ProcessingJobStatus.RUNNING, 58, "Transcribing on $device")

        val transcript = speech.transcribe(recording).getOrThrow()
        check(transcript.text.isNotBlank()) { "No speech was detected in this recording" }
        ensureNotCancelled(store, job.id)

        val completed = note.copy(
            title = note.title.ifBlank { NoteTitleGenerator.fromContent(transcript.text) },
            content = transcript.text,
            audioDurationMs = transcript.audioDurationMs.takeIf { it > 0L } ?: note.audioDurationMs,
            transcriptionStatus = TranscriptionStatus.COMPLETE
        )
        store.putNote(completed).getOrThrow()
        updateJob(store, job, ProcessingJobStatus.RUNNING, 92, "Transcript saved")
        store.enqueueProcessingJob(completed.id, ProcessingJobType.ORGANIZE).getOrThrow()
    }

    private suspend fun processOrganization(
        store: BrainStore,
        pipeline: IngestionPipeline,
        llm: com.secondbrain.app.ai.LlmEngine,
        job: ProcessingJob
    ) {
        val note: NoteDocument = store.getNote(job.noteId).getOrThrow()
            ?: error("The note no longer exists")
        check(note.content.isNotBlank()) { "The note has no text to organize" }
        ensureNotCancelled(store, job.id)

        updateJob(store, job, ProcessingJobStatus.RUNNING, 18, "Preparing on-device models")
        if (llm.isModelReady()) {
            llm.loadModel(useGpu = true).getOrThrow()
        }
        ensureNotCancelled(store, job.id)
        updateJob(store, job, ProcessingJobStatus.RUNNING, 42, "Extracting ideas, actions, and relationships")
        pipeline.enrich(note).getOrThrow()
        ensureNotCancelled(store, job.id)
        updateJob(store, job, ProcessingJobStatus.RUNNING, 92, "Knowledge and actions updated")
    }

    private suspend fun updateJob(
        store: BrainStore,
        base: ProcessingJob,
        status: ProcessingJobStatus,
        progress: Int,
        message: String,
        error: String = ""
    ) {
        val current = store.getProcessingJob(base.id).getOrThrow()
            ?: throw JobCancelledException()
        if (current.status == ProcessingJobStatus.CANCELLED) throw JobCancelledException()
        if (
            current.status == ProcessingJobStatus.QUEUED &&
            current.updatedTimestamp > base.updatedTimestamp
        ) {
            throw JobSupersededException()
        }
        store.putProcessingJob(
            current.copy(
                status = status,
                progress = progress,
                message = message,
                error = error,
                updatedTimestamp = now()
            )
        ).getOrThrow()
        setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STAGE to message))
    }

    private suspend fun ensureNotCancelled(store: BrainStore, jobId: String) {
        if (isStopped || isCancelled(store, jobId)) throw JobCancelledException()
    }

    private suspend fun isCancelled(store: BrainStore, jobId: String): Boolean =
        store.getProcessingJob(jobId).getOrNull()?.status == ProcessingJobStatus.CANCELLED

    private fun now(): Double = System.currentTimeMillis() / 1000.0

    private class JobCancelledException : Exception()
    private class JobSupersededException : Exception()

    companion object {
        private const val TAG = "BrainProcessing"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_STAGE = "stage"
    }
}
