package com.secondbrain.app.data

/**
 * Core personal ontology definitions for the Second Brain.
 * Every piece of captured knowledge is mapped onto these explicit types.
 */
enum class EntityCategory(val label: String, val description: String) {
    CONCEPT("Concept", "An abstract idea, principle, or architectural pattern"),
    PROJECT("Project", "An active goal, codebase, product, or initiative"),
    RESOURCE("Resource", "A source material: book, academic paper, article, or URL"),
    PERSON("Person", "An author, mentor, collaborator, or subject-matter expert"),
    DECISION("Decision", "A concrete architectural or personal conclusion made at a point in time"),
    INSIGHT("Insight", "A key takeaway, realization, or rule of thumb");

    companion object {
        fun fromString(s: String): EntityCategory =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) || it.label.equals(s, ignoreCase = true) }
                ?: CONCEPT
    }
}

enum class RelationType(val label: String) {
    DEPENDS_ON("DEPENDS_ON"),
    CONTRADICTS("CONTRADICTS"),
    EXTENDS("EXTENDS"),
    MENTIONS("MENTIONS"),
    SUPERSEDES("SUPERSEDES"),
    DERIVED_FROM("DERIVED_FROM"),
    USES_CONCEPT("USES_CONCEPT"),
    AUTHORED_BY("AUTHORED_BY"),
    RELATED_TO("RELATED_TO");

    companion object {
        fun fromString(s: String): RelationType =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) || it.label.equals(s, ignoreCase = true) }
                ?: RELATED_TO
    }
}

enum class TranscriptionStatus {
    NONE,
    PENDING,
    DOWNLOADING,
    TRANSCRIBING,
    COMPLETE,
    FAILED;

    companion object {
        fun fromString(value: String): TranscriptionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NONE
    }
}

enum class KnowledgeStatus {
    ACCEPTED,
    REJECTED;

    companion object {
        fun fromString(value: String): KnowledgeStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ACCEPTED
    }
}

data class EntityNode(
    val name: String,
    val category: EntityCategory,
    val description: String = "",
    val timestamp: Double = System.currentTimeMillis() / 1000.0,
    val aliases: List<String> = emptyList(),
    val confidence: Double = 1.0,
    val evidence: String = "",
    val sourceNoteId: String = "",
    val status: KnowledgeStatus = KnowledgeStatus.ACCEPTED
)

data class RelationEdge(
    val source: String,
    val relation: RelationType,
    val target: String,
    val timestamp: Double = System.currentTimeMillis() / 1000.0,
    val confidence: Double = 1.0,
    val evidence: String = "",
    val sourceNoteId: String = "",
    val status: KnowledgeStatus = KnowledgeStatus.ACCEPTED
)

data class NoteDocument(
    val id: String,
    val title: String,
    val content: String,
    val timestamp: Double = System.currentTimeMillis() / 1000.0,
    val source: String = "manual",
    val modifiedTimestamp: Double = timestamp,
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.NONE
)

data class ExtractedKnowledge(
    val entities: List<EntityNode>,
    val relations: List<RelationEdge>,
    val actions: List<ActionCandidate> = emptyList()
)

data class RetrievedSource(
    val number: Int,
    val note: NoteDocument,
    val score: Double,
    val excerpt: String,
    val reasons: List<String> = emptyList()
)

data class SubgraphContext(
    val anchorEntities: List<EntityNode>,
    val connectedEdges: List<RelationEdge>,
    val relatedNotes: List<NoteDocument>,
    val rankedSources: List<RetrievedSource> = emptyList(),
    val timelineNotes: List<NoteDocument> = emptyList()
)
