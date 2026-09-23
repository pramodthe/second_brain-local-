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

    /**
     * Full pipeline:
     * 1. Vector embedding generation
     * 2. Ontology & entity extraction (LLM / rules)
     * 3. Entity resolution against existing graph
     * 4. Atomic persistence in CozoDB
     */
    suspend fun ingest(
        title: String,
        content: String,
        source: String = "manual"
    ): Result<IngestionResult> = withContext(Dispatchers.Default) {
        runCatching {
            val id = "n" + System.currentTimeMillis()
            val note = NoteDocument(
                id = id,
                title = title.ifBlank { "Untitled Note" },
                content = content,
                timestamp = System.currentTimeMillis() / 1000.0,
                source = source
            )

            // Step 1: Generate Embedding
            val embedding = embedder.embed("$title\n$content")

            // Step 2: Extract Entities & Relations
            val extracted = llm.extractOntology(content)

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
