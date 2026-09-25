package com.secondbrain.app.domain

import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.ExtractedKnowledge
import com.secondbrain.app.data.KnowledgeResolution
import com.secondbrain.app.data.KnowledgeReviewItem
import com.secondbrain.app.data.KnowledgeStatus
import com.secondbrain.app.data.KnowledgeFeedbackKey
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.ReviewKind
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.max

class KnowledgeResolver(
    private val autoAcceptThreshold: Double = 0.80,
    private val duplicateThreshold: Double = 0.84
) {

    fun resolve(
        note: NoteDocument,
        raw: ExtractedKnowledge,
        existing: List<EntityNode>,
        acceptedAliases: Map<String, String>,
        rejectedRules: Set<String> = emptySet()
    ): KnowledgeResolution {
        val canonicalByKey = existing.associateBy { normalizeName(it.name) }.toMutableMap()
        val aliasByKey = acceptedAliases.mapKeys { normalizeName(it.key) }
        val acceptedEntities = mutableListOf<EntityNode>()
        val reviews = mutableListOf<KnowledgeReviewItem>()
        val nameMapping = mutableMapOf<String, String>()

        raw.entities
            .filter { it.name.isNotBlank() }
            .distinctBy { normalizeName(it.name) }
            .forEach { proposal ->
                val cleanName = proposal.name.trim()
                val key = normalizeName(cleanName)
                val exactCanonical = canonicalByKey[key]?.name ?: aliasByKey[key]
                val (evidence, evidenceVerified) = verifiedEvidence(note.content, proposal.evidence, cleanName)
                val confidence = proposal.confidence.coerceIn(0.0, 1.0)
                    .let { if (evidenceVerified) it else minOf(it, 0.65) }
                val cleanAliases = proposal.aliases
                    .map { it.trim() }
                    .filter { it.isNotBlank() && normalizeName(it) != key }
                    .distinctBy(::normalizeName)

                if (exactCanonical != null) {
                    val existingEntity = existing.firstOrNull {
                        normalizeName(it.name) == normalizeName(exactCanonical)
                    }
                    val merged = (existingEntity ?: proposal.copy(name = exactCanonical)).copy(
                        aliases = ((existingEntity?.aliases ?: emptyList()) + cleanAliases).distinctBy(::normalizeName),
                        confidence = max(existingEntity?.confidence ?: 0.0, confidence),
                        evidence = evidence,
                        sourceNoteId = note.id,
                        status = KnowledgeStatus.ACCEPTED
                    )
                    acceptedEntities += merged
                    nameMapping[key] = merged.name
                    return@forEach
                }

                val duplicate = existing
                    .map { it to nameSimilarity(cleanName, it.name) }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= duplicateThreshold }

                if (duplicate != null) {
                    val fingerprint = KnowledgeFeedbackKey.of(
                        ReviewKind.DUPLICATE,
                        cleanName,
                        duplicate.first.name,
                        proposal.category.name
                    )
                    if (fingerprint !in rejectedRules) {
                        reviews += reviewItem(
                            kind = ReviewKind.DUPLICATE,
                            note = note,
                            subject = cleanName,
                            candidate = duplicate.first.name,
                            schemaType = proposal.category.name,
                            description = proposal.description,
                            aliases = cleanAliases,
                            confidence = max(confidence, duplicate.second),
                            evidence = evidence
                        )
                    }
                } else if (
                    KnowledgeFeedbackKey.of(
                        ReviewKind.ENTITY,
                        cleanName,
                        "",
                        proposal.category.name
                    ) in rejectedRules
                ) {
                    return@forEach
                } else if (confidence >= autoAcceptThreshold) {
                    val accepted = proposal.copy(
                        name = cleanName,
                        aliases = cleanAliases.filterNot { alias ->
                            aliasByKey[normalizeName(alias)]?.let { normalizeName(it) != key } == true
                        },
                        confidence = confidence,
                        evidence = evidence,
                        sourceNoteId = note.id,
                        status = KnowledgeStatus.ACCEPTED
                    )
                    acceptedEntities += accepted
                    canonicalByKey[key] = accepted
                    nameMapping[key] = accepted.name
                } else {
                    reviews += reviewItem(
                        kind = ReviewKind.ENTITY,
                        note = note,
                        subject = cleanName,
                        schemaType = proposal.category.name,
                        description = proposal.description,
                        aliases = cleanAliases,
                        confidence = confidence,
                        evidence = evidence
                    )
                }
            }

        fun resolveEndpoint(value: String): String? {
            val key = normalizeName(value)
            return nameMapping[key] ?: canonicalByKey[key]?.name ?: aliasByKey[key]
        }

        val acceptedRelations = raw.relations
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .distinctBy { "${normalizeName(it.source)}|${it.relation.name}|${normalizeName(it.target)}" }
            .mapNotNull { proposal ->
                val source = resolveEndpoint(proposal.source)
                val target = resolveEndpoint(proposal.target)
                val (evidence, evidenceVerified) = verifiedEvidence(
                    note.content,
                    proposal.evidence,
                    proposal.source
                )
                val confidence = proposal.confidence.coerceIn(0.0, 1.0)
                    .let { if (evidenceVerified) it else minOf(it, 0.65) }
                val reviewSource = source ?: proposal.source.trim()
                val reviewTarget = target ?: proposal.target.trim()
                val rejected = KnowledgeFeedbackKey.of(
                    ReviewKind.RELATION,
                    reviewSource,
                    reviewTarget,
                    proposal.relation.name
                ) in rejectedRules

                if (rejected) return@mapNotNull null

                if (
                    source != null && target != null &&
                    normalizeName(source) != normalizeName(target) &&
                    confidence >= autoAcceptThreshold
                ) {
                    proposal.copy(
                        source = source,
                        target = target,
                        confidence = confidence,
                        evidence = evidence,
                        sourceNoteId = note.id,
                        status = KnowledgeStatus.ACCEPTED
                    )
                } else {
                    reviews += reviewItem(
                        kind = ReviewKind.RELATION,
                        note = note,
                        subject = reviewSource,
                        candidate = reviewTarget,
                        schemaType = proposal.relation.name,
                        confidence = confidence,
                        evidence = evidence
                    )
                    null
                }
            }

        return KnowledgeResolution(
            accepted = ExtractedKnowledge(
                acceptedEntities.distinctBy { normalizeName(it.name) },
                acceptedRelations,
                raw.actions
            ),
            reviews = reviews.distinctBy { it.id }
        )
    }

    internal fun nameSimilarity(first: String, second: String): Double {
        val a = normalizeName(first)
        val b = normalizeName(second)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0
        if (minOf(compactA.length, compactB.length) >= 5 &&
            (compactA.contains(compactB) || compactB.contains(compactA))
        ) return 0.88

        val tokenA = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tokenB = b.split(' ').filter { it.isNotBlank() }.toSet()
        val union = tokenA union tokenB
        val jaccard = if (union.isEmpty()) 0.0 else (tokenA intersect tokenB).size.toDouble() / union.size
        val edit = 1.0 - levenshtein(compactA, compactB).toDouble() / max(compactA.length, compactB.length)
        return max(jaccard, edit)
    }

    private fun reviewItem(
        kind: ReviewKind,
        note: NoteDocument,
        subject: String,
        candidate: String = "",
        schemaType: String,
        description: String = "",
        aliases: List<String> = emptyList(),
        confidence: Double,
        evidence: String
    ): KnowledgeReviewItem {
        val stableInput = listOf(kind.name, note.id, subject, candidate, schemaType)
            .joinToString("|") { normalizeName(it) }
        val stableId = UUID.nameUUIDFromBytes(stableInput.toByteArray(StandardCharsets.UTF_8))
        return KnowledgeReviewItem(
            id = "review-$stableId",
            kind = kind,
            noteId = note.id,
            subject = subject,
            candidate = candidate,
            schemaType = schemaType,
            description = description,
            aliases = aliases,
            confidence = confidence,
            evidence = evidence
        )
    }

    private fun verifiedEvidence(text: String, proposed: String, entityName: String): Pair<String, Boolean> {
        val clean = proposed.trim().trim('"', '\'', '“', '”')
        if (clean.isNotBlank() && text.contains(clean, ignoreCase = true)) return clean to true
        val sentence = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .firstOrNull { it.contains(entityName, ignoreCase = true) }
            ?.trim()
            ?.take(220)
        return (sentence ?: text.trim().take(220)) to false
    }

    companion object {
        fun normalizeName(value: String): String = value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        private fun levenshtein(first: String, second: String): Int {
            if (first.isEmpty()) return second.length
            if (second.isEmpty()) return first.length
            var previous = IntArray(second.length + 1) { it }
            for (i in first.indices) {
                val current = IntArray(second.length + 1)
                current[0] = i + 1
                for (j in second.indices) {
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + if (first[i] == second[j]) 0 else 1
                    )
                }
                previous = current
            }
            return previous[second.length]
        }
    }
}
