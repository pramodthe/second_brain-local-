package com.secondbrain.app.domain

import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.ActionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActionExtractorTest {
    private val date = LocalDate.of(2026, 9, 25)

    @Test
    fun extractsExplicitTasksAndDatesWithoutGuessingOrdinaryText() {
        val text = """
            We discussed the launch plan.
            - [ ] Call Maya tomorrow
            Reminder: submit the release by next Monday
            This line is only background information.
        """.trimIndent()

        val actions = ActionExtractor.deterministicCandidates(text, date)

        assertEquals(2, actions.size)
        assertEquals("2026-09-26", actions[0].dueDate)
        assertEquals("2026-09-28", actions[1].dueDate)
        assertTrue(actions.none { it.text.contains("background") })
    }

    @Test
    fun resolvesNamedDatesAcrossYearBoundary() {
        assertEquals(
            LocalDate.of(2027, 1, 3),
            ActionExtractor.findDueDate("TODO: renew on 3 January", LocalDate.of(2026, 12, 28))
        )
        assertNull(ActionExtractor.findDueDate("No date here", date))
    }

    @Test
    fun requiresVerifiableEvidenceBeforeCreatingAnAction() {
        val note = NoteDocument("n1", "Plan", "TODO: call Maya tomorrow", timestamp = 1_790_294_400.0)
        val candidates = ActionExtractor.deterministicCandidates(note.content, date)
        val items = ActionExtractor.toActionItems(note, candidates, ZoneId.of("Australia/Sydney"))

        assertEquals(1, items.size)
        assertEquals("call Maya tomorrow", items.single().text)
        assertEquals(0.98, items.single().confidence, 0.001)
    }

    @Test
    fun rejectsHallucinatedActionTextEvenWhenTheEvidenceQuoteExists() {
        val note = NoteDocument("n1", "Plan", "TODO: call Maya tomorrow", timestamp = 1_790_294_400.0)
        val candidate = ActionCandidate(
            text = "Book flights to Melbourne",
            dueDate = "2026-09-26",
            evidence = "TODO: call Maya tomorrow",
            confidence = 0.99
        )

        assertTrue(ActionExtractor.toActionItems(note, listOf(candidate)).isEmpty())
    }

    @Test
    fun ignoresADueDateThatIsNotPresentInTheEvidence() {
        val note = NoteDocument("n1", "Plan", "TODO: call Maya", timestamp = 1_790_294_400.0)
        val candidate = ActionCandidate(
            text = "call Maya",
            dueDate = "2026-10-01",
            evidence = "TODO: call Maya",
            confidence = 0.99
        )

        val item = ActionExtractor.toActionItems(note, listOf(candidate)).single()

        assertNull(item.dueTimestamp)
    }
}
