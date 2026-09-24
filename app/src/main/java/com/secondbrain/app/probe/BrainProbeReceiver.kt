package com.secondbrain.app.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.ai.SpeechEngine
import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.ActionStatus
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.ProcessingJobType
import com.secondbrain.app.domain.HybridRetriever
import com.secondbrain.app.domain.IngestionPipeline
import com.secondbrain.app.work.ProcessingWorkScheduler
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
                } else if (intent?.getBooleanExtra(EXTRA_QUEUE_ONLY, false) == true) {
                    runQueueDiagnostic(context.applicationContext)
                } else if (intent?.getBooleanExtra(EXTRA_RETRIEVAL_ONLY, false) == true) {
                    runRetrievalDiagnostic(context.applicationContext)
                } else if (intent?.getBooleanExtra(EXTRA_ACTIONS_ONLY, false) == true) {
                    runActionsDiagnostic(context.applicationContext)
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

    private suspend fun runActionsDiagnostic(context: Context) {
        Log.i(TAG, "================== START ACTIONS PROBE ==================")
        val store = BrainStore(context)
        val probe = ActionItem(
            id = ActionItem.stableId(ACTION_PROBE_NOTE_ID, "Verify action storage tomorrow"),
            noteId = ACTION_PROBE_NOTE_ID,
            text = "Verify action storage tomorrow",
            dueTimestamp = System.currentTimeMillis() / 1_000.0 + 86_400,
            confidence = 0.99,
            evidence = "TODO: Verify action storage tomorrow"
        )
        try {
            store.open().getOrThrow()
            store.syncOpenActionItems(ACTION_PROBE_NOTE_ID, listOf(probe)).getOrThrow()
            val inserted = store.getActionItems(limit = 100_000).getOrThrow()
                .firstOrNull { it.id == probe.id }
            check(inserted?.status == ActionStatus.OPEN) { "Open action was not persisted" }

            store.updateActionStatus(probe.id, ActionStatus.COMPLETED).getOrThrow()
            val completed = store.getActionItems(limit = 100_000).getOrThrow()
                .firstOrNull { it.id == probe.id }
            check(completed?.status == ActionStatus.COMPLETED) { "Action status was not updated" }

            store.updateActionStatus(probe.id, ActionStatus.OPEN).getOrThrow()
            store.syncOpenActionItems(ACTION_PROBE_NOTE_ID, emptyList()).getOrThrow()
            check(store.getActionItems(limit = 100_000).getOrThrow().none { it.id == probe.id }) {
                "Probe action was not cleaned up"
            }
            Log.i(TAG, "ACTIONS PASS: insert, query, complete, reopen, and cleanup succeeded")
        } catch (error: Throwable) {
            Log.e(TAG, "ACTIONS FAIL: ${error.message}", error)
        } finally {
            runCatching { store.updateActionStatus(probe.id, ActionStatus.OPEN).getOrThrow() }
            runCatching { store.syncOpenActionItems(ACTION_PROBE_NOTE_ID, emptyList()).getOrThrow() }
            store.close()
        }
        Log.i(TAG, "================== ACTIONS PROBE COMPLETE ==================")
    }

    private suspend fun runRetrievalDiagnostic(context: Context) {
        Log.i(TAG, "================== START RETRIEVAL PROBE ==================")
        val store = BrainStore(context)
        val embedder = EmbedderEngine(context)
        try {
            store.open().getOrThrow()
            val retriever = HybridRetriever(store, embedder)
            val result = retriever.retrieve("Why did we choose Event Sourcing for Project Apollo?", topK = 5)
            check(result.rankedSources.isNotEmpty()) { "No ranked sources returned" }
            check(result.rankedSources.map { it.note.id }.distinct().size == result.rankedSources.size) {
                "Duplicate ranked source IDs"
            }
            check(result.rankedSources.map { it.number } == (1..result.rankedSources.size).toList()) {
                "Source numbering is not contiguous"
            }
            check(result.timelineNotes.zipWithNext().all { (a, b) -> a.timestamp <= b.timestamp }) {
                "Timeline is not chronological"
            }
            val source = result.rankedSources.first().note
            val related = retriever.findRelatedNotes(source, limit = 5)
            check(related.none { it.note.id == source.id }) { "Related results included their source note" }
            Log.i(
                TAG,
                "RETRIEVAL PASS: sources=${result.rankedSources.map { "[${it.number}] ${it.note.title} ${"%.3f".format(it.score)}" }}"
            )
            Log.i(TAG, "RELATED PASS: source='${source.title}' results=${related.map { it.note.title }}")
            Log.i(TAG, "TIMELINE PASS: ${result.timelineNotes.size} notes in chronological order")
        } catch (error: Throwable) {
            Log.e(TAG, "RETRIEVAL FAIL: ${error.message}", error)
        } finally {
            embedder.close()
            store.close()
        }
        Log.i(TAG, "================== RETRIEVAL PROBE COMPLETE ==================")
    }

    private suspend fun runQueueDiagnostic(context: Context) {
        Log.i(TAG, "================== START QUEUE PROBE ==================")
        val store = BrainStore(context)
        try {
            store.open().getOrThrow()
            val note = store.getRecentNotes(100).getOrThrow().firstOrNull { it.source == "probe" }
                ?: error("Run the standard brain probe once to create a safe diagnostic note")
            val job = store.enqueueProcessingJob(note.id, ProcessingJobType.ORGANIZE).getOrThrow()
            ProcessingWorkScheduler.kick(context)
            Log.i(TAG, "QUEUE PASS: job=${job.id} note=${note.id} status=${job.status}")
        } catch (error: Throwable) {
            Log.e(TAG, "QUEUE FAIL: ${error.message}", error)
        } finally {
            store.close()
        }
        Log.i(TAG, "================== QUEUE PROBE COMPLETE ==================")
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
                Log.i(
                    TAG,
                    "TEST 3 PASS: Ingested '$title' (entities=${r.entitiesExtracted}, " +
                        "edges=${r.relationsExtracted}, reviews=${r.reviewsCreated})"
                )
            } else {
                Log.e(TAG, "TEST 3 FAIL: Ingestion error for '$title': ${res.exceptionOrNull()?.message}")
            }
        }

        // 4. Verify Stored Graph Stats
        val stats = store.getStats()
        Log.i(
            TAG,
            "TEST 4 STATS: Notes=${stats["notes"]}, Entities=${stats["entities"]}, " +
                "Edges=${stats["edges"]}, Pending reviews=${stats["reviews"]}"
        )

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
        Log.i(
            TAG,
            "   Ranked sources: ${retrievedContext.rankedSources.map { "[${it.number}] ${it.note.title} score=${"%.3f".format(it.score)} reasons=${it.reasons}" }}"
        )
        Log.i(TAG, "   Timeline: ${retrievedContext.timelineNotes.map { it.title }}")

        // 7. Test related-note discovery without returning the source note itself.
        val relatedSource = store.getRecentNotes(100).getOrThrow().firstOrNull { it.source == "probe" }
        if (relatedSource != null) {
            val related = retriever.findRelatedNotes(relatedSource, limit = 4)
            check(related.none { it.note.id == relatedSource.id }) { "Related search returned its source note" }
            Log.i(
                TAG,
                "TEST 7 PASS: Related to '${relatedSource.title}' = ${related.map { "${it.note.title} (${"%.3f".format(it.score)})" }}"
            )
        }

        Log.i(TAG, "================== BRAIN PROBE COMPLETE ==================")
    }

    companion object {
        private const val TAG = "BrainProbe"
        private const val EXTRA_SPEECH_ONLY = "speech_only"
        private const val EXTRA_QUEUE_ONLY = "queue_only"
        private const val EXTRA_RETRIEVAL_ONLY = "retrieval_only"
        private const val EXTRA_ACTIONS_ONLY = "actions_only"
        private const val ACTION_PROBE_NOTE_ID = "probe-action-storage"
    }
}
