package com.secondbrain.app.domain

import android.util.Log
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RetrievedSource
import com.secondbrain.app.data.SubgraphContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HybridRetriever(
    private val store: BrainStore,
    private val embedder: EmbedderEngine
) {

    /**
     * Combines vector distance, lexical relevance, graph overlap, and recency,
     * then expands two graph hops and returns numbered source excerpts.
     */
    suspend fun retrieve(query: String, topK: Int = 5): SubgraphContext = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext SubgraphContext(emptyList(), emptyList(), emptyList())
        val snapshot = loadSnapshot(query, topK)

        val explicitQueryEntities = matchQueryEntities(query, snapshot.entities)
        val firstPass = RetrievalRanker.rank(
            query = query,
            inputs = snapshot.inputs,
            queryEntities = explicitQueryEntities.mapTo(mutableSetOf()) { it.name },
            limit = maxOf(topK * 2, 8)
        )
        val inferredEntityNames = if (explicitQueryEntities.isEmpty()) {
            firstPass.filter { it.score >= INFERRED_ANCHOR_MIN_SCORE }
                .take(2)
                .flatMap { snapshot.noteEntities[it.note.id].orEmpty() }
                .toSet()
        } else {
            emptySet()
        }
        val scoringEntityNames = explicitQueryEntities.mapTo(mutableSetOf()) { it.name }
            .apply { addAll(inferredEntityNames) }

        val rankedSources = RetrievalRanker.rank(
            query = query,
            inputs = snapshot.inputs,
            queryEntities = scoringEntityNames,
            limit = topK
        )
        val anchorNames = scoringEntityNames.toList().take(8)
        val neighborhood = if (anchorNames.isEmpty()) {
            SubgraphContext(emptyList(), emptyList(), emptyList())
        } else {
            store.getNeighborhood(anchorNames).getOrElse { error ->
                Log.e(TAG, "Graph expansion failed for anchors=$anchorNames", error)
                SubgraphContext(explicitQueryEntities, emptyList(), emptyList())
            }
        }

        val combinedEntities = (explicitQueryEntities + neighborhood.anchorEntities)
            .distinctBy { RetrievalRanker.normalize(it.name) }
        val sources = rankedSources.renumbered()
        val sourceNotes = sources.map { it.note }
        val timeline = (sourceNotes + neighborhood.relatedNotes)
            .distinctBy { it.id }
            .sortedBy { it.timestamp }

        Log.i(
            TAG,
            "Hybrid retrieval returned ${sources.size} ranked sources, ${combinedEntities.size} entities, " +
                "and ${neighborhood.connectedEdges.size} edges for '$query'"
        )

        SubgraphContext(
            anchorEntities = combinedEntities,
            connectedEdges = neighborhood.connectedEdges,
            relatedNotes = sourceNotes,
            rankedSources = sources,
            timelineNotes = timeline
        )
    }

    suspend fun search(query: String, limit: Int = 20): List<RetrievedSource> =
        retrieve(query, limit).rankedSources

    suspend fun findRelatedNotes(note: NoteDocument, limit: Int = 6): List<RetrievedSource> =
        withContext(Dispatchers.Default) {
            val query = listOf(note.title, note.content).filter { it.isNotBlank() }.joinToString("\n").take(2_000)
            if (query.isBlank()) return@withContext emptyList()
            val snapshot = loadSnapshot(query, maxOf(limit, 8))
            val sourceEntities = snapshot.noteEntities[note.id].orEmpty()
            RetrievalRanker.rank(
                query = query,
                inputs = snapshot.inputs,
                queryEntities = sourceEntities,
                limit = limit,
                excludeNoteId = note.id
            ).renumbered()
        }

    private suspend fun loadSnapshot(query: String, topK: Int): RetrievalSnapshot {
        val notes = store.getRecentNotes(MAX_NOTES).getOrDefault(emptyList())
        val entities = store.getAllEntities().getOrDefault(emptyList())
        val noteEntities = store.getNoteEntityNames().getOrDefault(emptyMap())
        val vectorDistances = store.searchSimilarNotes(
            embedder.embed(query),
            k = maxOf(topK * 4, 24).coerceAtMost(MAX_NOTES)
        ).getOrDefault(emptyList())
            .groupBy { it.first.id }
            .mapValues { (_, matches) -> matches.minOf { it.second.toDouble() } }
        val inputs = notes.map { note ->
            NoteScoreInput(
                note = note,
                vectorDistance = vectorDistances[note.id],
                entityNames = noteEntities[note.id].orEmpty()
            )
        }
        return RetrievalSnapshot(inputs, entities, noteEntities)
    }

    private fun matchQueryEntities(query: String, entities: List<EntityNode>): List<EntityNode> {
        val normalizedQuery = RetrievalRanker.normalize(query)
        return entities.filter { entity ->
            (listOf(entity.name) + entity.aliases).any { candidate ->
                val normalized = RetrievalRanker.normalize(candidate)
                normalized.length >= 2 && normalizedQuery.contains(normalized)
            }
        }
    }

    private fun List<RetrievedSource>.renumbered(): List<RetrievedSource> =
        mapIndexed { index, source -> source.copy(number = index + 1) }

    private data class RetrievalSnapshot(
        val inputs: List<NoteScoreInput>,
        val entities: List<EntityNode>,
        val noteEntities: Map<String, Set<String>>
    )

    companion object {
        private const val TAG = "HybridRetriever"
        private const val MAX_NOTES = 500
        private const val INFERRED_ANCHOR_MIN_SCORE = 0.42
    }
}
