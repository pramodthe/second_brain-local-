package com.secondbrain.app.data

object KnowledgeFeedbackKey {
    fun of(kind: ReviewKind, subject: String, candidate: String, schemaType: String): String =
        listOf(kind.name, normalize(subject), normalize(candidate), schemaType.uppercase()).joinToString("|")

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
