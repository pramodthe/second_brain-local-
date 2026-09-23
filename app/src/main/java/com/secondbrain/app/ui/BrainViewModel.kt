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

    init {
        viewModelScope.launch {
            store.open()
            refreshData()
            // Attempt to load LLM in background if model file is already present
            if (llm.isModelReady()) {
                llm.loadModel(onCpuOnly = true)
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
            } finally {
                _isIngesting.value = false
            }
        }
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
