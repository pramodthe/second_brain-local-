package com.secondbrain.app.domain

import android.util.Log
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.TranscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class IngestionResult(
    val note: NoteDocument,
    val entitiesExtracted: Int,
    val relationsExtracted: Int,
    val vectorDimension: Int,
    val reviewsCreated: Int = 0
)

class IngestionPipeline(
    private val store: BrainStore,
    private val embedder: EmbedderEngine,
    private val llm: LlmEngine
) {
    private val resolver = KnowledgeResolver()

    /** Persists the user's original note before any AI work begins. */
    suspend fun capture(
        title: String,
        content: String,
        source: String = "manual",
        audioPath: String? = null,
        audioDurationMs: Long? = null,
        transcriptionStatus: TranscriptionStatus = TranscriptionStatus.NONE
    ): Result<NoteDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val capturedAt = System.currentTimeMillis() / 1000.0
            val note = NoteDocument(
                id = "n-${UUID.randomUUID()}",
                title = title.trim().ifBlank { NoteTitleGenerator.fromContent(content) },
                content = content,
                timestamp = capturedAt,
                source = source,
                modifiedTimestamp = capturedAt,
                audioPath = audioPath,
                audioDurationMs = audioDurationMs,
                transcriptionStatus = transcriptionStatus
            )
            store.putNote(note).getOrThrow()
            note
        }
    }

    /** Updates raw note text while keeping the original creation time and source. */
    suspend fun update(
        existing: NoteDocument,
        title: String,
        content: String
    ): Result<NoteDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val updated = existing.copy(
                title = title.trim().ifBlank { NoteTitleGenerator.fromContent(content) },
                content = content,
                modifiedTimestamp = System.currentTimeMillis() / 1000.0
            )
            store.putNote(updated).getOrThrow()
            updated
        }
    }

    /**
     * Generates the derived embedding and knowledge graph after the raw note is safe.
     */
    suspend fun enrich(note: NoteDocument): Result<IngestionResult> = withContext(Dispatchers.Default) {
        runCatching {
            val titleAndContent = listOf(note.title, note.content)
                .filter { it.isNotBlank() }
                .joinToString("\n")

            // Step 1: Generate Embedding
            val embedding = embedder.embed(titleAndContent)

            // Step 2: Extract Entities & Relations
            val extracted = llm.extractOntology(note.content)

            // Step 3: Trust-aware entity resolution. Only supported, high-confidence
            // knowledge enters the graph; ambiguous proposals go to the review inbox.
            val resolution = resolver.resolve(
                note = note,
                raw = extracted,
                existing = store.getAllEntities().getOrDefault(emptyList()),
                acceptedAliases = store.getAcceptedAliasMap().getOrDefault(emptyMap())
            )

            // Step 4: Replace stale pending proposals for this note, then persist the
            // accepted graph and its fresh review proposals.
            store.removePendingKnowledgeReviews(note.id).getOrThrow()
            store.putNote(note, resolution.accepted, embedding).getOrThrow()
            store.putKnowledgeReviews(resolution.reviews).getOrThrow()

            Log.i(
                TAG,
                "Ingested note '${note.title}' with ${resolution.accepted.entities.size} entities, " +
                    "${resolution.accepted.relations.size} edges, ${resolution.reviews.size} reviews"
            )

            IngestionResult(
                note = note,
                entitiesExtracted = resolution.accepted.entities.size,
                relationsExtracted = resolution.accepted.relations.size,
                vectorDimension = embedding.size,
                reviewsCreated = resolution.reviews.size
            )
        }
    }

    /** Capture plus enrichment for imports and diagnostic callers that need a completed result. */
    suspend fun ingest(
        title: String,
        content: String,
        source: String = "manual"
    ): Result<IngestionResult> = runCatching {
        val note = capture(title, content, source).getOrThrow()
        enrich(note).getOrThrow()
    }

    companion object {
        private const val TAG = "IngestionPipeline"
    }
}
