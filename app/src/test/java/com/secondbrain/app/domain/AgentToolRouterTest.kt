package com.secondbrain.app.domain

import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.NoteDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgentToolRouterTest {
    private val note = NoteDocument(
        id = "note-1",
        title = "Team meeting",
        content = "Decisions from yesterday's design meeting.",
        timestamp = 1.0
    )
    private val trashed = NoteDocument(
        id = "note-trash",
        title = "Old plan",
        content = "Archived planning notes.",
        timestamp = 1.0
    )
    private val action = ActionItem.manual("Call Sam")
    private val inventory = AgentToolInventory(
        activeNotes = listOf(note),
        trashedNotes = listOf(trashed),
        actions = listOf(action),
        today = LocalDate.of(2026, 9, 25)
    )
    private val router = AgentToolRouter()

    @Test
    fun `routes a whitelisted note id to a confirmed mutation`() {
        val route = router.parseModelResponse(
            """{"decision":"tool","tool":"trash_note","arguments":{"note_id":"note-1"}}""",
            inventory
        )

        assertEquals(
            AgentRoute.Tool(AgentCommand.TrashNote("Team meeting", "note-1"), AgentRoute.Source.QWEN),
            route
        )
    }

    @Test
    fun `accepts compact on-device wire format`() {
        assertEquals(
            AgentRoute.Tool(AgentCommand.TrashNote("Team meeting", "note-1"), AgentRoute.Source.QWEN),
            router.parseModelResponse(
                """{"d":"t","t":"trash_note","a":{"note_id":"note-1"}}""",
                inventory
            )
        )
    }

    @Test
    fun `rejects an id that was not in the supplied inventory`() {
        val route = router.parseModelResponse(
            """{"decision":"tool","tool":"trash_note","arguments":{"note_id":"invented"}}""",
            inventory
        )

        assertTrue(route is AgentRoute.Clarify)
    }

    @Test
    fun `rejects unknown tools and extra arguments`() {
        val unknownTool = router.parseModelResponse(
            """{"decision":"tool","tool":"delete_database","arguments":{}}""",
            inventory
        )
        val extraArgument = router.parseModelResponse(
            """{"decision":"tool","tool":"list_notes","arguments":{"force":true}}""",
            inventory
        )

        assertTrue(unknownTool is AgentRoute.Clarify)
        assertTrue(extraArgument is AgentRoute.Clarify)
    }

    @Test
    fun `parses append action completion and due dates`() {
        assertEquals(
            AgentRoute.Tool(
                AgentCommand.AppendNote("Team meeting", "Sam owns the prototype", "note-1"),
                AgentRoute.Source.QWEN
            ),
            router.parseModelResponse(
                """{"decision":"tool","tool":"append_note","arguments":{"note_id":"note-1","addition":"Sam owns the prototype"}}""",
                inventory
            )
        )
        assertEquals(
            AgentRoute.Tool(
                AgentCommand.CompleteAction("Call Sam", action.id),
                AgentRoute.Source.QWEN
            ),
            router.parseModelResponse(
                """{"decision":"tool","tool":"complete_action","arguments":{"action_id":"${action.id}"}}""",
                inventory
            )
        )
        assertEquals(
            AgentRoute.Tool(
                AgentCommand.CreateAction("Send report", LocalDate.of(2026, 9, 26)),
                AgentRoute.Source.QWEN
            ),
            router.parseModelResponse(
                """{"decision":"tool","tool":"create_action","arguments":{"text":"Send report","due_date":"2026-09-26"}}""",
                inventory
            )
        )
    }

    @Test
    fun `keeps knowledge questions out of the tool layer`() {
        assertEquals(
            AgentRoute.Question,
            router.parseModelResponse("preamble {\"decision\":\"question\"}", inventory)
        )
    }

    @Test
    fun `parses and validates an ordered multi-step plan`() {
        val route = router.parseModelResponse(
            """{"decision":"plan","steps":[{"tool":"append_note","arguments":{"note_id":"note-1","addition":"Sam owns the prototype"}},{"tool":"create_action","arguments":{"text":"Call Sam","due_date":"2026-09-26"}}]}""",
            inventory
        )

        assertEquals(
            AgentRoute.Plan(
                listOf(
                    AgentCommand.AppendNote("Team meeting", "Sam owns the prototype", "note-1"),
                    AgentCommand.CreateAction("Call Sam", LocalDate.of(2026, 9, 26))
                ),
                AgentRoute.Source.QWEN
            ),
            route
        )
    }

    @Test
    fun `rejects an unsafe id anywhere in a plan`() {
        val route = router.parseModelResponse(
            """{"decision":"plan","steps":[{"tool":"list_notes","arguments":{}},{"tool":"trash_note","arguments":{"note_id":"invented"}}]}""",
            inventory
        )

        assertTrue(route is AgentRoute.Clarify)
    }
}
