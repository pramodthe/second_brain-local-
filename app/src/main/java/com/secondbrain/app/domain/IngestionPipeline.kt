package com.secondbrain.app.domain

import android.util.Log
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.ExtractedKnowledge
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class IngestionResult(
    val note: NoteDocument,
    val entitiesExtracted: Int,
    val relationsExtracted: Int,
    val vectorDimension: Int
)

class IngestionPipeline(
    private val store: BrainStore,
    private val embedder: EmbedderEngine,
    private val llm: LlmEngine
) {

    /** Persists the user's original note before any AI work begins. */
    suspend fun capture(
        title: String,
        content: String,
        source: String = "manual"
    ): Result<NoteDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val capturedAt = System.currentTimeMillis() / 1000.0
            val note = NoteDocument(
                id = "n-${UUID.randomUUID()}",
                title = title.trim(),
                content = content,
                timestamp = capturedAt,
                source = source,
                modifiedTimestamp = capturedAt
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
                title = title.trim(),
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

            // Step 3: Entity Resolution (deduplication against existing nodes)
            val resolved = resolveEntities(extracted)

            // Step 4: Atomic storage in CozoDB
            store.putNote(note, resolved, embedding).getOrThrow()

            Log.i(TAG, "Ingested note '${note.title}' with ${resolved.entities.size} entities, ${resolved.relations.size} edges")

            IngestionResult(
                note = note,
                entitiesExtracted = resolved.entities.size,
                relationsExtracted = resolved.relations.size,
                vectorDimension = embedding.size
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

    private suspend fun resolveEntities(raw: ExtractedKnowledge): ExtractedKnowledge {
        val existing = store.getAllEntities().getOrDefault(emptyList())
        val existingMap = existing.associateBy { it.name.lowercase().trim() }

        val resolvedEntities = mutableListOf<EntityNode>()
        val nameMapping = mutableMapOf<String, String>()

        for (e in raw.entities) {
            val key = e.name.lowercase().trim()
            val match = existingMap[key]
            if (match != null) {
                // Reuse canonical existing entity name
                resolvedEntities.add(match)
                nameMapping[e.name] = match.name
            } else {
                resolvedEntities.add(e)
                nameMapping[e.name] = e.name
            }
        }

        val resolvedRelations = raw.relations.map { r ->
            RelationEdge(
                source = nameMapping[r.source] ?: r.source,
                relation = r.relation,
                target = nameMapping[r.target] ?: r.target,
                timestamp = r.timestamp
            )
        }

        return ExtractedKnowledge(resolvedEntities.distinctBy { it.name }, resolvedRelations.distinct())
    }

    companion object {
        private const val TAG = "IngestionPipeline"
    }
}
