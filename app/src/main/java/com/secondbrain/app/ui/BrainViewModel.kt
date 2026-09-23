package com.secondbrain.app.ui

import android.app.Application
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.ai.SpeechEngine
import com.secondbrain.app.ai.VoiceRecorder
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.SubgraphContext
import com.secondbrain.app.data.TranscriptionStatus
import com.secondbrain.app.domain.HybridRetriever
import com.secondbrain.app.domain.IngestionPipeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatMessageItem(
    val sender: String, // "user" or "brain"
    val text: String,
    val context: SubgraphContext? = null,
    val isStreaming: Boolean = false
)

class BrainViewModel(application: Application) : AndroidViewModel(application) {

    private val noteProcessingMutex = Mutex()
    private val speechProcessingMutex = Mutex()
    private val voiceRecorder = VoiceRecorder(application)
    private var recordingTickerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    val store = BrainStore(application)
    val embedder = EmbedderEngine(application)
    val llm = LlmEngine(application)
    val speech = SpeechEngine(application)

    val pipeline = IngestionPipeline(store, embedder, llm)
    val retriever = HybridRetriever(store, embedder)

    private val _notes = MutableStateFlow<List<NoteDocument>>(emptyList())
    val notes: StateFlow<List<NoteDocument>> = _notes.asStateFlow()

    private val _entities = MutableStateFlow<List<EntityNode>>(emptyList())
    val entities: StateFlow<List<EntityNode>> = _entities.asStateFlow()

    private val _edges = MutableStateFlow<List<RelationEdge>>(emptyList())
    val edges: StateFlow<List<RelationEdge>> = _edges.asStateFlow()

    private val _stats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val stats: StateFlow<Map<String, Int>> = _stats.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessageItem>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageItem>> = _chatMessages.asStateFlow()

    private val _isIngesting = MutableStateFlow(false)
    val isIngesting: StateFlow<Boolean> = _isIngesting.asStateFlow()

    private val _processingNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val processingNoteIds: StateFlow<Set<String>> = _processingNoteIds.asStateFlow()

    private val _speechProcessingNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val speechProcessingNoteIds: StateFlow<Set<String>> = _speechProcessingNoteIds.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _isVoiceCaptureBusy = MutableStateFlow(false)
    val isVoiceCaptureBusy: StateFlow<Boolean> = _isVoiceCaptureBusy.asStateFlow()

    private val _speechModelReady = MutableStateFlow(speech.isModelReady())
    val speechModelReady: StateFlow<Boolean> = _speechModelReady.asStateFlow()

    private val _speechDownloadProgress = MutableStateFlow(0)
    val speechDownloadProgress: StateFlow<Int> = _speechDownloadProgress.asStateFlow()

    private val _speechLoadedDevice = MutableStateFlow<String?>(null)
    val speechLoadedDevice: StateFlow<String?> = _speechLoadedDevice.asStateFlow()

    private val _playingNoteId = MutableStateFlow<String?>(null)
    val playingNoteId: StateFlow<String?> = _playingNoteId.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _appError = MutableStateFlow<String?>(null)
    val appError: StateFlow<String?> = _appError.asStateFlow()

    private val _modelReady = MutableStateFlow(llm.isModelReady())
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()

    private val _isModelBusy = MutableStateFlow(false)
    val isModelBusy: StateFlow<Boolean> = _isModelBusy.asStateFlow()

