package com.secondbrain.app.data

data class TrashedNote(
    val note: NoteDocument,
    val deletedTimestamp: Double
)

data class PermanentDeleteReport(
    val noteId: String,
    val actionsRemoved: Int,
    val reviewsRemoved: Int,
    val jobsRemoved: Int,
    val unsupportedEntitiesRemoved: Int,
    val unsupportedEdgesRemoved: Int,
    val recordingRemoved: Boolean
)
