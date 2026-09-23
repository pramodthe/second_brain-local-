package com.secondbrain.app.domain

import android.util.Log
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.SubgraphContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HybridRetriever(
    private val store: BrainStore,
    private val embedder: EmbedderEngine
) {

    /**
     * Executes two-stage hybrid retrieval:
     * 1. Vector similarity search over notes (HNSW index)
     * 2. Graph expansion around matched concepts & entities
     */
    suspend fun retrieve(query: String, topK: Int = 4): SubgraphContext = withContext(Dispatchers.Default) {
        // Step 1: Embed query and search via HNSW vector index
        val queryVec = embedder.embed(query)
        val vectorResults = store.searchSimilarNotes(queryVec, k = topK).getOrDefault(emptyList())
        val matchedNotes = vectorResults.map { it.first }

        // Step 2: Extract candidate entity anchors from query and matched notes
        val allEntities = store.getAllEntities().getOrDefault(emptyList())
        val queryLower = query.lowercase()

        // Match entities whose names appear in the query or matched note titles/content
        val candidateAnchors = allEntities.filter { entity ->
            val entityName = entity.name.lowercase()
            queryLower.contains(entityName) ||
                    matchedNotes.any { note -> note.content.lowercase().contains(entityName) }
        }.take(6)

        // Step 3: Expand 1-to-2 hops in the graph around candidate anchors
        val anchorNames = candidateAnchors.map { it.name }
        val neighborhood = if (anchorNames.isNotEmpty()) {
            store.getNeighborhood(anchorNames).getOrDefault(
                SubgraphContext(candidateAnchors, emptyList(), matchedNotes)
            )
        } else {
            SubgraphContext(emptyList(), emptyList(), matchedNotes)
        }

        // Merge notes from vector search and neighborhood
        val combinedNotes = (matchedNotes + neighborhood.relatedNotes).distinctBy { it.id }
        val combinedEntities = (candidateAnchors + neighborhood.anchorEntities).distinctBy { it.name }

        Log.i(TAG, "Retrieved ${combinedEntities.size} entities, ${neighborhood.connectedEdges.size} edges, ${combinedNotes.size} notes for query: '$query'")

        SubgraphContext(
            anchorEntities = combinedEntities,
            connectedEdges = neighborhood.connectedEdges,
            relatedNotes = combinedNotes
        )
    }

    companion object {
        private const val TAG = "HybridRetriever"
    }
}
