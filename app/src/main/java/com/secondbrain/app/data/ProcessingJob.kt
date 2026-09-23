package com.secondbrain.app.data

enum class ProcessingJobType(val label: String) {
    TRANSCRIBE("Transcribe voice note"),
    ORGANIZE("Organize knowledge");

    companion object {
        fun fromString(value: String): ProcessingJobType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ORGANIZE
    }
}

enum class ProcessingJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val canRetry: Boolean get() = this == FAILED || this == CANCELLED

    companion object {
        fun fromString(value: String): ProcessingJobStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: QUEUED
    }
}

data class ProcessingJob(
    val id: String,
    val noteId: String,
    val type: ProcessingJobType,
    val status: ProcessingJobStatus,
    val progress: Int = 0,
    val message: String = "Waiting",
    val error: String = "",
    val attempt: Int = 0,
    val createdTimestamp: Double = System.currentTimeMillis() / 1000.0,
    val updatedTimestamp: Double = createdTimestamp
) {
    companion object {
        fun stableId(noteId: String, type: ProcessingJobType): String =
            "${type.name.lowercase()}-$noteId"
    }
}
