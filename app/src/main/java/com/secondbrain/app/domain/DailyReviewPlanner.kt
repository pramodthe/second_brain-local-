package com.secondbrain.app.domain

import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.ActionStatus
import com.secondbrain.app.data.NoteDocument
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ResurfacedMemory(
    val note: NoteDocument,
    val reason: String,
    val score: Double
)

data class DailyReview(
    val date: LocalDate,
    val summary: String,
    val capturedToday: Int,
    val overdueCount: Int,
    val dueTodayCount: Int,
    val completedTodayCount: Int,
    val focusActions: List<ActionItem>,
    val memories: List<ResurfacedMemory>
) {
    companion object {
        fun empty(date: LocalDate = LocalDate.now()): DailyReview = DailyReview(
            date = date,
            summary = "Capture a thought or action to begin today's review.",
            capturedToday = 0,
            overdueCount = 0,
            dueTodayCount = 0,
            completedTodayCount = 0,
            focusActions = emptyList(),
            memories = emptyList()
        )
    }
}

/** Builds an instant, explainable daily plan without waiting for the on-device LLM. */
object DailyReviewPlanner {
    fun plan(
        notes: List<NoteDocument>,
        actions: List<ActionItem>,
        noteEntities: Map<String, Set<String>>,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        memoryLimit: Int = 3
    ): DailyReview {
        val openActions = actions.filter { it.status == ActionStatus.OPEN }
        val overdue = openActions.filter { dueDate(it, zoneId)?.isBefore(today) == true }
        val dueToday = openActions.filter { dueDate(it, zoneId) == today }
        val completedToday = actions.count {
            it.status == ActionStatus.COMPLETED && localDate(it.updatedTimestamp, zoneId) == today
        }
        val todaysNotes = notes.filter { localDate(it.timestamp, zoneId) == today }
        val focusActions = openActions.sortedWith(
            compareBy<ActionItem> { actionPriority(it, today, zoneId) }
                .thenBy { it.dueTimestamp ?: Double.MAX_VALUE }
                .thenByDescending(ActionItem::updatedTimestamp)
        ).take(3)

        val activeSourceIds = openActions.mapNotNullTo(mutableSetOf()) { it.noteId.takeIf(String::isNotBlank) }
        val contextNoteIds = (todaysNotes.map(NoteDocument::id) + focusActions.map(ActionItem::noteId))
            .filter(String::isNotBlank)
            .toSet()
        val contextEntities = contextNoteIds.flatMapTo(mutableSetOf()) { noteEntities[it].orEmpty() }
        val entityFrequency = noteEntities.values.flatten().groupingBy { it }.eachCount()

        val memories = notes.asSequence()
            .filter { localDate(it.timestamp, zoneId).isBefore(today) }
            .filter { it.title.isNotBlank() || it.content.isNotBlank() }
            .map { note ->
                rankMemory(
                    note = note,
                    entities = noteEntities[note.id].orEmpty(),
                    contextEntities = contextEntities,
                    entityFrequency = entityFrequency,
                    supportsOpenAction = note.id in activeSourceIds,
                    today = today,
                    zoneId = zoneId
                )
            }
            .sortedWith(compareByDescending<ResurfacedMemory>(ResurfacedMemory::score).thenByDescending { it.note.timestamp })
            .take(memoryLimit.coerceAtLeast(0))
            .toList()

        return DailyReview(
            date = today,
            summary = summary(overdue.size, dueToday.size, completedToday, todaysNotes.size, memories.size),
            capturedToday = todaysNotes.size,
            overdueCount = overdue.size,
            dueTodayCount = dueToday.size,
            completedTodayCount = completedToday,
            focusActions = focusActions,
            memories = memories
        )
    }

    private fun rankMemory(
        note: NoteDocument,
        entities: Set<String>,
        contextEntities: Set<String>,
        entityFrequency: Map<String, Int>,
        supportsOpenAction: Boolean,
        today: LocalDate,
        zoneId: ZoneId
    ): ResurfacedMemory {
        val savedDate = localDate(note.timestamp, zoneId)
        val ageDays = ChronoUnit.DAYS.between(savedDate, today).coerceAtLeast(1)
        val sharedEntities = entities.intersect(contextEntities)
        val connectedEntities = entities.count { entityFrequency.getOrDefault(it, 0) > 1 }
        val recencyScore = when {
            ageDays <= 7 -> 3.0
            ageDays <= 30 -> 2.0
            ageDays <= 90 -> 1.2
            ageDays <= 365 -> 0.5
            else -> 0.0
        }
        val anniversary = savedDate.month == today.month && savedDate.dayOfMonth == today.dayOfMonth
        val rotation = ((note.id.hashCode() xor today.dayOfYear) and Int.MAX_VALUE) % 100 / 200.0
        val score = sharedEntities.size * 6.0 +
            (if (supportsOpenAction) 5.0 else 0.0) +
            connectedEntities.coerceAtMost(4) * 0.8 +
            (if (anniversary) 2.0 else 0.0) +
            recencyScore + rotation

        val reason = when {
            sharedEntities.isNotEmpty() -> "Connects to ${sharedEntities.take(2).joinToString(" and ")} in your current work"
            supportsOpenAction -> "Contains the source for an open action"
            connectedEntities > 0 -> "Links ${connectedEntities.coerceAtMost(4)} recurring ${if (connectedEntities == 1) "topic" else "topics"}"
            anniversary -> "Captured on this date in an earlier year"
            ageDays <= 30 -> "A recent note worth keeping active"
            else -> "A quieter memory you have not seen recently"
        }
        return ResurfacedMemory(note, reason, score)
    }

    private fun summary(
        overdue: Int,
        dueToday: Int,
        completedToday: Int,
        capturedToday: Int,
        memories: Int
    ): String = when {
        overdue > 0 -> "Start with $overdue overdue ${word(overdue, "action")}. $dueToday more ${word(dueToday, "action")} due today."
        dueToday > 0 -> "$dueToday ${word(dueToday, "action")} due today. You have completed $completedToday so far."
        completedToday > 0 -> "You completed $completedToday ${word(completedToday, "action")} today. Nothing else is due."
        capturedToday > 0 -> "Nothing is due. You captured $capturedToday new ${word(capturedToday, "memory")} today."
        memories > 0 -> "Nothing is due. Revisit a connected memory or capture what is on your mind."
        else -> "Nothing is due. Capture a thought or create an action to begin."
    }

    private fun word(count: Int, singular: String): String = if (count == 1) singular else "${singular}s"

    private fun actionPriority(action: ActionItem, today: LocalDate, zoneId: ZoneId): Int {
        val due = dueDate(action, zoneId) ?: return 3
        return when {
            due.isBefore(today) -> 0
            due == today -> 1
            else -> 2
        }
    }

    private fun dueDate(action: ActionItem, zoneId: ZoneId): LocalDate? =
        action.dueTimestamp?.let { localDate(it, zoneId) }

    private fun localDate(timestamp: Double, zoneId: ZoneId): LocalDate =
        Instant.ofEpochSecond(timestamp.toLong()).atZone(zoneId).toLocalDate()
}