    private val _modelDownloadProgress = MutableStateFlow(0)
    val modelDownloadProgress: StateFlow<Int> = _modelDownloadProgress.asStateFlow()

    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    init {
        viewModelScope.launch {
            store.open()
                .onSuccess {
                    loadData()
                    resumePendingTranscriptions()
                }
                .onFailure { _appError.value = "Could not open the local knowledge database: ${it.message}" }

            if (_modelReady.value) {
                loadLocalModel()
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch { loadData() }
    }

    private suspend fun loadData() {
        _notes.value = store.getRecentNotes(100).getOrDefault(emptyList())
        _entities.value = store.getAllEntities().getOrDefault(emptyList())
        _edges.value = store.getAllEdges().getOrDefault(emptyList())
        _stats.value = store.getStats()
    }

    fun saveNote(title: String, content: String, onComplete: () -> Unit = {}) {
        saveNote(existing = null, title = title, content = content, onComplete = onComplete)
    }

    fun saveNote(
        existing: NoteDocument?,
        title: String,
        content: String,
        onComplete: () -> Unit = {}
    ) {
        if (content.isBlank()) return
        viewModelScope.launch {
            _isIngesting.value = true
            var savedNote: NoteDocument? = null
            try {
                savedNote = if (existing == null) {
                    pipeline.capture(title, content).getOrThrow()
                } else {
                    pipeline.update(existing, title, content).getOrThrow()
                }

                val note = requireNotNull(savedNote)
                _notes.value = if (existing == null) {
                    listOf(note) + _notes.value
                } else {
                    _notes.value.map { if (it.id == note.id) note else it }
                }
                refreshData()
                onComplete()
            } catch (error: Exception) {
                _appError.value = "Could not save the note: ${error.message ?: "unknown error"}"
            } finally {
                _isIngesting.value = false
            }

            savedNote?.let { processNote(it) }
        }
    }

    private suspend fun processNote(note: NoteDocument) {
        _processingNoteIds.value = _processingNoteIds.value + note.id
        try {
            noteProcessingMutex.withLock {
                pipeline.enrich(note)
                    .onSuccess { refreshData() }
                    .onFailure { error ->
                        _appError.value = "Note saved, but automatic organization failed: ${error.message ?: "unknown error"}"
                    }
            }
        } finally {
            _processingNoteIds.value = _processingNoteIds.value - note.id
        }
    }

    fun startVoiceRecording() {
        if (_isRecording.value || _isVoiceCaptureBusy.value) return
        voiceRecorder.start(viewModelScope)
            .onSuccess {
                _recordingElapsedMs.value = 0L
                _isRecording.value = true
                val startedAt = SystemClock.elapsedRealtime()
                recordingTickerJob?.cancel()
                recordingTickerJob = viewModelScope.launch {
                    while (_isRecording.value) {
                        _recordingElapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                        delay(200)
                    }
                }
            }
            .onFailure { error ->
                _appError.value = error.message ?: "Could not start recording"
            }
    }

    fun stopVoiceRecording(title: String, onSaved: () -> Unit = {}) {
        if (!_isRecording.value || _isVoiceCaptureBusy.value) return
        _isVoiceCaptureBusy.value = true
        viewModelScope.launch {
            var savedNote: NoteDocument? = null
            try {
                val recording = voiceRecorder.stop().getOrThrow()
                _isRecording.value = false
                recordingTickerJob?.cancel()
                savedNote = pipeline.capture(
                    title = title,
                    content = "",
                    source = "voice",
                    audioPath = recording.file.absolutePath,
                    audioDurationMs = recording.durationMs,
                    transcriptionStatus = TranscriptionStatus.PENDING
                ).getOrThrow()
                _notes.value = listOf(savedNote) + _notes.value
                loadData()
                onSaved()
            } catch (error: Exception) {
                _appError.value = "Could not save the recording: ${error.message ?: "unknown error"}"
            } finally {
                _isRecording.value = false
                _isVoiceCaptureBusy.value = false
                recordingTickerJob?.cancel()
            }
            savedNote?.let { processVoiceNote(it) }
        }
    }

    fun cancelVoiceRecording() {
        recordingTickerJob?.cancel()
        _isRecording.value = false
        viewModelScope.launch { voiceRecorder.cancel() }
    }

    fun retryTranscription(note: NoteDocument) {
        if (note.audioPath.isNullOrBlank() || note.id in _speechProcessingNoteIds.value) return
        viewModelScope.launch { processVoiceNote(note) }
    }

    private fun resumePendingTranscriptions() {
        _notes.value
            .filter {
                it.audioPath != null && it.transcriptionStatus in setOf(
                    TranscriptionStatus.PENDING,
                    TranscriptionStatus.DOWNLOADING,
                    TranscriptionStatus.TRANSCRIBING
                )
            }
            .forEach { note -> viewModelScope.launch { processVoiceNote(note) } }
    }

    private suspend fun processVoiceNote(original: NoteDocument) {
        if (original.id in _speechProcessingNoteIds.value) return
        _speechProcessingNoteIds.value = _speechProcessingNoteIds.value + original.id
        var workingNote = original
        var completedNote: NoteDocument? = null
        try {
            speechProcessingMutex.withLock {
                val audioFile = java.io.File(requireNotNull(workingNote.audioPath))
                check(audioFile.isFile) { "The original recording is missing" }

                if (!speech.isModelReady()) {
                    workingNote = updateVoiceStatus(workingNote, TranscriptionStatus.DOWNLOADING)
                    speech.downloadModel { progress -> _speechDownloadProgress.value = progress }
                        .getOrThrow()
                    _speechModelReady.value = true
                }

                _speechLoadedDevice.value = speech.load().getOrThrow()
                workingNote = updateVoiceStatus(workingNote, TranscriptionStatus.TRANSCRIBING)
                val transcript = speech.transcribe(audioFile).getOrThrow()
                check(transcript.text.isNotBlank()) { "No speech was detected in this recording" }

                val completed = workingNote.copy(
                    content = transcript.text,
                    audioDurationMs = transcript.audioDurationMs.takeIf { it > 0L }
                        ?: workingNote.audioDurationMs,
                    modifiedTimestamp = System.currentTimeMillis() / 1000.0,
                    transcriptionStatus = TranscriptionStatus.COMPLETE
                )
                store.putNote(completed).getOrThrow()
                replaceNote(completed)
                completedNote = completed
            }
        } catch (error: Exception) {
            val failed = workingNote.copy(
                transcriptionStatus = TranscriptionStatus.FAILED,
                modifiedTimestamp = System.currentTimeMillis() / 1000.0
            )
            store.putNote(failed)
            replaceNote(failed)
            _appError.value = "Voice note saved, but transcription failed: ${error.message ?: "unknown error"}"
        } finally {
            _speechProcessingNoteIds.value = _speechProcessingNoteIds.value - original.id
            refreshData()
        }

        completedNote?.let { processNote(it) }
    }

    private suspend fun updateVoiceStatus(
        note: NoteDocument,
        status: TranscriptionStatus
    ): NoteDocument {
        val updated = note.copy(transcriptionStatus = status)
        store.putNote(updated).getOrThrow()
        replaceNote(updated)
        return updated
    }

    private fun replaceNote(note: NoteDocument) {
        _notes.value = _notes.value.map { if (it.id == note.id) note else it }
    }

    fun togglePlayback(note: NoteDocument) {
        val path = note.audioPath ?: return
        if (_playingNoteId.value == note.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
                mediaPlayer = this
                _playingNoteId.value = note.id
            }
        }.onFailure { error ->
            stopPlayback()
            _appError.value = "Could not play the recording: ${error.message ?: "unknown error"}"
        }
    }

    private fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        _playingNoteId.value = null
    }

