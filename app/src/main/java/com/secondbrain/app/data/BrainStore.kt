package com.secondbrain.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cozodb.CozoDb
import org.json.JSONArray
import java.io.File

/**
 * Embedded Knowledge Graph and HNSW Vector Store backed by CozoDB.
 * Manages notes, ontology entities, relational edges, and vector indices.
 */
class BrainStore(private val context: Context) {

    private data class StoredAudio(
        val path: String,
        val durationMs: Long,
        val status: TranscriptionStatus
    )

    private data class EntityQuality(
        val confidence: Double,
        val status: KnowledgeStatus,
        val evidence: String,
        val noteId: String
    )

    private data class EdgeQuality(
        val confidence: Double,
        val status: KnowledgeStatus,
        val evidence: String,
        val noteId: String
    )

    private var db: CozoDb? = null

    val dbFile: File
        get() = File(context.filesDir, "brain").apply { mkdirs() }.resolve("knowledge.db")

    fun isOpen(): Boolean = db != null

    suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (db != null) return@runCatching Unit
            val d = CozoDb("sqlite", dbFile.absolutePath)
            db = d

            SCHEMA.forEach { stmt ->
                runCatching { d.run(stmt) }
                    .onFailure {
                        Log.d(TAG, "Schema notice (likely already exists): ${it.message?.take(80)}")
                    }
            }
            Log.i(TAG, "BrainStore initialized at ${dbFile.absolutePath}")
            Unit
        }.onFailure { Log.e(TAG, "Failed to open BrainStore", it) }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun now(): Double = System.currentTimeMillis() / 1000.0

    /**
     * Stores a note document along with extracted entities, edges, and optional dense embedding.
     */
    suspend fun putNote(
        note: NoteDocument,
        knowledge: ExtractedKnowledge = ExtractedKnowledge(emptyList(), emptyList()),
        embedding: FloatArray? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")

            // 1. Store Note document
            d.run(
                """
                ?[id, title, content, at, source] <- [["${esc(note.id)}", "${esc(note.title)}", "${esc(note.content)}", ${note.timestamp}, "${esc(note.source)}"]]
                :put note {id => title, content, at, source}
                """.trimIndent()
            )
            d.run(
                """
                ?[note_id, modified_at] <- [["${esc(note.id)}", ${note.modifiedTimestamp}]]
                :put note_meta {note_id => modified_at}
                """.trimIndent()
            )
            note.audioPath?.let { audioPath ->
                d.run(
                    """
                    ?[note_id, path, duration_ms, status] <- [["${esc(note.id)}", "${esc(audioPath)}", ${note.audioDurationMs ?: 0L}, "${note.transcriptionStatus.name}"]]
                    :put note_audio {note_id => path, duration_ms, status}
                    """.trimIndent()
                )
            }

            // 2. Store Entities
            if (knowledge.entities.isNotEmpty()) {
                val entityRows = knowledge.entities.joinToString(", ") { e ->
                    """["${esc(e.name)}", "${esc(e.category.name)}", "${esc(e.description)}", ${e.timestamp}]"""
                }
                d.run(
                    """
                    ?[name, category, description, at] <- [$entityRows]
                    :put entity {name => category, description, at}
                    """.trimIndent()
                )

                val qualityRows = knowledge.entities.joinToString(", ") { e ->
                    """["${esc(e.name)}", ${e.confidence.coerceIn(0.0, 1.0)}, "${e.status.name}", "${esc(e.evidence)}", "${esc(e.sourceNoteId.ifBlank { note.id })}"]"""
                }
                d.run(
                    """
                    ?[name, confidence, status, evidence, note_id] <- [$qualityRows]
                    :put entity_quality {name => confidence, status, evidence, note_id}
                    """.trimIndent()
                )

                knowledge.entities.forEach { entity ->
                    putAliases(d, entity.name, entity.aliases, entity.confidence, entity.sourceNoteId.ifBlank { note.id })
                }

                // Link note to entities
                val noteEntityRows = knowledge.entities.joinToString(", ") { e ->
                    """["${esc(note.id)}", "${esc(e.name)}", ${now()}]"""
                }
                d.run(
                    """
                    ?[note_id, entity_name, at] <- [$noteEntityRows]
                    :put note_entity {note_id, entity_name => at}
                    """.trimIndent()
                )
            }

            // 3. Store Relational Edges
            if (knowledge.relations.isNotEmpty()) {
                val edgeRows = knowledge.relations.joinToString(", ") { r ->
                    """["${esc(r.source)}", "${esc(r.relation.name)}", "${esc(r.target)}", ${r.timestamp}]"""
                }
                d.run(
                    """
                    ?[source, relation, target, at] <- [$edgeRows]
                    :put edge {source, relation, target => at}
                    """.trimIndent()
                )
                val edgeQualityRows = knowledge.relations.joinToString(", ") { r ->
                    """["${esc(r.source)}", "${r.relation.name}", "${esc(r.target)}", ${r.confidence.coerceIn(0.0, 1.0)}, "${r.status.name}", "${esc(r.evidence)}", "${esc(r.sourceNoteId.ifBlank { note.id })}"]"""
                }
                d.run(
                    """
                    ?[source, relation, target, confidence, status, evidence, note_id] <- [$edgeQualityRows]
                    :put edge_quality {source, relation, target => confidence, status, evidence, note_id}
                    """.trimIndent()
                )
            }

            // 4. Store Vector Embedding if available
            if (embedding != null && embedding.size == EMBEDDING_DIM) {
                val vecStr = embedding.joinToString(", ") { it.toString() }
                d.run(
                    """
                    ?[note_id, embedding, at] <- [["${esc(note.id)}", vec([$vecStr]), ${now()}]]
                    :put note_vector {note_id => embedding, at}
                    """.trimIndent()
                )
            }
            Unit
        }
    }

    /**
     * Vector Similarity Search using CozoDB's native HNSW index.
     */
    suspend fun searchSimilarNotes(
        queryVector: FloatArray,
        k: Int = 5
    ): Result<List<Pair<NoteDocument, Float>>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            if (queryVector.size != EMBEDDING_DIM) return@runCatching emptyList()

            val vecStr = queryVector.joinToString(", ") { it.toString() }
            val query = """
                ?[id, title, content, at, source, dist] := 
                    ~note_vector:vec_idx{note_id: id | query: vec([$vecStr]), k: $k, ef: 64, bind_distance: dist},
                    *note{id, title, content, at, source}
                :order dist
            """.trimIndent()

            val rows = d.run(query)
            val modifiedTimes = loadModifiedTimes(d)
            val audioMetadata = loadAudioMetadata(d)
            rows.map { r ->
                val id = r.rows[0].asString()
                val createdAt = r.rows[3].asDouble()
                val audio = audioMetadata[id]
                val doc = NoteDocument(
                    id = id,
                    title = r.rows[1].asString(),
                    content = r.rows[2].asString(),
                    timestamp = createdAt,
                    source = r.rows[4].asString(),
                    modifiedTimestamp = modifiedTimes[id] ?: createdAt,
                    audioPath = audio?.path,
                    audioDurationMs = audio?.durationMs,
                    transcriptionStatus = audio?.status ?: TranscriptionStatus.NONE
                )
                val dist = r.rows[5].asFloat()
                doc to dist
            }
        }
    }

    /**
     * Expands 1-to-2 hops outward from anchor entity names.
     */
    suspend fun getNeighborhood(entityNames: List<String>): Result<SubgraphContext> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = db ?: error("Database not open")
                if (entityNames.isEmpty()) {
                    return@runCatching SubgraphContext(emptyList(), emptyList(), emptyList())
                }

                val anchors = entityNames.map { esc(it.lowercase().trim()) }.filter { it.isNotBlank() }
                val filterClause = anchors.joinToString(" or ") { a ->
                    "lowercase(source) == \"$a\" or lowercase(target) == \"$a\""
                }

                // Query 1: first-hop edges connected to anchor entities.
                val edgeQuery = """
                    ?[source, relation, target, at] := *edge{source, relation, target, at},
                        ($filterClause)
                    :limit 30
                """.trimIndent()
                val firstHopRows = d.run(edgeQuery)
                val firstHopNames = (
                    entityNames +
                        firstHopRows.map { it.rows[0].asString() } +
                        firstHopRows.map { it.rows[2].asString() }
                    ).distinct()
                val secondHopFilter = firstHopNames
                    .map { esc(it.lowercase().trim()) }
                    .filter { it.isNotBlank() }
                    .joinToString(" or ") { name ->
                        "lowercase(source) == \"$name\" or lowercase(target) == \"$name\""
                    }
                val secondHopRows = if (secondHopFilter.isBlank()) emptyList() else d.run(
                    """
                    ?[source, relation, target, at] := *edge{source, relation, target, at},
                        ($secondHopFilter)
                    :limit 60
                    """.trimIndent()
                )
                val edgeRows = (firstHopRows + secondHopRows).distinctBy { row ->
                    "${row.rows[0].asString()}|${row.rows[1].asString()}|${row.rows[2].asString()}"
                }
                val edgeQualities = loadEdgeQualities(d)
                val edges = edgeRows.map { r ->
                    val source = r.rows[0].asString()
                    val relation = RelationType.fromString(r.rows[1].asString())
                    val target = r.rows[2].asString()
                    val quality = edgeQualities[edgeKey(source, relation.name, target)]
                    RelationEdge(
                        source = source,
                        relation = relation,
                        target = target,
                        timestamp = r.rows[3].asDouble(),
                        confidence = quality?.confidence ?: 1.0,
                        evidence = quality?.evidence.orEmpty(),
                        sourceNoteId = quality?.noteId.orEmpty(),
                        status = quality?.status ?: KnowledgeStatus.ACCEPTED
                    )
                }

                // Query 2: Entity details
                val touchedNames = (entityNames + edges.map { it.source } + edges.map { it.target }).distinct()
                val entityFilter = touchedNames.map { esc(it.lowercase().trim()) }.filter { it.isNotBlank() }
                    .joinToString(" or ") { n ->
                        "lowercase(name) == \"$n\""
                    }
                val entityQuery = """
                    ?[name, category, description, at] := *entity{name, category, description, at},
                        ($entityFilter)
                """.trimIndent()
                val entityRows = d.run(entityQuery)
                val entityQualities = loadEntityQualities(d)
                val aliases = loadAliasesByCanonical(d)
                val entities = entityRows.map { r ->
                    val name = r.rows[0].asString()
                    val quality = entityQualities[name]
                    EntityNode(
                        name = name,
                        category = EntityCategory.fromString(r.rows[1].asString()),
                        description = r.rows[2].asString(),
                        timestamp = r.rows[3].asDouble(),
                        aliases = aliases[name].orEmpty(),
                        confidence = quality?.confidence ?: 1.0,
                        evidence = quality?.evidence.orEmpty(),
                        sourceNoteId = quality?.noteId.orEmpty(),
                        status = quality?.status ?: KnowledgeStatus.ACCEPTED
                    )
                }

                // Query 3: Notes referencing these entities
                val noteFilter = touchedNames.map { esc(it.lowercase().trim()) }
                    .filter { it.isNotBlank() }
                    .joinToString(" or ") { a ->
                        "lowercase(entity_name) == \"$a\""
                }
                val noteQuery = """
                    ?[id, title, content, at, source] := *note_entity{note_id: id, entity_name},
                        *note{id, title, content, at, source},
                        ($noteFilter)
                    :limit 10
                """.trimIndent()
                val noteRows = runCatching { d.run(noteQuery) }.getOrDefault(emptyList())
                val modifiedTimes = loadModifiedTimes(d)
                val audioMetadata = loadAudioMetadata(d)
                val notes = noteRows.map { r ->
                    val id = r.rows[0].asString()
                    val createdAt = r.rows[3].asDouble()
                    val audio = audioMetadata[id]
                    NoteDocument(
                        id = id,
                        title = r.rows[1].asString(),
                        content = r.rows[2].asString(),
                        timestamp = createdAt,
                        source = r.rows[4].asString(),
                        modifiedTimestamp = modifiedTimes[id] ?: createdAt,
                        audioPath = audio?.path,
                        audioDurationMs = audio?.durationMs,
                        transcriptionStatus = audio?.status ?: TranscriptionStatus.NONE
                    )
                }

                SubgraphContext(entities, edges, notes)
            }
        }

    /**
     * Returns all registered entities in the ontology graph.
     */
    suspend fun getAllEntities(): Result<List<EntityNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val rows = d.run("?[name, category, description, at] := *entity{name, category, description, at} :order -at")
            val qualities = loadEntityQualities(d)
            val aliases = loadAliasesByCanonical(d)
            rows.map { r ->
                val name = r.rows[0].asString()
                val quality = qualities[name]
                EntityNode(
                    name = name,
                    category = EntityCategory.fromString(r.rows[1].asString()),
                    description = r.rows[2].asString(),
                    timestamp = r.rows[3].asDouble(),
                    aliases = aliases[name].orEmpty(),
                    confidence = quality?.confidence ?: 1.0,
                    evidence = quality?.evidence.orEmpty(),
                    sourceNoteId = quality?.noteId.orEmpty(),
                    status = quality?.status ?: KnowledgeStatus.ACCEPTED
                )
            }
        }
    }

    /**
     * Returns all relational edges.
     */
    suspend fun getAllEdges(): Result<List<RelationEdge>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val rows = d.run("?[source, relation, target, at] := *edge{source, relation, target, at} :order -at")
            val qualities = loadEdgeQualities(d)
            rows.map { r ->
                val source = r.rows[0].asString()
                val relation = RelationType.fromString(r.rows[1].asString())
                val target = r.rows[2].asString()
                val quality = qualities[edgeKey(source, relation.name, target)]
                RelationEdge(
                    source = source,
                    relation = relation,
                    target = target,
                    timestamp = r.rows[3].asDouble(),
                    confidence = quality?.confidence ?: 1.0,
                    evidence = quality?.evidence.orEmpty(),
                    sourceNoteId = quality?.noteId.orEmpty(),
                    status = quality?.status ?: KnowledgeStatus.ACCEPTED
                )
            }
        }
    }

    /** Returns accepted graph entity names linked to each note. */
    suspend fun getNoteEntityNames(): Result<Map<String, Set<String>>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            d.run("?[note_id, entity_name] := *note_entity{note_id, entity_name}")
                .groupBy(
                    keySelector = { it.rows[0].asString() },
                    valueTransform = { it.rows[1].asString() }
                )
                .mapValues { (_, names) -> names.toSet() }
        }
    }

    /**
     * Returns recent captured notes.
     */
    suspend fun getRecentNotes(limit: Int = 20): Result<List<NoteDocument>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val rows = d.run("?[id, title, content, at, source] := *note{id, title, content, at, source} :order -at :limit $limit")
            val modifiedTimes = loadModifiedTimes(d)
            val audioMetadata = loadAudioMetadata(d)
            rows.map { r ->
                val id = r.rows[0].asString()
                val createdAt = r.rows[3].asDouble()
                val audio = audioMetadata[id]
                NoteDocument(
                    id = id,
                    title = r.rows[1].asString(),
                    content = r.rows[2].asString(),
                    timestamp = createdAt,
                    source = r.rows[4].asString(),
                    modifiedTimestamp = modifiedTimes[id] ?: createdAt,
                    audioPath = audio?.path,
                    audioDurationMs = audio?.durationMs,
                    transcriptionStatus = audio?.status ?: TranscriptionStatus.NONE
                )
            }
        }
    }

    suspend fun getAllNotes(): Result<List<NoteDocument>> = getRecentNotes(limit = 100_000)

    /** Merges portable graph records without deleting any local rows. */
    suspend fun restoreGraph(
        entities: List<EntityNode>,
        edges: List<RelationEdge>,
        noteEntityNames: Map<String, Set<String>>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            entities.forEach { entity ->
                putEntityRecord(d, entity, entity.sourceNoteId)
            }
            edges.forEach { edge -> putEdge(d, edge) }
            noteEntityNames.forEach { (noteId, names) ->
                names.forEach { name -> linkNoteEntity(d, noteId, name) }
            }
            Unit
        }
    }

    suspend fun getNote(noteId: String): Result<NoteDocument?> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val rows = d.run(
                """
                ?[id, title, content, at, source] := *note{id, title, content, at, source}, id == "${esc(noteId)}"
                :limit 1
                """.trimIndent()
            )
            val row = rows.firstOrNull() ?: return@runCatching null
            val id = row.rows[0].asString()
            val createdAt = row.rows[3].asDouble()
            val audio = loadAudioMetadata(d)[id]
            NoteDocument(
                id = id,
                title = row.rows[1].asString(),
                content = row.rows[2].asString(),
                timestamp = createdAt,
                source = row.rows[4].asString(),
                modifiedTimestamp = loadModifiedTimes(d)[id] ?: createdAt,
                audioPath = audio?.path,
                audioDurationMs = audio?.durationMs,
                transcriptionStatus = audio?.status ?: TranscriptionStatus.NONE
            )
        }
    }

    suspend fun getAcceptedAliasMap(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            d.run(
                """
                ?[alias, canonical_name] := *entity_alias{alias, canonical_name, status},
                    status == "${KnowledgeStatus.ACCEPTED.name}"
                """.trimIndent()
            ).associate { row -> row.rows[0].asString() to row.rows[1].asString() }
        }
    }

    suspend fun putKnowledgeReviews(items: List<KnowledgeReviewItem>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            if (items.isEmpty()) return@runCatching Unit
            val rows = items.joinToString(", ") { item ->
                """["${esc(item.id)}", "${item.kind.name}", "${item.status.name}", "${esc(item.noteId)}", "${esc(item.subject)}", "${esc(item.candidate)}", "${esc(item.schemaType)}", "${esc(item.description)}", "${esc(JSONArray(item.aliases).toString())}", ${item.confidence.coerceIn(0.0, 1.0)}, "${esc(item.evidence)}", ${item.createdTimestamp}, ${item.updatedTimestamp}]"""
            }
            d.run(
                """
                ?[id, kind, status, note_id, subject, candidate, schema_type, description, aliases, confidence, evidence, created_at, updated_at] <- [$rows]
                :put review_item {id => kind, status, note_id, subject, candidate, schema_type, description, aliases, confidence, evidence, created_at, updated_at}
                """.trimIndent()
            )
            Unit
        }
    }

    suspend fun getKnowledgeReviews(
        status: ReviewStatus? = ReviewStatus.PENDING,
        limit: Int = 100
    ): Result<List<KnowledgeReviewItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val statusFilter = status?.let { ", status == \"${it.name}\"" }.orEmpty()
            d.run(
                """
                ?[id, kind, status, note_id, subject, candidate, schema_type, description, aliases, confidence, evidence, created_at, updated_at] :=
                    *review_item{id, kind, status, note_id, subject, candidate, schema_type, description, aliases, confidence, evidence, created_at, updated_at}$statusFilter
                :order -updated_at
                :limit ${limit.coerceIn(1, MAX_BACKUP_ITEMS)}
                """.trimIndent()
            ).map(::knowledgeReviewFromRow)
        }
    }

    suspend fun getAllKnowledgeReviews(): Result<List<KnowledgeReviewItem>> =
        getKnowledgeReviews(status = null, limit = MAX_BACKUP_ITEMS)

    suspend fun removePendingKnowledgeReviews(noteId: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val items = getKnowledgeReviews(null, 500).getOrThrow().filter {
                it.noteId == noteId && it.status == ReviewStatus.PENDING
            }
            items.forEach { removeReview(d, it.id) }
            items.size
        }
    }

    suspend fun resolveKnowledgeReview(itemId: String, accept: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val item = getKnowledgeReviews(null, 500).getOrThrow().firstOrNull { it.id == itemId }
                ?: error("Review item not found")
            if (item.status != ReviewStatus.PENDING) return@runCatching Unit

            if (accept) {
                when (item.kind) {
                    ReviewKind.ENTITY -> {
                        val entity = EntityNode(
                            name = item.subject,
                            category = EntityCategory.fromString(item.schemaType),
                            description = item.description,
                            aliases = item.aliases,
                            confidence = item.confidence,
                            evidence = item.evidence,
                            sourceNoteId = item.noteId
                        )
                        putEntity(d, entity, item.noteId)
                    }

                    ReviewKind.DUPLICATE -> {
                        val canonical = item.candidate.ifBlank { item.subject }
                        putAliases(d, canonical, listOf(item.subject) + item.aliases, item.confidence, item.noteId)
                        linkNoteEntity(d, item.noteId, canonical)
                    }

                    ReviewKind.RELATION -> {
                        val aliases = getAcceptedAliasMap().getOrDefault(emptyMap())
                            .mapKeys { normalizeEntityName(it.key) }
                        val source = aliases[normalizeEntityName(item.subject)] ?: item.subject
                        val target = aliases[normalizeEntityName(item.candidate)] ?: item.candidate
                        ensureEntityExists(d, source, item.noteId)
                        ensureEntityExists(d, target, item.noteId)
                        putEdge(
                            d,
                            RelationEdge(
                                source = source,
                                relation = RelationType.fromString(item.schemaType),
                                target = target,
                                confidence = item.confidence,
                                evidence = item.evidence,
                                sourceNoteId = item.noteId
                            )
                        )
                    }
                }
            }

            putKnowledgeReviews(
                listOf(
                    item.copy(
                        status = if (accept) ReviewStatus.ACCEPTED else ReviewStatus.REJECTED,
                        updatedTimestamp = now()
                    )
                )
            ).getOrThrow()
            Unit
        }
    }

    suspend fun removeResolvedKnowledgeReviews(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val items = getKnowledgeReviews(null, 500).getOrThrow()
                .filter { it.status != ReviewStatus.PENDING }
            items.forEach { removeReview(d, it.id) }
            items.size
        }
    }

    suspend fun syncOpenActionItems(noteId: String, proposals: List<ActionItem>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = db ?: error("Database not open")
                val existing = queryActionItems(d, noteId = noteId)
                val existingById = existing.associateBy(ActionItem::id)
                existing.filter { it.status == ActionStatus.OPEN }.forEach { removeAction(d, it.id) }
                val merged = proposals.mapNotNull { proposal ->
                    val previous = existingById[proposal.id]
                    when (previous?.status) {
                        ActionStatus.COMPLETED, ActionStatus.DISMISSED -> null
                        else -> proposal.copy(
                            createdTimestamp = previous?.createdTimestamp ?: proposal.createdTimestamp,
                            updatedTimestamp = now()
                        )
                    }
                }
                putActionItems(d, merged)
                Unit
            }
        }

    suspend fun getActionItems(
        status: ActionStatus? = null,
        limit: Int = 1_000
    ): Result<List<ActionItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            queryActionItems(d, status = status, limit = limit)
                .sortedWith(
                    compareBy<ActionItem> { it.dueTimestamp ?: Double.MAX_VALUE }
                        .thenByDescending(ActionItem::updatedTimestamp)
                )
        }
    }

    suspend fun updateActionStatus(id: String, status: ActionStatus): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = db ?: error("Database not open")
                val current = queryActionItems(d, id = id, limit = 1).firstOrNull()
                    ?: error("Action not found")
                putActionItems(d, listOf(current.copy(status = status, updatedTimestamp = now())))
                Unit
            }
        }

    suspend fun restoreActionItems(items: List<ActionItem>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            items.forEach { incoming ->
                val local = queryActionItems(d, id = incoming.id, limit = 1).firstOrNull()
                if (local == null || incoming.updatedTimestamp > local.updatedTimestamp) {
                    putActionItems(d, listOf(incoming))
                }
            }
            Unit
        }
    }

    private fun queryActionItems(
        d: CozoDb,
        status: ActionStatus? = null,
        noteId: String? = null,
        id: String? = null,
        limit: Int = MAX_BACKUP_ITEMS
    ): List<ActionItem> {
        val filters = buildList {
            status?.let { add("status == \"${it.name}\"") }
            noteId?.let { add("note_id == \"${esc(it)}\"") }
            id?.let { add("id == \"${esc(it)}\"") }
        }.joinToString(separator = ", ", prefix = if (status != null || noteId != null || id != null) ", " else "")
        return d.run(
            """
            ?[id, note_id, text, due_at, status, confidence, evidence, created_at, updated_at] :=
                *action_item{id, note_id, text, due_at, status, confidence, evidence, created_at, updated_at}$filters
            :order -updated_at
            :limit ${limit.coerceIn(1, MAX_BACKUP_ITEMS)}
            """.trimIndent()
        ).map(::actionItemFromRow)
    }

    private fun putActionItems(d: CozoDb, items: List<ActionItem>) {
        if (items.isEmpty()) return
        val rows = items.joinToString(", ") { item ->
            """["${esc(item.id)}", "${esc(item.noteId)}", "${esc(item.text)}", ${item.dueTimestamp ?: -1.0}, "${item.status.name}", ${item.confidence.coerceIn(0.0, 1.0)}, "${esc(item.evidence)}", ${item.createdTimestamp}, ${item.updatedTimestamp}]"""
        }
        d.run(
            """
            ?[id, note_id, text, due_at, status, confidence, evidence, created_at, updated_at] <- [$rows]
            :put action_item {id => note_id, text, due_at, status, confidence, evidence, created_at, updated_at}
            """.trimIndent()
        )
    }

    private fun removeAction(d: CozoDb, id: String) {
        d.run(
            """
            ?[id] <- [["${esc(id)}"]]
            :rm action_item {id}
            """.trimIndent()
        )
    }

    suspend fun enqueueProcessingJob(
        noteId: String,
        type: ProcessingJobType
    ): Result<ProcessingJob> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = getProcessingJob(ProcessingJob.stableId(noteId, type)).getOrThrow()
            val now = now()
            if (existing?.status == ProcessingJobStatus.QUEUED) return@runCatching existing
            if (existing?.status == ProcessingJobStatus.RUNNING) {
                val superseding = existing.copy(
                    status = ProcessingJobStatus.QUEUED,
                    progress = 0,
                    message = "Waiting for the latest note changes",
                    error = "",
                    updatedTimestamp = now
                )
                putProcessingJob(superseding).getOrThrow()
                return@runCatching superseding
            }
            val job = ProcessingJob(
                id = ProcessingJob.stableId(noteId, type),
                noteId = noteId,
                type = type,
                status = ProcessingJobStatus.QUEUED,
                message = "Waiting to ${type.label.lowercase()}",
                attempt = existing?.attempt ?: 0,
                createdTimestamp = now,
                updatedTimestamp = now
            )
            putProcessingJob(job).getOrThrow()
            job
        }
    }

    suspend fun putProcessingJob(job: ProcessingJob): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            d.run(
                """
                ?[id, note_id, type, status, progress, message, error, attempt, created_at, updated_at] <- [[
                    "${esc(job.id)}", "${esc(job.noteId)}", "${job.type.name}", "${job.status.name}",
                    ${job.progress.coerceIn(0, 100)}, "${esc(job.message)}", "${esc(job.error)}",
                    ${job.attempt}, ${job.createdTimestamp}, ${job.updatedTimestamp}
                ]]
                :put processing_job {id => note_id, type, status, progress, message, error, attempt, created_at, updated_at}
                """.trimIndent()
            )
            Unit
        }
    }

    suspend fun getProcessingJob(jobId: String): Result<ProcessingJob?> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            d.run(
                """
                ?[id, note_id, type, status, progress, message, error, attempt, created_at, updated_at] :=
                    *processing_job{id, note_id, type, status, progress, message, error, attempt, created_at, updated_at},
                    id == "${esc(jobId)}"
                :limit 1
                """.trimIndent()
            ).firstOrNull()?.let(::processingJobFromRow)
        }
    }

    suspend fun getProcessingJobs(limit: Int = 100): Result<List<ProcessingJob>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            d.run(
                """
                ?[id, note_id, type, status, progress, message, error, attempt, created_at, updated_at] :=
                    *processing_job{id, note_id, type, status, progress, message, error, attempt, created_at, updated_at}
                :order -updated_at
                :limit ${limit.coerceIn(1, 500)}
                """.trimIndent()
            ).map(::processingJobFromRow)
        }
    }

    suspend fun getNextQueuedProcessingJob(): Result<ProcessingJob?> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            d.run(
                """
                ?[id, note_id, type, status, progress, message, error, attempt, created_at, updated_at] :=
                    *processing_job{id, note_id, type, status, progress, message, error, attempt, created_at, updated_at},
                    status == "${ProcessingJobStatus.QUEUED.name}"
                :order created_at
                :limit 1
                """.trimIndent()
            ).firstOrNull()?.let(::processingJobFromRow)
        }
    }

    suspend fun recoverInterruptedProcessingJobs(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val interrupted = getProcessingJobs(500).getOrThrow()
                .filter { it.status == ProcessingJobStatus.RUNNING }
            interrupted.forEach { job ->
                putProcessingJob(
                    job.copy(
                        status = ProcessingJobStatus.QUEUED,
                        progress = 0,
                        message = "Resuming after interruption",
                        updatedTimestamp = now()
                    )
                ).getOrThrow()
            }
            interrupted.size
        }
    }

    suspend fun removeFinishedProcessingJobs(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val d = db ?: error("Database not open")
            val finished = getProcessingJobs(500).getOrThrow()
                .filter { !it.status.isActive }
            finished.forEach { job ->
                d.run(
                    """
                    ?[id] <- [["${esc(job.id)}"]]
                    :rm processing_job {id}
                    """.trimIndent()
                )
            }
            finished.size
        }
    }

    private fun processingJobFromRow(row: CozoDb.RelationRow): ProcessingJob = ProcessingJob(
        id = row.rows[0].asString(),
        noteId = row.rows[1].asString(),
        type = ProcessingJobType.fromString(row.rows[2].asString()),
        status = ProcessingJobStatus.fromString(row.rows[3].asString()),
        progress = row.rows[4].asInteger(),
        message = row.rows[5].asString(),
        error = row.rows[6].asString(),
        attempt = row.rows[7].asInteger(),
        createdTimestamp = row.rows[8].asDouble(),
        updatedTimestamp = row.rows[9].asDouble()
    )

    private fun actionItemFromRow(row: CozoDb.RelationRow): ActionItem {
        val dueAt = row.rows[3].asDouble()
        return ActionItem(
            id = row.rows[0].asString(),
            noteId = row.rows[1].asString(),
            text = row.rows[2].asString(),
            dueTimestamp = dueAt.takeIf { it >= 0.0 },
            status = ActionStatus.fromString(row.rows[4].asString()),
            confidence = row.rows[5].asDouble(),
            evidence = row.rows[6].asString(),
            createdTimestamp = row.rows[7].asDouble(),
            updatedTimestamp = row.rows[8].asDouble()
        )
    }

    private fun knowledgeReviewFromRow(row: CozoDb.RelationRow): KnowledgeReviewItem = KnowledgeReviewItem(
        id = row.rows[0].asString(),
        kind = ReviewKind.fromString(row.rows[1].asString()),
        status = ReviewStatus.fromString(row.rows[2].asString()),
        noteId = row.rows[3].asString(),
        subject = row.rows[4].asString(),
        candidate = row.rows[5].asString(),
        schemaType = row.rows[6].asString(),
        description = row.rows[7].asString(),
        aliases = runCatching {
            val array = JSONArray(row.rows[8].asString())
            List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList()),
        confidence = row.rows[9].asDouble(),
        evidence = row.rows[10].asString(),
        createdTimestamp = row.rows[11].asDouble(),
        updatedTimestamp = row.rows[12].asDouble()
    )

    private fun putEntity(d: CozoDb, entity: EntityNode, noteId: String) {
        putEntityRecord(d, entity, noteId)
        linkNoteEntity(d, noteId, entity.name)
    }

    private fun putEntityRecord(d: CozoDb, entity: EntityNode, noteId: String) {
        d.run(
            """
            ?[name, category, description, at] <- [["${esc(entity.name)}", "${entity.category.name}", "${esc(entity.description)}", ${entity.timestamp}]]
            :put entity {name => category, description, at}
            """.trimIndent()
        )
        d.run(
            """
            ?[name, confidence, status, evidence, note_id] <- [["${esc(entity.name)}", ${entity.confidence.coerceIn(0.0, 1.0)}, "${KnowledgeStatus.ACCEPTED.name}", "${esc(entity.evidence)}", "${esc(noteId)}"]]
            :put entity_quality {name => confidence, status, evidence, note_id}
            """.trimIndent()
        )
        putAliases(d, entity.name, entity.aliases, entity.confidence, noteId)
    }

    private fun putEdge(d: CozoDb, edge: RelationEdge) {
        d.run(
            """
            ?[source, relation, target, at] <- [["${esc(edge.source)}", "${edge.relation.name}", "${esc(edge.target)}", ${edge.timestamp}]]
            :put edge {source, relation, target => at}
            """.trimIndent()
        )
        d.run(
            """
            ?[source, relation, target, confidence, status, evidence, note_id] <- [["${esc(edge.source)}", "${edge.relation.name}", "${esc(edge.target)}", ${edge.confidence.coerceIn(0.0, 1.0)}, "${KnowledgeStatus.ACCEPTED.name}", "${esc(edge.evidence)}", "${esc(edge.sourceNoteId)}"]]
            :put edge_quality {source, relation, target => confidence, status, evidence, note_id}
            """.trimIndent()
        )
    }

    private fun ensureEntityExists(d: CozoDb, name: String, noteId: String) {
        if (name.isBlank()) return
        val exists = d.run(
            """
            ?[name] := *entity{name}, name == "${esc(name)}"
            :limit 1
            """.trimIndent()
        ).isNotEmpty()
        if (!exists) {
            putEntity(
                d,
                EntityNode(name = name, category = EntityCategory.CONCEPT, sourceNoteId = noteId),
                noteId
            )
        } else {
            linkNoteEntity(d, noteId, name)
        }
    }

    private fun linkNoteEntity(d: CozoDb, noteId: String, entityName: String) {
        d.run(
            """
            ?[note_id, entity_name, at] <- [["${esc(noteId)}", "${esc(entityName)}", ${now()}]]
            :put note_entity {note_id, entity_name => at}
            """.trimIndent()
        )
    }

    private fun putAliases(
        d: CozoDb,
        canonicalName: String,
        aliases: List<String>,
        confidence: Double,
        noteId: String
    ) {
        aliases.map { it.trim() }
            .filter { it.isNotBlank() && normalizeEntityName(it) != normalizeEntityName(canonicalName) }
            .distinctBy(::normalizeEntityName)
            .forEach { alias ->
                d.run(
                    """
                    ?[alias, canonical_name, confidence, status, note_id, at] <- [["${esc(alias)}", "${esc(canonicalName)}", ${confidence.coerceIn(0.0, 1.0)}, "${KnowledgeStatus.ACCEPTED.name}", "${esc(noteId)}", ${now()}]]
                    :put entity_alias {alias => canonical_name, confidence, status, note_id, at}
                    """.trimIndent()
                )
            }
    }

    private fun removeReview(d: CozoDb, itemId: String) {
        d.run(
            """
            ?[id] <- [["${esc(itemId)}"]]
            :rm review_item {id}
            """.trimIndent()
        )
    }

    private fun loadEntityQualities(d: CozoDb): Map<String, EntityQuality> = runCatching {
        d.run("?[name, confidence, status, evidence, note_id] := *entity_quality{name, confidence, status, evidence, note_id}")
            .associate { row ->
                row.rows[0].asString() to EntityQuality(
                    confidence = row.rows[1].asDouble(),
                    status = KnowledgeStatus.fromString(row.rows[2].asString()),
                    evidence = row.rows[3].asString(),
                    noteId = row.rows[4].asString()
                )
            }
    }.getOrDefault(emptyMap())

    private fun loadAliasesByCanonical(d: CozoDb): Map<String, List<String>> = runCatching {
        d.run(
            """
            ?[alias, canonical_name] := *entity_alias{alias, canonical_name, status},
                status == "${KnowledgeStatus.ACCEPTED.name}"
            """.trimIndent()
        ).groupBy(
            keySelector = { it.rows[1].asString() },
            valueTransform = { it.rows[0].asString() }
        )
    }.getOrDefault(emptyMap())

    private fun loadEdgeQualities(d: CozoDb): Map<String, EdgeQuality> = runCatching {
        d.run("?[source, relation, target, confidence, status, evidence, note_id] := *edge_quality{source, relation, target, confidence, status, evidence, note_id}")
            .associate { row ->
                edgeKey(row.rows[0].asString(), row.rows[1].asString(), row.rows[2].asString()) to EdgeQuality(
                    confidence = row.rows[3].asDouble(),
                    status = KnowledgeStatus.fromString(row.rows[4].asString()),
                    evidence = row.rows[5].asString(),
                    noteId = row.rows[6].asString()
                )
            }
    }.getOrDefault(emptyMap())

    private fun edgeKey(source: String, relation: String, target: String): String =
        "${normalizeEntityName(source)}|${relation.uppercase()}|${normalizeEntityName(target)}"

    private fun normalizeEntityName(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun loadModifiedTimes(d: CozoDb): Map<String, Double> =
        runCatching {
            d.run("?[note_id, modified_at] := *note_meta{note_id, modified_at}")
                .associate { row -> row.rows[0].asString() to row.rows[1].asDouble() }
        }.getOrDefault(emptyMap())

    private fun loadAudioMetadata(d: CozoDb): Map<String, StoredAudio> =
        runCatching {
            d.run("?[note_id, path, duration_ms, status] := *note_audio{note_id, path, duration_ms, status}")
                .associate { row ->
                    row.rows[0].asString() to StoredAudio(
                        path = row.rows[1].asString(),
                        durationMs = row.rows[2].asInteger().toLong(),
                        status = TranscriptionStatus.fromString(row.rows[3].asString())
                    )
                }
        }.getOrDefault(emptyMap())

    suspend fun getStats(): Map<String, Int> = withContext(Dispatchers.IO) {
        val d = db ?: return@withContext emptyMap()
        val noteCount = runCatching { d.run("?[count(id)] := *note{id}").firstOrNull()?.rows?.get(0)?.asInteger() ?: 0 }.getOrDefault(0)
        val entityCount = runCatching { d.run("?[count(name)] := *entity{name}").firstOrNull()?.rows?.get(0)?.asInteger() ?: 0 }.getOrDefault(0)
        val edgeCount = runCatching { d.run("?[count(source)] := *edge{source, relation, target}").firstOrNull()?.rows?.get(0)?.asInteger() ?: 0 }.getOrDefault(0)
        val reviewCount = runCatching {
            d.run("?[count(id)] := *review_item{id, status}, status == \"${ReviewStatus.PENDING.name}\"")
                .firstOrNull()?.rows?.get(0)?.asInteger() ?: 0
        }.getOrDefault(0)
        mapOf("notes" to noteCount, "entities" to entityCount, "edges" to edgeCount, "reviews" to reviewCount)
    }

    fun close() {
        runCatching { db?.close() }
        db = null
    }

    companion object {
        private const val TAG = "BrainStore"
        private const val MAX_BACKUP_ITEMS = 100_000
        const val EMBEDDING_DIM = 256

        private val SCHEMA = listOf(
            ":create note {id: String => title: String, content: String, at: Float, source: String}",
            ":create note_meta {note_id: String => modified_at: Float}",
            ":create note_audio {note_id: String => path: String, duration_ms: Int, status: String}",
            ":create processing_job {id: String => note_id: String, type: String, status: String, progress: Int, message: String, error: String, attempt: Int, created_at: Float, updated_at: Float}",
            ":create entity {name: String => category: String, description: String, at: Float}",
            ":create entity_quality {name: String => confidence: Float, status: String, evidence: String, note_id: String}",
            ":create entity_alias {alias: String => canonical_name: String, confidence: Float, status: String, note_id: String, at: Float}",
            ":create edge {source: String, relation: String, target: String => at: Float}",
            ":create edge_quality {source: String, relation: String, target: String => confidence: Float, status: String, evidence: String, note_id: String}",
            ":create note_entity {note_id: String, entity_name: String => at: Float}",
            ":create review_item {id: String => kind: String, status: String, note_id: String, subject: String, candidate: String, schema_type: String, description: String, aliases: String, confidence: Float, evidence: String, created_at: Float, updated_at: Float}",
            ":create action_item {id: String => note_id: String, text: String, due_at: Float, status: String, confidence: Float, evidence: String, created_at: Float, updated_at: Float}",
            ":create note_vector {note_id: String => embedding: <F32; 256>, at: Float}",
            "::hnsw create note_vector:vec_idx {dim: 256, m: 24, ef_construction: 64, fields: [embedding], distance: Cosine}"
        )
    }
}
