package com.secondbrain.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.SubgraphContext
import com.secondbrain.app.domain.HybridRetriever
import com.secondbrain.app.domain.IngestionPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessageItem(
    val sender: String, // "user" or "brain"
    val text: String,
    val context: SubgraphContext? = null,
    val isStreaming: Boolean = false
)

class BrainViewModel(application: Application) : AndroidViewModel(application) {

    val store = BrainStore(application)
    val embedder = EmbedderEngine(application)
    val llm = LlmEngine(application)

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
                .onSuccess { refreshData() }
                .onFailure { _appError.value = "Could not open the local knowledge database: ${it.message}" }

            if (_modelReady.value) {
                loadLocalModel()
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _notes.value = store.getRecentNotes(30).getOrDefault(emptyList())
            _entities.value = store.getAllEntities().getOrDefault(emptyList())
            _edges.value = store.getAllEdges().getOrDefault(emptyList())
            _stats.value = store.getStats()
        }
    }

    fun saveNote(title: String, content: String, onComplete: () -> Unit = {}) {
        if (content.isBlank()) return
        viewModelScope.launch {
            _isIngesting.value = true
            try {
                pipeline.ingest(title, content).getOrThrow()
                refreshData()
                onComplete()
            } catch (error: Exception) {
                _appError.value = "Could not save the note: ${error.message ?: "unknown error"}"
            } finally {
                _isIngesting.value = false
            }
        }
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
    }
}
