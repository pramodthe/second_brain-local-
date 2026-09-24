package com.secondbrain.app.domain

import com.secondbrain.app.data.NoteDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalRankerTest {

    private val now = 2_000_000.0

    @Test
    fun exactAndSemanticEvidenceRanksAheadOfUnrelatedNotes() {
        val matching = note("matching", "Apollo decision", "Project Apollo uses event sourcing for audit trails.")
        val unrelated = note("unrelated", "Shopping", "Buy milk and coffee.")

        val ranked = RetrievalRanker.rank(
            query = "Apollo event sourcing",
            inputs = listOf(
                NoteScoreInput(unrelated, vectorDistance = 0.72),
                NoteScoreInput(matching, vectorDistance = 0.18)
            ),
            limit = 2,
            nowSeconds = now
        )

        assertEquals("matching", ranked.first().note.id)
        assertTrue("text match" in ranked.first().reasons)
        assertEquals(1, ranked.first().number)
    }

    @Test
    fun sharedGraphEntitiesBoostRelatedNotes() {
        val linked = note("linked", "Architecture", "A local architecture note.")
        val plain = note("plain", "Architecture", "A local architecture note.")

        val ranked = RetrievalRanker.rank(
            query = "architecture",
            inputs = listOf(
                NoteScoreInput(plain, vectorDistance = 0.4),
                NoteScoreInput(linked, vectorDistance = 0.4, entityNames = setOf("Project Apollo"))
            ),
            queryEntities = setOf("Project Apollo"),
            limit = 2,
            nowSeconds = now
        )

        assertEquals("linked", ranked.first().note.id)
        assertTrue(ranked.first().reasons.any { it.startsWith("shared") })
    }

    @Test
    fun excerptSelectsThePassageContainingQueryTerms() {
        val note = note(
            "source",
            "Long note",
            "This opening sentence is background and has no useful detail. " +
                "Event sourcing preserves every audit event for Project Apollo. " +
                "This closing sentence is unrelated and intentionally verbose."
        )

        val excerpt = RetrievalRanker.excerpt(note, setOf("event", "sourcing", "apollo"), maxLength = 100)

        assertTrue(excerpt.contains("Event sourcing"))
        assertFalse(excerpt.contains("opening"))
    }

    @Test
    fun relatedSearchExcludesTheSourceNote() {
        val source = note("source", "CozoDB", "Local graph database")
        val duplicateSource = source.copy(id = "source-copy", timestamp = source.timestamp - 1.0)
        val related = note("related", "Graph", "CozoDB is a local graph database")

        val ranked = RetrievalRanker.rank(
            query = "CozoDB local graph database",
            inputs = listOf(
                NoteScoreInput(source, vectorDistance = 0.0),
                NoteScoreInput(duplicateSource, vectorDistance = 0.0),
                NoteScoreInput(related, vectorDistance = 0.2)
            ),
            limit = 5,
            excludeNoteId = source.id,
            nowSeconds = now
        )

        assertEquals(listOf("related"), ranked.map { it.note.id })
    }

    @Test
    fun temporalQuestionsFavorRecentMemories() {
        val recent = note("recent", "New learning", "A note about retrieval")
        val old = note("old", "Old learning", "A note about retrieval").copy(
            timestamp = now - 86_400.0 * 900
        )

        val ranked = RetrievalRanker.rank(
            query = "what did I learn recently",
            inputs = listOf(
                NoteScoreInput(old, vectorDistance = 0.25),
                NoteScoreInput(recent, vectorDistance = 0.25)
            ),
            limit = 2,
            nowSeconds = now
        )

        assertEquals("recent", ranked.first().note.id)
    }

    @Test
    fun duplicateNoteContentUsesOnlyOneCitationSlot() {
        val first = note("first", "Same title", "Same captured text")
        val duplicate = first.copy(id = "duplicate", timestamp = first.timestamp - 10.0)

        val ranked = RetrievalRanker.rank(
            query = "same captured text",
            inputs = listOf(
                NoteScoreInput(first, vectorDistance = 0.1),
                NoteScoreInput(duplicate, vectorDistance = 0.1)
            ),
            limit = 5,
            nowSeconds = now
        )

        assertEquals(1, ranked.size)
        assertEquals("first", ranked.single().note.id)
    }

    @Test
    fun weakSemanticOnlyMatchesDoNotBecomeCitations() {
        val unrelated = note("unrelated", "Groceries", "Buy tea and fruit")

        val ranked = RetrievalRanker.rank(
            query = "event sourcing architecture",
            inputs = listOf(NoteScoreInput(unrelated, vectorDistance = 0.7)),
            limit = 5,
            nowSeconds = now
        )

        assertTrue(ranked.isEmpty())
    }

    private fun note(id: String, title: String, content: String) = NoteDocument(
        id = id,
        title = title,
        content = content,
        timestamp = now - 60.0
    )
}
