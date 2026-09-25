package com.secondbrain.app.domain

import java.time.LocalDate

sealed interface AgentCommand {
    data class CreateNote(val title: String, val content: String) : AgentCommand
    data class RenameNote(val query: String, val newTitle: String) : AgentCommand
    data class ReplaceNote(val query: String, val newContent: String) : AgentCommand
    data class TrashNote(val query: String) : AgentCommand
    data class RestoreNote(val query: String) : AgentCommand
    data object ListNotes : AgentCommand
    data object ListTrash : AgentCommand
    data class CreateAction(val text: String, val dueDate: LocalDate?) : AgentCommand
    data class CompleteAction(val query: String) : AgentCommand
}

/** Strict local parser for mutating chat commands. Unknown text remains a normal RAG question. */
object AgentCommandParser {
    fun parse(input: String, today: LocalDate = LocalDate.now()): AgentCommand? {
        val text = input.trim()
        if (text.isBlank()) return null

        LIST_TRASH.matchEntire(text)?.let { return AgentCommand.ListTrash }
        LIST_NOTES.matchEntire(text)?.let { return AgentCommand.ListNotes }
        TRASH_NOTE.matchEntire(text)?.groupValues?.get(1)?.cleanQuery()?.let {
            return AgentCommand.TrashNote(it)
        }
        RESTORE_NOTE.matchEntire(text)?.groupValues?.get(1)?.cleanQuery()?.let {
            return AgentCommand.RestoreNote(it)
        }
        RENAME_NOTE.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].cleanQuery()
            val title = match.groupValues[2].cleanValue()
            if (query.isNotBlank() && title.isNotBlank()) return AgentCommand.RenameNote(query, title)
        }
        REPLACE_NOTE.matchEntire(text)?.let { match ->
            val query = match.groupValues[1].cleanQuery()
            val content = match.groupValues[2].cleanValue()
            if (query.isNotBlank() && content.isNotBlank()) return AgentCommand.ReplaceNote(query, content)
        }
        CREATE_NOTE.matchEntire(text)?.let { match ->
            val title = match.groupValues[1].cleanValue()
            val content = match.groupValues[2].cleanValue()
            if (content.isNotBlank()) return AgentCommand.CreateNote(title, content)
        }
        REMEMBER.matchEntire(text)?.groupValues?.get(1)?.cleanValue()?.let {
            if (it.isNotBlank()) return AgentCommand.CreateNote("", it)
        }
        CREATE_ACTION.matchEntire(text)?.groupValues?.get(1)?.cleanValue()?.let { actionText ->
            if (actionText.isNotBlank()) {
                return AgentCommand.CreateAction(
                    text = actionText,
                    dueDate = ActionExtractor.findDueDate(actionText, today)
                )
            }
        }
        COMPLETE_ACTION.matchEntire(text)?.groupValues?.get(1)?.cleanQuery()?.let {
            return AgentCommand.CompleteAction(it)
        }
        return null
    }

    private fun String.cleanQuery(): String = cleanValue()
        .removePrefix("named ")
        .removePrefix("called ")
        .trim()

    private fun String.cleanValue(): String = trim().trim('"', '\'', '“', '”').trim()

    private val LIST_TRASH = Regex(
        "(?:show|list|open)(?: my| the)? (?:trash|deleted notes)(?: please)?[.!]?",
        RegexOption.IGNORE_CASE
    )
    private val LIST_NOTES = Regex(
        "(?:show|list)(?: my| all)? notes(?: please)?[.!]?",
        RegexOption.IGNORE_CASE
    )
    private val TRASH_NOTE = Regex(
        "(?:delete|trash|move)(?: the)? note(?: named| called)?[ :]+(.+?)(?: please)?[.!]?",
        RegexOption.IGNORE_CASE
    )
    private val RESTORE_NOTE = Regex(
        "restore(?: the)? note(?: named| called)?[ :]+(.+?)(?: please)?[.!]?",
        RegexOption.IGNORE_CASE
    )
    private val RENAME_NOTE = Regex(
        "rename(?: the)? note(?: named| called)?[ :]+(.+?)\\s+to\\s+(.+?)(?: please)?[.!]?",
        RegexOption.IGNORE_CASE
    )
    private val REPLACE_NOTE = Regex(
        "(?:replace|update)(?: the)? note(?: named| called)?[ :]+(.+?)\\s+(?:with|to say)\\s+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val CREATE_NOTE = Regex(
        "(?:create|add|save|make)(?: a| this)? note(?: titled [\"“]?(.+?)[\"”]?)?(?: saying| with|:)[ ]*(.+)",
        RegexOption.IGNORE_CASE
    )
    private val REMEMBER = Regex("remember(?: that)?[ :]+(.+)", RegexOption.IGNORE_CASE)
    private val CREATE_ACTION = Regex(
        "(?:create|add|make)(?: an?| this)? (?:action|task|todo)(?: saying|:)?[ ]+(.+)",
        RegexOption.IGNORE_CASE
    )
    private val COMPLETE_ACTION = Regex(
        "(?:complete|finish|mark done)(?: the)? (?:action|task|todo)(?: named| called)?[ :]+(.+?)(?: please)?[.!]?",
        RegexOption.IGNORE_CASE
    )
}
