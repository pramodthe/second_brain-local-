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
    val reviews: List<KnowledgeReviewItem>,
    val actions: List<ActionItem> = emptyList()
)

data class BackupReport(
    val notes: Int,
    val entities: Int,
    val edges: Int,
    val recordings: Int,
    val actions: Int = 0
)

data class RestoreReport(
    val importedNotes: Int,
    val skippedNewerNotes: Int,
    val entities: Int,
    val edges: Int,
    val recordings: Int,
    val actions: Int = 0
)
