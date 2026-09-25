package com.secondbrain.app.domain

import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.ActionStatus
import com.secondbrain.app.data.NoteDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyReviewPlannerTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun `focus actions put overdue and today before later and undated work`() {
        val actions = listOf(
            action("none", null),
            action("future", today.plusDays(2)),
            action("today", today),
            action("overdue", today.minusDays(1))
        )

        val review = DailyReviewPlanner.plan(emptyList(), actions, emptyMap(), today, zone)

        assertEquals(listOf("overdue", "today", "future"), review.focusActions.map(ActionItem::id))
        assertEquals(1, review.overdueCount)
        assertEquals(1, review.dueTodayCount)
    }

    @Test
    fun `memory sharing a current entity ranks before an unrelated recent note`() {
        val current = note("current", today, "Working on Jarvis")
        val connected = note("connected", today.minusDays(60), "Earlier Jarvis design")
        val unrelated = note("unrelated", today.minusDays(2), "Shopping list")

        val review = DailyReviewPlanner.plan(
            notes = listOf(current, connected, unrelated),
            actions = emptyList(),
            noteEntities = mapOf(
                current.id to setOf("Jarvis"),
                connected.id to setOf("Jarvis", "Nexa"),
                unrelated.id to setOf("Groceries")
            ),
            today = today,
            zoneId = zone
        )

        assertEquals("connected", review.memories.first().note.id)
        assertTrue(review.memories.first().reason.contains("Jarvis"))
    }

    @Test
    fun `completed today and captured today are reflected in the review`() {
        val completedAt = today.atTime(15, 30).atZone(zone).toEpochSecond().toDouble()
        val completed = action("done", today).copy(
            status = ActionStatus.COMPLETED,
            updatedTimestamp = completedAt
        )

        val review = DailyReviewPlanner.plan(
            notes = listOf(note("today-note", today, "A fresh thought")),
            actions = listOf(completed),
            noteEntities = emptyMap(),
            today = today,
            zoneId = zone
        )

        assertEquals(1, review.completedTodayCount)
        assertEquals(1, review.capturedToday)
        assertTrue(review.summary.contains("completed 1 action"))
    }

    private fun action(id: String, due: LocalDate?): ActionItem = ActionItem(
        id = id,
        noteId = "",
        text = id,
        dueTimestamp = due?.atStartOfDay(zone)?.toEpochSecond()?.toDouble(),
        confidence = 1.0,
        evidence = "test"
    )

    private fun note(id: String, date: LocalDate, content: String): NoteDocument = NoteDocument(
        id = id,
        title = id,
        content = content,
        timestamp = date.atTime(10, 0).atZone(zone).toEpochSecond().toDouble()
    )
}
