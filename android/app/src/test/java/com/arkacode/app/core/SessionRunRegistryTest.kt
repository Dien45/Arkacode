package com.arkacode.app.core

import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ServerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionRunRegistryTest {

    private val sid = "ses_x"

    private fun registry(): SessionRunRegistry =
        SessionRunRegistry(CoroutineScope(Dispatchers.Unconfined), OpenCodeClient(port = 1, password = "x"), null)

    private fun tool(
        label: String = "",
        status: String = "",
        detail: String? = null,
        output: String? = null,
        exit: Int? = null,
        shellId: String? = null,
        shellFile: String? = null,
        id: String = "",
        sessionID: String? = sid,
    ) = ServerEvent.Tool(sessionID, id, label, status, detail, output, exit, false, shellId, shellFile)

    @Test
    fun mergesStartedCalledAndShellCreatedIntoOneEntry() {
        val reg = registry()
        val r = reg.run(sid)

        reg.applyEvent(tool(id = "call_1", label = "shell", status = "running"))
        reg.applyEvent(tool(id = "call_1", status = "running", detail = "echo hi; sleep 3"))
        reg.applyEvent(
            tool(
                label = "shell",
                status = "running",
                detail = "echo hi; sleep 3",
                shellId = "sh_1",
                shellFile = "/root/.local/share/opencode/shell/p/sh_1.out",
            ),
        )

        val tools = r.liveTools.value
        assertEquals(1, tools.size)
        val t = tools.first()
        assertEquals("call_1", t.id)
        assertEquals("shell", t.name)
        assertEquals("echo hi; sleep 3", t.detail)
        assertEquals("sh_1", t.shellId)
        assertEquals("/root/.local/share/opencode/shell/p/sh_1.out", t.shellFile)
    }

    @Test
    fun successFinalizesOutputAndStatus() {
        val reg = registry()
        val r = reg.run(sid)

        reg.applyEvent(tool(id = "call_1", label = "shell", status = "running", detail = "echo hi"))
        reg.applyEvent(tool(id = "call_1", status = "completed", output = "one\ntwo\n", exit = 0))

        val t = r.liveTools.value.first()
        assertEquals("completed", t.status)
        assertEquals("one\ntwo\n", t.output)
        assertEquals(0, t.exit)
    }

    @Test
    fun shellExitedWithoutSessionIdMatchesByShellId() {
        val reg = registry()
        val r = reg.run(sid)

        reg.applyEvent(tool(id = "call_1", label = "shell", status = "running", detail = "sleep 9"))
        reg.applyEvent(
            tool(label = "shell", status = "running", shellId = "sh_9", shellFile = "/x/sh_9.out"),
        )
        // shell.exited: tanpa sessionID.
        reg.applyEvent(tool(sessionID = null, shellId = "sh_9", exit = 7))

        val t = r.liveTools.value.first()
        assertEquals("sh_9", t.shellId)
        assertEquals(7, t.exit)
    }

    @Test
    fun ignoresEventsForUnknownSession() {
        val reg = registry()
        reg.run(sid)
        // tidak throw walau sesi lain
        reg.applyEvent(tool(sessionID = "ses_other", id = "c", status = "running"))
        assertNull(reg.run(sid).liveTools.value.firstOrNull())
    }

    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue("kondisi tak terpenuhi dalam ${timeoutMs}ms", cond())
    }

    @Test
    fun tailsShellOutputFileFromHost() {
        val root = File.createTempFile("rootfs", "").let { it.delete(); it.mkdirs(); it }
        val outFile = File(root, "root/.local/share/opencode/shell/p/sh_1.out")
        outFile.parentFile!!.mkdirs()
        outFile.writeText("one\n")

        val reg = SessionRunRegistry(
            CoroutineScope(Dispatchers.Default),
            OpenCodeClient(port = 1, password = "x"),
            root,
        )
        val r = reg.run(sid)
        reg.applyEvent(
            tool(
                label = "shell",
                status = "running",
                detail = "seq 1 10",
                shellId = "sh_1",
                shellFile = "/root/.local/share/opencode/shell/p/sh_1.out",
            ),
        )

        awaitUntil(3000) { r.liveTools.value.firstOrNull()?.output == "one\n" }

        // file tumbuh → output live ikut tumbuh
        outFile.writeText("one\ntwo\nthree\n")
        awaitUntil(3000) { r.liveTools.value.firstOrNull()?.output == "one\ntwo\nthree\n" }

        val t = r.liveTools.value.first()
        assertEquals("shell", t.name)
        assertEquals("seq 1 10", t.detail)
        assertEquals("sh_1", t.shellId)
    }
}
