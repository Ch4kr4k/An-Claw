package dev.anclaw

import dev.anclaw.agent.ChatItem
import dev.anclaw.agent.ChatReducer
import dev.anclaw.agent.ChatState
import dev.anclaw.agent.SlashCommands
import dev.anclaw.engine.LlamaServer
import dev.anclaw.ui.MdBlock
import dev.anclaw.ui.MdParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun run(vararg events: String): ChatState =
        events.fold(ChatState()) { s, e -> ChatReducer.reduce(s, JSONObject(e)) }

    @Test fun streamsTextIntoOneMessage() {
        val s = run("""{"ev":"turn_start"}""", """{"ev":"text","text":"Hel"}""", """{"ev":"text","text":"lo"}""")
        assertTrue(s.busy)
        assertEquals("Hello", (s.items.single() as ChatItem.Assistant).text)
    }

    @Test fun toolResultsFillCallsInOrder() {
        val s = run(
            """{"ev":"tool_call","id":"a","name":"bash","input":"{\"command\":\"ls\"}"}""",
            """{"ev":"tool_call","id":"b","name":"bash","input":"{\"command\":\"pwd\"}"}""",
            """{"ev":"tool_result","name":"bash","output":"x","is_error":false}""",
        )
        val tools = s.items.filterIsInstance<ChatItem.Tool>()
        assertEquals("x", tools[0].output)
        assertNull(tools[1].output)
    }

    @Test fun permissionAndTurnEnd() {
        val s = run(
            """{"ev":"permission","id":7,"tool":"bash","input":"{}","command":"rm -rf x","reason":null,"host":false}""",
        )
        assertEquals(7L, s.permission!!.id)
        assertNull(s.permission!!.reason)
        val ended = ChatReducer.reduce(s, JSONObject("""{"ev":"turn_end","status":"stopped"}"""))
        assertNull(ended.permission)
        assertFalse(ended.busy)
        assertTrue(ended.items.last() is ChatItem.Notice)
    }

    @Test fun sessionReplay() {
        val lines = sequenceOf(
            """{"type":"session_meta","workspace_root":"/w"}""",
            """{"type":"message","message":{"role":"user","blocks":[{"type":"text","text":"hi"}]}}""",
            """{"type":"message","message":{"role":"assistant","blocks":[{"type":"tool_use","id":"t1","name":"read_file","input":"{}"}]}}""",
            """{"type":"message","message":{"role":"user","blocks":[{"type":"tool_result","tool_use_id":"t1","tool_name":"read_file","output":"data","is_error":false}]}}""",
        )
        val s = ChatReducer.fromSession(lines)
        assertEquals(2, s.items.size)
        assertEquals("data", (s.items[1] as ChatItem.Tool).output)
    }

    @Test fun slashMatching() {
        assertTrue(SlashCommands.matching("/rev").any { it.name == "review" })
        assertTrue(SlashCommands.matching("/review x").isEmpty())
    }

    @Test fun argSplitting() {
        assertEquals(listOf("--moe-n-expert", "6", "a b"), LlamaServer.splitArgs("--moe-n-expert 6 'a b'"))
    }

    @Test fun markdownBlocks() {
        val blocks = MdParser.parse("# T\n\ntext\n\n```kotlin\nval x = 1\n```\n- a\n- b\n\n| h | i |\n|---|---|\n| 1 | 2 |")
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals("val x = 1", (blocks.first { it is MdBlock.Code } as MdBlock.Code).code)
        assertEquals(2, blocks.count { it is MdBlock.ListItem })
        assertEquals(2, (blocks.last() as MdBlock.Table).rows.size)
    }
}
