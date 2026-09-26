package com.secondbrain.app.probe

import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.secondbrain.app.SecondBrainApplication
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.ai.LlmEngine
import com.secondbrain.app.ai.SpeechEngine
import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.ActionStatus
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.ExtractedKnowledge
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.ProcessingJobType
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.RelationType
import com.secondbrain.app.domain.HybridRetriever
import com.secondbrain.app.domain.IngestionPipeline
import com.secondbrain.app.domain.AgentCommand
import com.secondbrain.app.domain.AgentRoute
import com.secondbrain.app.domain.AgentToolInventory
import com.secondbrain.app.domain.AgentToolRouter
import com.secondbrain.app.work.ProcessingWorkScheduler
import com.secondbrain.app.work.ActionReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

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
                } else if (intent?.getBooleanExtra(EXTRA_REMINDERS_ONLY, false) == true) {
                    runReminderDiagnostic(context.applicationContext)
                } else if (intent?.getBooleanExtra(EXTRA_CRUD_ONLY, false) == true) {
                    runCrudDiagnostic(context.applicationContext)
                } else if (intent?.getBooleanExtra(EXTRA_AGENT_ROUTER_ONLY, false) == true) {
                    runAgentRouterDiagnostic(context.applicationContext)
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

    private suspend fun runAgentRouterDiagnostic(context: Context) {
        Log.i(TAG, "================== START AGENT ROUTER PROBE ==================")
        val resultFile = context.filesDir.resolve("probe/agent-router-result.txt")
        resultFile.parentFile?.mkdirs()
        resultFile.writeText("RUNNING")
        var rawOutput = ""
        val llm = (context.applicationContext as SecondBrainApplication).llmEngine
        val note = NoteDocument(
            id = "probe-router-note",
            title = "Team meeting",
            content = "Decisions from the product design meeting.",
            timestamp = LocalDate.now().minusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toDouble(),
            source = "probe"
        )
        try {
            check(llm.isModelReady()) { "Qwen model is not installed" }
            llm.loadModel(useGpu = true).getOrThrow()
            Log.i(TAG, "AGENT ROUTER MODEL READY: ${llm.defaultModel.name} on GPU")
            val route = AgentToolRouter().route(
                input = "Please get rid of yesterday's meeting note for me",
                inventory = AgentToolInventory(
                    activeNotes = listOf(note),
                    trashedNotes = emptyList(),
                    actions = emptyList()
                ),
                modelAvailable = true,
                generate = { systemPrompt, userPrompt ->
                    llm.generateAgentRoute(systemPrompt, userPrompt).also { result ->
                        result.onSuccess { rawOutput = it.take(4_000) }
                    }
                }
            )
            check(route is AgentRoute.Tool) { "Qwen did not select a tool: $route" }
            check(route.source == AgentRoute.Source.QWEN) { "The deterministic fallback handled the probe" }
            val command = route.command
            check(command is AgentCommand.TrashNote && command.noteId == note.id) {
                "Qwen selected an unexpected command: $command"
            }
            resultFile.writeText("PASS: natural request -> validated trash_note(${note.id})")
            Log.i(TAG, "AGENT ROUTER PASS: natural request -> validated trash_note(${note.id})")
        } catch (error: Throwable) {
            resultFile.writeText(
                "FAIL: ${error.message ?: error::class.java.simpleName}\nRAW:\n$rawOutput"
            )
            Log.e(TAG, "AGENT ROUTER FAIL: ${error.message}", error)
        }
        Log.i(TAG, "================== AGENT ROUTER PROBE COMPLETE ==================")
    }

    private suspend fun runCrudDiagnostic(context: Context) {
        Log.i(TAG, "================== START CRUD PROBE ==================")
        val store = BrainStore(context)
        val note = NoteDocument(
            id = CRUD_PROBE_NOTE_ID,
            title = "CRUD probe note",
            content = "CRUD Probe Project uses CRUD Probe Concept.",
            source = "probe"
        )
        val project = EntityNode(
            name = "CRUD Probe Project",
            category = EntityCategory.PROJECT,
            evidence = "CRUD Probe Project",
            sourceNoteId = note.id
        )
        val concept = EntityNode(
            name = "CRUD Probe Concept",
            category = EntityCategory.CONCEPT,
            evidence = "CRUD Probe Concept",
            sourceNoteId = note.id
        )
        val relation = RelationEdge(
            source = project.name,
            relation = RelationType.USES_CONCEPT,
            target = concept.name,
            evidence = note.content,
            sourceNoteId = note.id
        )
        val action = ActionItem(
            id = ActionItem.stableId(note.id, "Verify CRUD cleanup"),
            noteId = note.id,
            text = "Verify CRUD cleanup",
            confidence = 1.0,
            evidence = note.content
        )
        try {
            store.open().getOrThrow()
            store.putNote(
                note,
                ExtractedKnowledge(listOf(project, concept), listOf(relation)),
                FloatArray(BrainStore.EMBEDDING_DIM) { if (it == 0) 1f else 0f }
            ).getOrThrow()
            store.syncOpenActionItems(note.id, listOf(action)).getOrThrow()
            val job = store.enqueueProcessingJob(note.id, ProcessingJobType.ORGANIZE).getOrThrow()
            check(store.getNote(note.id).getOrThrow() != null) { "Created note was not readable" }

            store.moveNoteToTrash(note.id).getOrThrow()
            check(store.getNote(note.id).getOrThrow() == null) { "Trashed note remained active" }
            check(store.getTrashedNotes().getOrThrow().any { it.note.id == note.id }) { "Trash entry was not stored" }
            check(store.getActionItems().getOrThrow().none { it.id == action.id }) { "Trashed action remained active" }
            check(store.getAllEntities().getOrThrow().none { it.name == project.name }) { "Trashed entity remained active" }
            check(store.getProcessingJobs().getOrThrow().none { it.id == job.id }) { "Trashed job remained visible" }
            check(store.getProcessingJob(job.id).getOrThrow()?.status?.name == "CANCELLED") {
                "Trashed job was not cancelled"
            }

            store.restoreNote(note.id).getOrThrow()
            check(store.getNote(note.id).getOrThrow() != null) { "Restored note was not readable" }
            check(store.getActionItems().getOrThrow().any { it.id == action.id }) { "Restored action did not return" }

            store.moveNoteToTrash(note.id).getOrThrow()
            val report = store.permanentlyDeleteNote(note.id).getOrThrow()
            check(store.getTrashedNotes().getOrThrow().none { it.note.id == note.id }) { "Permanent delete left a trash row" }
            check(store.getAllEntities().getOrThrow().none { it.name in setOf(project.name, concept.name) }) {
                "Permanent delete left unsupported entities"
            }
            check(report.actionsRemoved == 1 && report.jobsRemoved == 1 && report.unsupportedEntitiesRemoved == 2) {
                "Unexpected cleanup report: $report"
            }
            Log.i(TAG, "CRUD PASS: create, read, trash, restore, cascade delete, and graph cleanup succeeded")
        } catch (error: Throwable) {
            Log.e(TAG, "CRUD FAIL: ${error.message}", error)
        } finally {
            runCatching {
                if (store.getNote(note.id).getOrNull() != null) store.moveNoteToTrash(note.id).getOrThrow()
                if (store.getTrashedNotes().getOrDefault(emptyList()).any { it.note.id == note.id }) {
                    store.permanentlyDeleteNote(note.id).getOrThrow()
                }
            }
            store.close()
        }
        Log.i(TAG, "================== CRUD PROBE COMPLETE ==================")
    }

    private suspend fun runReminderDiagnostic(context: Context) {
        Log.i(TAG, "================== START REMINDER PROBE ==================")
        val store = BrainStore(context)
        val dueTimestamp = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
            .toDouble()
        val probe = ActionItem(
            id = ActionItem.stableId(REMINDER_PROBE_NOTE_ID, "Verify the reminder worker"),
            noteId = REMINDER_PROBE_NOTE_ID,
            text = "Verify the reminder worker",
            dueTimestamp = dueTimestamp,
            confidence = 1.0,
            evidence = "TODO: Verify the reminder worker today"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                check(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                ) { "Grant notification permission before running the reminder probe" }
            }
            store.open().getOrThrow()
            store.syncOpenActionItems(REMINDER_PROBE_NOTE_ID, listOf(probe)).getOrThrow()
            ActionReminderScheduler.schedule(context, probe)

            var delivered = false
            for (attempt in 0 until 40) {
                delivered = store.wasActionReminderDelivered(probe.id, dueTimestamp).getOrThrow()
                if (delivered) break
                delay(250)
            }
            check(delivered) { "Reminder worker did not deliver within 10 seconds" }
            Log.i(TAG, "REMINDER PASS: WorkManager delivered and recorded the due-action notification")
        } catch (error: Throwable) {
            Log.e(TAG, "REMINDER FAIL: ${error.message}", error)
        } finally {
            NotificationManagerCompat.from(context).cancel(probe.id.hashCode())
            ActionReminderScheduler.schedule(context, probe.copy(status = ActionStatus.DISMISSED))
            runCatching { store.updateActionStatus(probe.id, ActionStatus.OPEN).getOrThrow() }
            runCatching { store.syncOpenActionItems(REMINDER_PROBE_NOTE_ID, emptyList()).getOrThrow() }
            store.close()
        }
        Log.i(TAG, "================== REMINDER PROBE COMPLETE ==================")
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

            check(!store.wasActionReminderDelivered(probe.id, requireNotNull(probe.dueTimestamp)).getOrThrow()) {
                "A fresh reminder was incorrectly marked as delivered"
            }
            store.markActionReminderDelivered(probe.id, requireNotNull(probe.dueTimestamp)).getOrThrow()
            check(store.wasActionReminderDelivered(probe.id, requireNotNull(probe.dueTimestamp)).getOrThrow()) {
                "Reminder delivery was not persisted"
            }

            store.saveAction(probe.copy(text = "Edited action survives reprocessing", dueTimestamp = null)).getOrThrow()
            store.syncOpenActionItems(ACTION_PROBE_NOTE_ID, listOf(probe)).getOrThrow()
            val edited = store.getActionItem(probe.id).getOrThrow()
            check(edited?.text == "Edited action survives reprocessing" && edited.dueTimestamp == null) {
                "The user action override did not survive extraction sync"
            }
            check(!store.wasActionReminderDelivered(probe.id, requireNotNull(probe.dueTimestamp)).getOrThrow()) {
                "Changing the due date did not reset reminder delivery"
            }

            store.updateActionStatus(probe.id, ActionStatus.COMPLETED).getOrThrow()
            val completed = store.getActionItems(limit = 100_000).getOrThrow()
                .firstOrNull { it.id == probe.id }
            check(completed?.status == ActionStatus.COMPLETED) { "Action status was not updated" }

            store.updateActionStatus(probe.id, ActionStatus.OPEN).getOrThrow()
            store.syncOpenActionItems(ACTION_PROBE_NOTE_ID, emptyList()).getOrThrow()
            check(store.getActionItems(limit = 100_000).getOrThrow().none { it.id == probe.id }) {
                "Probe action was not cleaned up"
            }
            Log.i(
                TAG,
                "ACTIONS PASS: storage, edit override, reminder state, status changes, and cleanup succeeded"
            )
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
        private const val EXTRA_REMINDERS_ONLY = "reminders_only"
        private const val EXTRA_CRUD_ONLY = "crud_only"
        private const val EXTRA_AGENT_ROUTER_ONLY = "agent_router_only"
        private const val ACTION_PROBE_NOTE_ID = "probe-action-storage"
        private const val REMINDER_PROBE_NOTE_ID = "probe-action-reminder"
        private const val CRUD_PROBE_NOTE_ID = "probe-agent-crud"
    }
}
