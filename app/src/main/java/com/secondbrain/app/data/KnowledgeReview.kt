package com.secondbrain.app.data

enum class ReviewKind(val label: String) {
    ENTITY("Entity"),
    RELATION("Relationship"),
    DUPLICATE("Possible duplicate");

    companion object {
        fun fromString(value: String): ReviewKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ENTITY
    }
}

enum class ReviewStatus {
    PENDING,
    ACCEPTED,
    REJECTED;

    companion object {
        fun fromString(value: String): ReviewStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}

data class KnowledgeReviewItem(
    val id: String,
    val kind: ReviewKind,
    val status: ReviewStatus = ReviewStatus.PENDING,
    val noteId: String,
    val subject: String,
    val candidate: String = "",
    val schemaType: String,
    val description: String = "",
    val aliases: List<String> = emptyList(),
    val confidence: Double,
    val evidence: String,
    val createdTimestamp: Double = System.currentTimeMillis() / 1000.0,
    val updatedTimestamp: Double = createdTimestamp
)

data class KnowledgeResolution(
    val accepted: ExtractedKnowledge,
    val reviews: List<KnowledgeReviewItem>
)
