package com.secondbrain.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgentCommandParserTest {
    @Test
    fun `parses an explicit sequence into an ordered plan`() {
        assertEquals(
            listOf(
                AgentCommand.CreateAction("Call Sam", null),
                AgentCommand.ListNotes
            ),
            AgentCommandParser.parsePlan("Create a task Call Sam and then list my notes")
        )
    }

    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun `parses note creation and remember commands`() {
        assertEquals(
            AgentCommand.CreateNote("Ideas", "Use a local graph"),
            AgentCommandParser.parse("Create a note titled Ideas saying Use a local graph", today)
        )
        assertEquals(
            AgentCommand.CreateNote("", "Pramod prefers local inference"),
            AgentCommandParser.parse("Remember that Pramod prefers local inference", today)
        )
    }

    @Test
    fun `parses destructive note commands without executing them`() {
        assertEquals(
            AgentCommand.TrashNote("Project plan"),
            AgentCommandParser.parse("Delete the note called Project plan", today)
        )
        assertEquals(
            AgentCommand.ReplaceNote("Project plan", "The new plan"),
            AgentCommandParser.parse("Update note Project plan to say The new plan", today)
        )
        assertEquals(
            AgentCommand.AppendNote("Project plan", "Call the design team"),
            AgentCommandParser.parse("Append to note Project plan: Call the design team", today)
        )
    }

    @Test
    fun `parses action date against the supplied day`() {
        val command = AgentCommandParser.parse("Add task Call Sam tomorrow", today)
        assertTrue(command is AgentCommand.CreateAction)
        command as AgentCommand.CreateAction
        assertEquals(today.plusDays(1), command.dueDate)
    }

    @Test
    fun `leaves normal questions for retrieval`() {
        assertNull(AgentCommandParser.parse("What did I decide about CozoDB?", today))
    }
}