    fun setupLocalModel() {
        if (_isModelBusy.value || _modelLoaded.value) return
        viewModelScope.launch {
            _isModelBusy.value = true
            _modelError.value = null
            try {
                if (!llm.isModelReady()) {
                    llm.downloadModel { progress -> _modelDownloadProgress.value = progress }
                        .getOrThrow()
                    _modelReady.value = true
                }
                llm.loadModel(useGpu = true).getOrThrow()
                _modelLoaded.value = true
            } catch (error: Exception) {
                _modelReady.value = llm.isModelReady()
                _modelError.value = error.message ?: "Unknown model setup error"
            } finally {
                _isModelBusy.value = false
            }
        }
    }

    private suspend fun loadLocalModel() {
        _isModelBusy.value = true
        _modelError.value = null
        llm.loadModel(useGpu = true)
            .onSuccess { _modelLoaded.value = true }
            .onFailure { _modelError.value = it.message ?: "Could not load the local model" }
        _isModelBusy.value = false
    }

    fun clearAppError() {
        _appError.value = null
    }

    fun reportAppError(message: String) {
        _appError.value = message
    }

    fun askQuestion(query: String) {
        if (query.isBlank() || _isGenerating.value) return
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val userMsg = ChatMessageItem("user", query)
                _chatMessages.value = _chatMessages.value + userMsg

                // 1. Retrieve hybrid subgraph context
                val context = retriever.retrieve(query, topK = 4)

                // 2. Add assistant placeholder
                val assistantMsg = ChatMessageItem("brain", "", context, isStreaming = true)
                _chatMessages.value = _chatMessages.value + assistantMsg

                // 3. Stream response
                val replyBuffer = StringBuilder()
                llm.answerWithContext(query, context).collect { token ->
                    replyBuffer.append(token)
                    val updatedList = _chatMessages.value.toMutableList()
                    val lastIdx = updatedList.lastIndex
                    if (lastIdx >= 0) {
                        updatedList[lastIdx] = ChatMessageItem("brain", replyBuffer.toString(), context, isStreaming = true)
                        _chatMessages.value = updatedList
                    }
                }

                // Finalize
                val updatedList = _chatMessages.value.toMutableList()
                val lastIdx = updatedList.lastIndex
                if (lastIdx >= 0) {
                    updatedList[lastIdx] = ChatMessageItem("brain", replyBuffer.toString(), context, isStreaming = false)
                    _chatMessages.value = updatedList
                }
            } catch (error: Exception) {
                _chatMessages.value = _chatMessages.value + ChatMessageItem(
                    sender = "brain",
                    text = "I couldn't complete that request: ${error.message ?: "unknown error"}"
                )
            } finally {
                _isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        store.close()
        embedder.close()
        llm.release()
        speech.release()
        voiceRecorder.abort()
        stopPlayback()
    }
}
