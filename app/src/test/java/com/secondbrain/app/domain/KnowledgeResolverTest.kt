package com.secondbrain.app.domain

import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.ExtractedKnowledge
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.KnowledgeFeedbackKey
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.RelationType
import com.secondbrain.app.data.ReviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeResolverTest {

    private val resolver = KnowledgeResolver()

    @Test
    fun acceptsExplicitHighConfidenceKnowledgeWithEvidence() {
        val note = note("Jarvis uses Qwen for private answers.")
        val raw = ExtractedKnowledge(
            entities = listOf(
                entity("Jarvis", "Jarvis uses Qwen for private answers."),
                entity("Qwen", "Jarvis uses Qwen for private answers.")
            ),
            relations = listOf(
                RelationEdge(
                    source = "Jarvis",
                    relation = RelationType.USES_CONCEPT,
                    target = "Qwen",
                    confidence = 0.94,
                    evidence = "Jarvis uses Qwen"
                )
            )
        )

        val result = resolver.resolve(note, raw, emptyList(), emptyMap())

        assertEquals(2, result.accepted.entities.size)
        assertEquals(1, result.accepted.relations.size)
        assertTrue(result.reviews.isEmpty())
        assertTrue(result.accepted.entities.all { it.sourceNoteId == note.id })
    }

    @Test
    fun sendsUnsupportedClaimsToReviewEvenWhenModelConfidenceIsHigh() {
        val note = note("A short note with no named product.")
        val raw = ExtractedKnowledge(
            entities = listOf(entity("Imaginary Platform", "not an exact quote", confidence = 0.99)),
            relations = emptyList()
        )

        val result = resolver.resolve(note, raw, emptyList(), emptyMap())

        assertTrue(result.accepted.entities.isEmpty())
        assertEquals(ReviewKind.ENTITY, result.reviews.single().kind)
        assertEquals(0.65, result.reviews.single().confidence, 0.001)
    }

    @Test
    fun proposesNearMatchesAsDuplicatesInsteadOfCreatingParallelNodes() {
        val note = note("Qwen3.5 is the local model.")
        val existing = listOf(
            EntityNode(name = "Qwen 3.5", category = EntityCategory.CONCEPT)
        )
        val raw = ExtractedKnowledge(
            entities = listOf(entity("Qwen3.5", "Qwen3.5 is the local model.")),
            relations = emptyList()
        )

        val result = resolver.resolve(note, raw, existing, emptyMap())

        assertTrue(result.accepted.entities.isEmpty())
        val review = result.reviews.single()
        assertEquals(ReviewKind.DUPLICATE, review.kind)
        assertEquals("Qwen 3.5", review.candidate)
    }

    @Test
    fun acceptedAliasResolvesToCanonicalEntity() {
        val note = note("SB helps organize knowledge.")
        val existing = listOf(
            EntityNode(name = "Second Brain", category = EntityCategory.PROJECT)
        )
        val raw = ExtractedKnowledge(
            entities = listOf(entity("SB", "SB helps organize knowledge.")),
            relations = emptyList()
        )

        val result = resolver.resolve(
            note = note,
            raw = raw,
            existing = existing,
            acceptedAliases = mapOf("SB" to "Second Brain")
        )

        assertEquals("Second Brain", result.accepted.entities.single().name)
        assertTrue(result.reviews.isEmpty())
    }

    @Test
    fun rejectedEntityRulePreventsTheSameSuggestionFromReturning() {
        val note = note("Jarvis is a project.")
        val proposal = EntityNode(
            name = "Jarvis",
            category = EntityCategory.PROJECT,
            confidence = 0.95,
            evidence = "Jarvis is a project"
        )
        val rejected = setOf(
            KnowledgeFeedbackKey.of(ReviewKind.ENTITY, "Jarvis", "", EntityCategory.PROJECT.name)
        )

        val result = resolver.resolve(
            note = note,
            raw = ExtractedKnowledge(listOf(proposal), emptyList()),
            existing = emptyList(),
            acceptedAliases = emptyMap(),
            rejectedRules = rejected
        )

        assertTrue(result.accepted.entities.isEmpty())
        assertTrue(result.reviews.isEmpty())
    }

    private fun note(content: String) = NoteDocument(
        id = "note-test",
        title = "Test",
        content = content,
        timestamp = 1.0
    )

    private fun entity(name: String, evidence: String, confidence: Double = 0.92) = EntityNode(
        name = name,
        category = EntityCategory.CONCEPT,
        confidence = confidence,
        evidence = evidence
    )
}
