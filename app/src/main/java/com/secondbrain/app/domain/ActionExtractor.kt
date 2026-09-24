package com.secondbrain.app.domain

import com.secondbrain.app.data.ActionCandidate
import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.NoteDocument
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object ActionExtractor {
    private val prefix = Regex(
        "^(?:[-*+]\\s+)?(?:\\[[ ]?]\\s*)?(?:todo|task|reminder)\\s*[:\\-]?\\s*|" +
            "^(?:[-*+]\\s+)?(?:remind me to|i need to|need to|don't forget to|do not forget to)\\s+",
        RegexOption.IGNORE_CASE
    )
    private val checklist = Regex("^\\s*[-*+]\\s+\\[ ]\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun deterministicCandidates(
        text: String,
        referenceDate: LocalDate = LocalDate.now()
    ): List<ActionCandidate> = text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull { line ->
            val checklistText = checklist.matchEntire(line)?.groupValues?.get(1)?.trim()
            val prefixed = prefix.find(line)?.let { line.removeRange(it.range).trim() }
            val actionText = checklistText ?: prefixed ?: return@mapNotNull null
            actionText.takeIf(String::isNotBlank)?.let {
                ActionCandidate(
                    text = it.take(240),
                    dueDate = findDueDate(line, referenceDate)?.toString(),
                    evidence = line.take(280),
                    confidence = 0.98
                )
            }
        }
        .distinctBy { normalize(it.text) }
        .toList()

    fun toActionItems(
        note: NoteDocument,
        candidates: List<ActionCandidate>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<ActionItem> = candidates
        .asSequence()
        .map { candidate ->
            val cleanText = candidate.text.trim().replace(Regex("\\s+"), " ").take(240)
            val cleanEvidence = candidate.evidence.trim().trim('"', '\'', '“', '”')
            val evidenceVerified = cleanEvidence.isNotBlank() &&
                note.content.contains(cleanEvidence, ignoreCase = true)
            val textGrounded = evidenceVerified && isTextGrounded(cleanText, cleanEvidence)
            val confidence = candidate.confidence.coerceIn(0.0, 1.0)
                .let { if (textGrounded) it else minOf(it, 0.55) }
            val dueDate = findDueDate(cleanEvidence, localDate(note.timestamp, zoneId))
                ?: parseIsoDate(candidate.dueDate)?.takeIf { cleanEvidence.contains(it.toString()) }
            ActionItem(
                id = ActionItem.stableId(note.id, cleanText),
                noteId = note.id,
                text = cleanText,
                dueTimestamp = dueDate?.atStartOfDay(zoneId)?.toEpochSecond()?.toDouble(),
                confidence = confidence,
                evidence = cleanEvidence
            )
        }
        .filter { it.text.isNotBlank() && it.confidence >= 0.72 }
        .distinctBy(ActionItem::id)
        .toList()

    internal fun findDueDate(text: String, referenceDate: LocalDate): LocalDate? {
        val lower = text.lowercase()
        if (Regex("\\btoday\\b").containsMatchIn(lower)) return referenceDate
        if (Regex("\\btomorrow\\b").containsMatchIn(lower)) return referenceDate.plusDays(1)

        ISO_DATE.find(text)?.value?.let(::parseIsoDate)?.let { return it }
        DAY_MONTH.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val monthName = match.groupValues[2]
            val year = match.groupValues[3].toIntOrNull() ?: referenceDate.year
            parseNamedMonth(day, monthName, year)?.let { parsed ->
                return if (match.groupValues[3].isBlank() && parsed.isBefore(referenceDate)) {
                    parsed.plusYears(1)
                } else parsed
            }
        }

        WEEKDAY.find(lower)?.let { match ->
            val day = DayOfWeek.valueOf(match.groupValues[2].uppercase())
            val strictlyNext = match.groupValues[1].isNotBlank()
            val adjuster = if (strictlyNext) TemporalAdjusters.next(day) else TemporalAdjusters.nextOrSame(day)
            return referenceDate.with(adjuster)
        }
        return null
    }

    private fun localDate(timestamp: Double, zoneId: ZoneId): LocalDate =
        java.time.Instant.ofEpochSecond(timestamp.toLong()).atZone(zoneId).toLocalDate()

    private fun parseIsoDate(value: String?): LocalDate? = try {
        value?.takeIf(String::isNotBlank)?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseNamedMonth(day: Int, monthName: String, year: Int): LocalDate? =
        MONTH_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                LocalDate.parse(
                    "$day $monthName $year",
                    DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                )
            }.getOrNull()
        }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun isTextGrounded(action: String, evidence: String): Boolean {
        val evidenceTokens = TOKEN.findAll(evidence.lowercase()).map { it.value }.toSet()
        val actionTokens = TOKEN.findAll(action.lowercase())
            .map { it.value }
            .filter { it !in GROUNDING_STOP_WORDS }
            .toList()
        if (actionTokens.isEmpty()) return false
        val supported = actionTokens.count(evidenceTokens::contains)
        return supported.toDouble() / actionTokens.size >= 0.75
    }

    private val ISO_DATE = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private val DAY_MONTH = Regex(
        "\\b(\\d{1,2})\\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
            "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)(?:\\s+(\\d{4}))?\\b",
        RegexOption.IGNORE_CASE
    )
    private val WEEKDAY = Regex(
        "\\b(next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE
    )
    private val MONTH_FORMATS = listOf("d MMM uuuu", "d MMMM uuuu")
    private val TOKEN = Regex("[a-z0-9]+")
    private val GROUNDING_STOP_WORDS = setOf(
        "a", "an", "the", "i", "me", "my", "to", "for", "by", "on", "at", "in",
        "todo", "task", "reminder", "remind", "need", "do", "not", "forget"
    )
}
