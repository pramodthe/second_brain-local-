package com.secondbrain.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cozodb.CozoDb
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
                    """str_includes(lowercase(source), "$a") or str_includes(lowercase(target), "$a")"""
                }

                // Query 1: Edges connected to anchor entities
                val edgeQuery = """
                    ?[source, relation, target, at] := *edge{source, relation, target, at},
                        ($filterClause)
                    :limit 30
                """.trimIndent()
                val edgeRows = d.run(edgeQuery)
                val edges = edgeRows.map { r ->
                    RelationEdge(
                        source = r.rows[0].asString(),
                        relation = RelationType.fromString(r.rows[1].asString()),
                        target = r.rows[2].asString(),
                        timestamp = r.rows[3].asDouble()
                    )
                }

                // Query 2: Entity details
                val touchedNames = (entityNames + edges.map { it.source } + edges.map { it.target }).distinct()
                val entityFilter = touchedNames.map { esc(it.lowercase().trim()) }.filter { it.isNotBlank() }
                    .joinToString(" or ") { n ->
                        """str_includes(lowercase(name), "$n")"""
                    }
                val entityQuery = """
                    ?[name, category, description, at] := *entity{name, category, description, at},
                        ($entityFilter)
                """.trimIndent()
                val entityRows = d.run(entityQuery)
                val entities = entityRows.map { r ->
                    EntityNode(
                        name = r.rows[0].asString(),
                        category = EntityCategory.fromString(r.rows[1].asString()),
                        description = r.rows[2].asString(),
                        timestamp = r.rows[3].asDouble()
                    )
                }

                // Query 3: Notes referencing these entities
                val noteFilter = anchors.joinToString(" or ") { a ->
                    """str_includes(lowercase(entity_name), "$a")"""
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
            rows.map { r ->
                EntityNode(
                    name = r.rows[0].asString(),
                    category = EntityCategory.fromString(r.rows[1].asString()),
                    description = r.rows[2].asString(),
                    timestamp = r.rows[3].asDouble()
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
            rows.map { r ->
                RelationEdge(
                    source = r.rows[0].asString(),
                    relation = RelationType.fromString(r.rows[1].asString()),
                    target = r.rows[2].asString(),
                    timestamp = r.rows[3].asDouble()
                )
            }
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
        mapOf("notes" to noteCount, "entities" to entityCount, "edges" to edgeCount)
    }

    fun close() {
        runCatching { db?.close() }
        db = null
    }

    companion object {
        private const val TAG = "BrainStore"
        const val EMBEDDING_DIM = 256

        private val SCHEMA = listOf(
            ":create note {id: String => title: String, content: String, at: Float, source: String}",
            ":create note_meta {note_id: String => modified_at: Float}",
            ":create note_audio {note_id: String => path: String, duration_ms: Int, status: String}",
            ":create entity {name: String => category: String, description: String, at: Float}",
            ":create edge {source: String, relation: String, target: String => at: Float}",
            ":create note_entity {note_id: String, entity_name: String => at: Float}",
            ":create note_vector {note_id: String => embedding: <F32; 256>, at: Float}",
            "::hnsw create note_vector:vec_idx {dim: 256, m: 24, ef_construction: 64, fields: [embedding], distance: Cosine}"
        )
    }
}
