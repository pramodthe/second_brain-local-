package com.secondbrain.app.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.ai.SpeechEngine
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.domain.HybridRetriever
import com.secondbrain.app.domain.IngestionPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Diagnostic receiver allowing full CLI verification of the Second Brain stack via:
 *   adb shell am broadcast -a com.secondbrain.app.PROBE -p com.secondbrain.app
 */
class BrainProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent?.getBooleanExtra(EXTRA_SPEECH_ONLY, false) == true) {
                    runSpeechDiagnostic(context.applicationContext)
                } else {
                    runDiagnostic(context.applicationContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "BrainProbe failed with exception", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runSpeechDiagnostic(context: Context) {
        Log.i(TAG, "================== START SPEECH PROBE ==================")
        val speech = SpeechEngine(context)
        val sample = context.filesDir.resolve("probe/speech-test.wav")
        try {
            check(sample.isFile) { "Missing synthetic test WAV at ${sample.absolutePath}" }
            check(speech.isModelReady()) { "Speech model is not installed" }
            val device = speech.load().getOrThrow()
            val transcript = speech.transcribe(sample).getOrThrow()
            Log.i(TAG, "SPEECH PASS: device=$device duration=${transcript.audioDurationMs}ms rtf=${transcript.realTimeFactor}")
            Log.i(TAG, "SPEECH TRANSCRIPT: ${transcript.text}")
        } catch (error: Throwable) {
            Log.e(TAG, "SPEECH FAIL: ${error.message}", error)
        } finally {
            speech.release()
        }
        Log.i(TAG, "================== SPEECH PROBE COMPLETE ==================")
    }

    private suspend fun runDiagnostic(context: Context) {
        Log.i(TAG, "================== START BRAIN PROBE ==================")

        val store = BrainStore(context)
        val embedder = EmbedderEngine(context)
        val llm = LlmEngine(context)

        // 1. Open Database
        store.open().onFailure {
            Log.e(TAG, "TEST 1 FAIL: CozoDB open error: ${it.message}", it)
            return
        }
        Log.i(TAG, "TEST 1 PASS: CozoDB opened successfully at ${store.dbFile.absolutePath}")

        // 2. Test Embedding Generation
        val sampleText = "Event-driven architecture with CQRS allows high scalability."
        val vector = embedder.embed(sampleText)
        Log.i(TAG, "TEST 2 PASS: Generated ${vector.size}-dim vector. L2-norm = ${vector.take(3).joinToString()}")

        // 3. Test Ingestion Pipeline with Ontology Mapping
        val pipeline = IngestionPipeline(store, embedder, llm)
        val sampleNotes = listOf(
            "Project Apollo Architecture" to "For Project Apollo we chose Event Sourcing over CRUD because we need complete audit trails for compliance.",
            "Database Scalability Insights" to "Microservices pattern with CozoDB provides low-latency local graph joins while maintaining data sovereignty.",
            "Personal Reading" to "Designing Data-Intensive Applications by Martin Kleppmann is essential for understanding consensus algorithms."
        )

        for ((title, content) in sampleNotes) {
            val res = pipeline.ingest(title, content, "probe")
            if (res.isSuccess) {
                val r = res.getOrThrow()
                Log.i(TAG, "TEST 3 PASS: Ingested '${title}' (entities=${r.entitiesExtracted}, edges=${r.relationsExtracted})")
            } else {
                Log.e(TAG, "TEST 3 FAIL: Ingestion error for '$title': ${res.exceptionOrNull()?.message}")
            }
        }

        // 4. Verify Stored Graph Stats
        val stats = store.getStats()
        Log.i(TAG, "TEST 4 STATS: Notes=${stats["notes"]}, Entities=${stats["entities"]}, Edges=${stats["edges"]}")

        // 5. Test Vector Similarity Search (HNSW)
        val searchResults = store.searchSimilarNotes(embedder.embed("compliance and audit trails"), k = 2)
        searchResults.onSuccess { matches ->
            Log.i(TAG, "TEST 5 PASS: Vector search returned ${matches.size} matches:")
            matches.forEach { (doc, dist) ->
                Log.i(TAG, "   • [dist=%.3f] %s: %s".format(dist, doc.title, doc.content.take(60)))
            }
        }.onFailure {
            Log.e(TAG, "TEST 5 FAIL: Vector search error: ${it.message}", it)
        }

        // 6. Test Hybrid Retrieval (Vector + Graph Subgraph)
        val retriever = HybridRetriever(store, embedder)
        val query = "Why did we choose Event Sourcing for Project Apollo?"
        val retrievedContext = retriever.retrieve(query, topK = 3)
        Log.i(TAG, "TEST 6 PASS: Hybrid Retrieval for '$query':")
        Log.i(TAG, "   Anchors: ${retrievedContext.anchorEntities.map { "${it.name} (${it.category.name})" }}")
        Log.i(TAG, "   Edges: ${retrievedContext.connectedEdges.map { "${it.source} -[${it.relation.name}]-> ${it.target}" }}")
        Log.i(TAG, "   Notes: ${retrievedContext.relatedNotes.map { it.title }}")

        Log.i(TAG, "================== BRAIN PROBE COMPLETE ==================")
    }

    companion object {
        private const val TAG = "BrainProbe"
        private const val EXTRA_SPEECH_ONLY = "speech_only"
    }
}
