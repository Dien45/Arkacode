package com.arkacode.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload nyata dari engine v2.0.31 (lihat capture `sse2.log`). */
class EventParsingTest {

    private val client = OpenCodeClient(port = 1, password = "x")

    private fun tool(ev: ServerEvent?): ServerEvent.Tool {
        assertTrue("event bukan Tool: $ev", ev is ServerEvent.Tool)
        return ev as ServerEvent.Tool
    }

    @Test
    fun inputStarted_carriesCallIdAndName() {
        val ev = tool(
            client.parseServerEvent(
                """{"type":"session.tool.input.started","data":{"sessionID":"ses_x","assistantMessageID":"msg_x","id":"call_1","name":"shell"}}""",
            ),
        )
        assertEquals("ses_x", ev.sessionID)
        assertEquals("call_1", ev.id)
        assertEquals("shell", ev.label)
        assertEquals("running", ev.status)
    }

    @Test
    fun called_carriesCommand() {
        val ev = tool(
            client.parseServerEvent(
                """{"type":"session.tool.called","data":{"sessionID":"ses_x","id":"call_1","input":{"command":"echo hi; sleep 3"},"executed":false}}""",
            ),
        )
        assertEquals("call_1", ev.id)
        assertEquals("echo hi; sleep 3", ev.detail)
        assertEquals("running", ev.status)
    }

    @Test
    fun shellCreated_carriesFileAndShellId_routedByMetadataSession() {
        val ev = tool(
            client.parseServerEvent(
                """{"type":"shell.created","data":{"info":{"id":"sh_1","status":"running","command":"echo hi","cwd":"/root/workspace","shell":"/bin/sh","file":"/root/.local/share/opencode/shell/p/sh_1.out","metadata":{"sessionID":"ses_x"}}}}""",
            ),
        )
        assertEquals("ses_x", ev.sessionID)
        assertEquals("sh_1", ev.shellId)
        assertEquals("/root/.local/share/opencode/shell/p/sh_1.out", ev.shellFile)
        assertEquals("echo hi", ev.detail)
    }

    @Test
    fun shellExited_hasNoSessionId_butHasExit() {
        val ev = tool(
            client.parseServerEvent("""{"type":"shell.exited","data":{"id":"sh_1","exit":7,"status":"exited"}}"""),
        )
        assertNull(ev.sessionID)
        assertEquals("sh_1", ev.shellId)
        assertEquals(7, ev.exit)
    }

    @Test
    fun toolSuccess_carriesOutputAndExit() {
        val ev = tool(
            client.parseServerEvent(
                """{"type":"session.tool.success","data":{"sessionID":"ses_x","id":"call_1","content":[{"type":"text","text":"one\ntwo\n"}],"metadata":{"status":"completed","truncated":false,"exit":0},"executed":false}}""",
            ),
        )
        assertEquals("call_1", ev.id)
        assertEquals("completed", ev.status)
        assertEquals("one\ntwo\n", ev.output)
        assertEquals(0, ev.exit)
        assertEquals(false, ev.truncated)
    }

    @Test
    fun progress_carriesShellId() {
        val ev = tool(
            client.parseServerEvent(
                """{"type":"session.tool.progress","data":{"sessionID":"ses_x","id":"call_1","metadata":{"shellID":"sh_1"}}}""",
            ),
        )
        assertEquals("call_1", ev.id)
        assertEquals("sh_1", ev.shellId)
    }
}
