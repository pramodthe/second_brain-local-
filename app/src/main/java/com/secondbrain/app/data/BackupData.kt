package com.secondbrain.app.data

data class BackupNote(
    val note: NoteDocument,
    val audioEntry: String? = null
)

data class BackupPayload(
    val formatVersion: Int = 1,
    val createdTimestamp: Double = System.currentTimeMillis() / 1_000.0,
    val notes: List<BackupNote>,
    val entities: List<EntityNode>,
    val edges: List<RelationEdge>,
    val noteEntityNames: Map<String, Set<String>>,
    val reviews: List<KnowledgeReviewItem>
)

data class BackupReport(
    val notes: Int,
    val entities: Int,
    val edges: Int,
    val recordings: Int
)

data class RestoreReport(
    val importedNotes: Int,
    val skippedNewerNotes: Int,
    val entities: Int,
    val edges: Int,
    val recordings: Int
)
