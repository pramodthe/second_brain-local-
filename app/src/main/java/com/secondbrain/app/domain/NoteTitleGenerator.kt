package com.secondbrain.app.domain

/** Produces a useful local title without delaying durable note capture for AI work. */
object NoteTitleGenerator {
    private const val MAX_TITLE_LENGTH = 72

    fun fromContent(content: String): String {
        val firstThought = content
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .replace(Regex("^(?:#{1,6}\\s*|[-*+]\\s+(?:\\[[ xX]]\\s*)?|\\d+[.)]\\s+)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (firstThought.isBlank()) return ""
        if (firstThought.length <= MAX_TITLE_LENGTH) return firstThought

        val shortened = firstThought.take(MAX_TITLE_LENGTH + 1)
        val sentenceEnd = shortened.indexOfAny(charArrayOf('.', '!', '?'))
        if (sentenceEnd in 12 until MAX_TITLE_LENGTH) {
            return shortened.take(sentenceEnd + 1)
        }
        val wordEnd = shortened.take(MAX_TITLE_LENGTH).lastIndexOf(' ')
        return if (wordEnd >= 24) shortened.take(wordEnd).trimEnd() + "…"
        else shortened.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
    }
}
