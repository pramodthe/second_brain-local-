package com.secondbrain.app.backup

import com.secondbrain.app.data.BackupNote
import com.secondbrain.app.data.BackupPayload
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.KnowledgeReviewItem
import com.secondbrain.app.data.KnowledgeStatus
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.RelationType
import com.secondbrain.app.data.ReviewKind
import com.secondbrain.app.data.ReviewStatus
import com.secondbrain.app.data.TranscriptionStatus
import org.json.JSONArray
import org.json.JSONObject

internal object BackupJson {
    fun encode(payload: BackupPayload): String = JSONObject().apply {
        put("formatVersion", payload.formatVersion)
        put("createdTimestamp", payload.createdTimestamp)
        put("notes", JSONArray().apply { payload.notes.forEach { put(encodeNote(it)) } })
        put("entities", JSONArray().apply { payload.entities.forEach { put(encodeEntity(it)) } })
        put("edges", JSONArray().apply { payload.edges.forEach { put(encodeEdge(it)) } })
        put("noteEntities", JSONArray().apply {
            payload.noteEntityNames.forEach { (noteId, names) ->
                names.forEach { name -> put(JSONObject().put("noteId", noteId).put("entityName", name)) }
            }
        })
        put("reviews", JSONArray().apply { payload.reviews.forEach { put(encodeReview(it)) } })
    }.toString()

    fun decode(json: String): BackupPayload {
        val root = JSONObject(json)
        val version = root.getInt("formatVersion")
        require(version == 1) { "Backup format $version is not supported" }
        val noteEntities = mutableMapOf<String, MutableSet<String>>()
        root.optJSONArray("noteEntities")?.objects()?.forEach { item ->
            noteEntities.getOrPut(item.getString("noteId")) { mutableSetOf() }
                .add(item.getString("entityName"))
        }
        return BackupPayload(
            formatVersion = version,
            createdTimestamp = root.getDouble("createdTimestamp"),
            notes = root.getJSONArray("notes").objects().map(::decodeNote),
            entities = root.getJSONArray("entities").objects().map(::decodeEntity),
            edges = root.getJSONArray("edges").objects().map(::decodeEdge),
            noteEntityNames = noteEntities,
            reviews = root.optJSONArray("reviews")?.objects()?.map(::decodeReview).orEmpty()
        )
    }

    private fun encodeNote(item: BackupNote) = JSONObject().apply {
        val note = item.note
        put("id", note.id)
        put("title", note.title)
        put("content", note.content)
        put("timestamp", note.timestamp)
        put("source", note.source)
        put("modifiedTimestamp", note.modifiedTimestamp)
        put("audioDurationMs", note.audioDurationMs ?: JSONObject.NULL)
        put("transcriptionStatus", note.transcriptionStatus.name)
        put("audioEntry", item.audioEntry ?: JSONObject.NULL)
    }

    private fun decodeNote(value: JSONObject): BackupNote = BackupNote(
        note = NoteDocument(
            id = value.getString("id"),
            title = value.optString("title"),
            content = value.optString("content"),
            timestamp = value.getDouble("timestamp"),
            source = value.optString("source", "backup"),
            modifiedTimestamp = value.optDouble("modifiedTimestamp", value.getDouble("timestamp")),
            audioPath = null,
            audioDurationMs = value.optNullableLong("audioDurationMs"),
            transcriptionStatus = TranscriptionStatus.fromString(value.optString("transcriptionStatus"))
        ),
        audioEntry = value.optNullableString("audioEntry")
    )

    private fun encodeEntity(entity: EntityNode) = JSONObject().apply {
        put("name", entity.name)
        put("category", entity.category.name)
        put("description", entity.description)
        put("timestamp", entity.timestamp)
        put("aliases", JSONArray(entity.aliases))
        put("confidence", entity.confidence)
        put("evidence", entity.evidence)
        put("sourceNoteId", entity.sourceNoteId)
        put("status", entity.status.name)
    }

    private fun decodeEntity(value: JSONObject) = EntityNode(
        name = value.getString("name"),
        category = EntityCategory.fromString(value.optString("category")),
        description = value.optString("description"),
        timestamp = value.optDouble("timestamp", 0.0),
        aliases = value.optJSONArray("aliases")?.strings().orEmpty(),
        confidence = value.optDouble("confidence", 1.0),
        evidence = value.optString("evidence"),
        sourceNoteId = value.optString("sourceNoteId"),
        status = KnowledgeStatus.fromString(value.optString("status"))
    )

    private fun encodeEdge(edge: RelationEdge) = JSONObject().apply {
        put("source", edge.source)
        put("relation", edge.relation.name)
        put("target", edge.target)
        put("timestamp", edge.timestamp)
        put("confidence", edge.confidence)
        put("evidence", edge.evidence)
        put("sourceNoteId", edge.sourceNoteId)
        put("status", edge.status.name)
    }

    private fun decodeEdge(value: JSONObject) = RelationEdge(
        source = value.getString("source"),
        relation = RelationType.fromString(value.optString("relation")),
        target = value.getString("target"),
        timestamp = value.optDouble("timestamp", 0.0),
        confidence = value.optDouble("confidence", 1.0),
        evidence = value.optString("evidence"),
        sourceNoteId = value.optString("sourceNoteId"),
        status = KnowledgeStatus.fromString(value.optString("status"))
    )

    private fun encodeReview(item: KnowledgeReviewItem) = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind.name)
        put("status", item.status.name)
        put("noteId", item.noteId)
        put("subject", item.subject)
        put("candidate", item.candidate)
        put("schemaType", item.schemaType)
        put("description", item.description)
        put("aliases", JSONArray(item.aliases))
        put("confidence", item.confidence)
        put("evidence", item.evidence)
        put("createdTimestamp", item.createdTimestamp)
        put("updatedTimestamp", item.updatedTimestamp)
    }

    private fun decodeReview(value: JSONObject) = KnowledgeReviewItem(
        id = value.getString("id"),
        kind = ReviewKind.fromString(value.optString("kind")),
        status = ReviewStatus.fromString(value.optString("status")),
        noteId = value.getString("noteId"),
        subject = value.getString("subject"),
        candidate = value.optString("candidate"),
        schemaType = value.optString("schemaType"),
        description = value.optString("description"),
        aliases = value.optJSONArray("aliases")?.strings().orEmpty(),
        confidence = value.optDouble("confidence", 0.0),
        evidence = value.optString("evidence"),
        createdTimestamp = value.optDouble("createdTimestamp", 0.0),
        updatedTimestamp = value.optDouble("updatedTimestamp", 0.0)
    )

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = List(length()) { optString(it) }.filter { it.isNotBlank() }
    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else optLong(key)
}
