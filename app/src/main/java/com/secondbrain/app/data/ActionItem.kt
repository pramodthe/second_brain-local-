package com.secondbrain.app.data

import java.nio.charset.StandardCharsets
import java.util.UUID

enum class ActionStatus {
    OPEN,
    COMPLETED,
    DISMISSED;

    companion object {
        fun fromString(value: String): ActionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OPEN
    }
}

data class ActionCandidate(
    val text: String,
    val dueDate: String? = null,
    val evidence: String,
    val confidence: Double
)

data class ActionItem(
    val id: String,
    val noteId: String,
    val text: String,
    val dueTimestamp: Double? = null,
    val status: ActionStatus = ActionStatus.OPEN,
    val confidence: Double,
    val evidence: String,
    val createdTimestamp: Double = System.currentTimeMillis() / 1_000.0,
    val updatedTimestamp: Double = createdTimestamp
) {
    companion object {
        fun stableId(noteId: String, text: String): String {
            val normalized = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            val uuid = UUID.nameUUIDFromBytes("$noteId|$normalized".toByteArray(StandardCharsets.UTF_8))
            return "action-$uuid"
        }

        fun manual(text: String, dueTimestamp: Double? = null): ActionItem = ActionItem(
            id = "action-${UUID.randomUUID()}",
            noteId = "",
            text = text.trim(),
            dueTimestamp = dueTimestamp,
            confidence = 1.0,
            evidence = "Created directly"
        )
    }
}
