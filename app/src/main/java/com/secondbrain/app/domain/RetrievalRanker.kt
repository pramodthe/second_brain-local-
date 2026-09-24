package com.secondbrain.app.domain

import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RetrievedSource
import kotlin.math.exp

internal data class NoteScoreInput(
    val note: NoteDocument,
    val vectorDistance: Double? = null,
    val entityNames: Set<String> = emptySet()
)

/** Pure, deterministic hybrid reranker shared by search, related notes, and chat retrieval. */
internal object RetrievalRanker {

    fun rank(
        query: String,
        inputs: List<NoteScoreInput>,
        queryEntities: Set<String> = emptySet(),
        limit: Int,
        excludeNoteId: String? = null,
        nowSeconds: Double = System.currentTimeMillis() / 1_000.0
    ): List<RetrievedSource> {
        val normalizedQuery = normalize(query)
        val queryTokens = tokens(normalizedQuery)
        if (normalizedQuery.isBlank() || queryTokens.isEmpty()) return emptyList()
        val temporalIntent = queryTokens.any { it in TEMPORAL_TERMS }
        val excludedFingerprint = excludeNoteId
            ?.let { excludedId -> inputs.firstOrNull { it.note.id == excludedId } }
            ?.let { contentFingerprint(it.note) }

        return inputs.asSequence()
            .filter {
                it.note.id != excludeNoteId &&
                    (excludedFingerprint == null || contentFingerprint(it.note) != excludedFingerprint)
            }
            .map { input ->
                val title = normalize(input.note.title)
                val content = normalize(input.note.content)
                val titleTokens = tokens(title)
                val contentTokens = tokens(content)
                val titleCoverage = coverage(queryTokens, titleTokens)
                val contentCoverage = coverage(queryTokens, contentTokens)
                val exactPhrase = when {
                    normalizedQuery.length >= 3 && title.contains(normalizedQuery) -> 1.0
                    normalizedQuery.length >= 3 && content.contains(normalizedQuery) -> 0.82
                    else -> 0.0
                }
                val lexical = maxOf(exactPhrase, titleCoverage * 0.65 + contentCoverage * 0.35)
                val vector = input.vectorDistance
                    ?.let { (1.0 - it.coerceIn(0.0, 2.0) / 2.0).coerceIn(0.0, 1.0) }
                    ?: 0.0
                val normalizedNoteEntities = input.entityNames.mapTo(mutableSetOf(), ::normalize)
                val entityOverlap = if (queryEntities.isEmpty()) 0.0 else {
                    val normalizedQueryEntities = queryEntities.mapTo(mutableSetOf(), ::normalize)
                    (normalizedNoteEntities intersect normalizedQueryEntities).size.toDouble() /
                        normalizedQueryEntities.size.coerceAtLeast(1)
                }
                val ageDays = ((nowSeconds - input.note.timestamp).coerceAtLeast(0.0) / 86_400.0)
                val recency = exp(-ageDays / 180.0)
                val score = if (temporalIntent) {
                    vector * 0.35 + lexical * 0.25 + entityOverlap * 0.15 + recency * 0.25
                } else {
                    vector * 0.50 + lexical * 0.32 + entityOverlap * 0.15 + recency * 0.03
                }.coerceIn(0.0, 1.0)

                val reasons = buildList {
                    if (exactPhrase > 0.0) add("exact phrase")
                    if (titleCoverage >= 0.5) add("title match")
                    if (contentCoverage >= 0.5) add("text match")
                    if (entityOverlap > 0.0) add("shared ${if (entityOverlap == 1.0) "entities" else "entity"}")
                    if (vector >= 0.55) add("semantic match")
                }.distinct()

                Triple(input.note, score, reasons)
            }
            .filter { (_, score, reasons) ->
                score >= MIN_SCORE && (
                    reasons.any { it != "semantic match" } || score >= SEMANTIC_ONLY_MIN_SCORE
                    )
            }
            .sortedWith(compareByDescending<Triple<NoteDocument, Double, List<String>>> { it.second }
                .thenByDescending { it.first.timestamp })
            .distinctBy { (note) -> contentFingerprint(note) }
            .take(limit.coerceAtLeast(1))
            .mapIndexed { index, (note, score, reasons) ->
                RetrievedSource(
                    number = index + 1,
                    note = note,
                    score = score,
                    excerpt = excerpt(note, queryTokens),
                    reasons = reasons.ifEmpty { listOf("semantic match") }
                )
            }
            .toList()
    }

    internal fun excerpt(note: NoteDocument, queryTokens: Set<String>, maxLength: Int = 240): String {
        val text = note.content.trim().ifBlank { note.title.trim() }
        if (text.length <= maxLength) return text
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val best = sentences.maxByOrNull { sentence ->
            val sentenceTokens = tokens(normalize(sentence))
            (sentenceTokens intersect queryTokens).size
        }.orEmpty()
        if (best.isNotBlank()) return best.take(maxLength).trimEnd() + if (best.length > maxLength) "…" else ""
        return text.take(maxLength).trimEnd() + "…"
    }

    internal fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun contentFingerprint(note: NoteDocument): String =
        normalize("${note.title}\n${note.content}").take(2_000)

    private fun tokens(value: String): Set<String> = value
        .split(' ')
        .asSequence()
        .filter { it.length > 1 && it !in STOP_WORDS }
        .toSet()

    private fun coverage(queryTokens: Set<String>, documentTokens: Set<String>): Double =
        if (queryTokens.isEmpty()) 0.0
        else (queryTokens intersect documentTokens).size.toDouble() / queryTokens.size

    private const val MIN_SCORE = 0.18
    private const val SEMANTIC_ONLY_MIN_SCORE = 0.40
    private val STOP_WORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "i", "in",
        "is", "it", "my", "of", "on", "or", "the", "to", "was", "what", "when", "where", "which", "why", "with"
    )
    private val TEMPORAL_TERMS = setOf(
        "recent", "recently", "latest", "newest", "today", "yesterday", "week", "month", "timeline", "history"
    )
}
