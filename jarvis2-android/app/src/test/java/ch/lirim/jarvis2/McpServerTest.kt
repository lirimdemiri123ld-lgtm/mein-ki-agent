package ch.lirim.jarvis2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val host = object : ToolHost {
        override fun tools(): List<JMap> = listOf(jobj("name" to "echo", "description" to "", "inputSchema" to jobj("type" to "object")))
        override fun call(name: String, args: JMap) = ToolResult("echo " + args.str("t"))
    }
    private val server = McpServer(0, { "/mcp/x" }, host)

    private fun rpc(s: String): JMap = Json.obj(server.handleRpc(s))

    @Test fun initialize() {
        val r = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals("2025-03-26", r.obj("result")!!.str("protocolVersion"))
        assertEquals("jarvis2", r.obj("result")!!.obj("serverInfo")!!.str("name"))
    }

    @Test fun unknownVersionFallsBackToLatest() {
        val r = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        assertEquals(McpServer.SUPPORTED_VERSIONS.first(), r.obj("result")!!.str("protocolVersion"))
    }

    @Test fun notificationGetsNoReply() {
        assertNull(server.handleRpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test fun listAndCall() {
        val l = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals("echo", l.obj("result")!!.objs("tools")[0].str("name"))
        val c = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"t":"Grüezi"}}}""")
        val content = c.obj("result")!!.objs("content")
        assertEquals("echo Grüezi", content[0].str("text"))
        val u = rpc("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nix"}}""")
        assertTrue(u.obj("result")!!.bool("isError"))
    }

    @Test fun unknownMethodIsError() {
        val r = rpc("""{"jsonrpc":"2.0","id":5,"method":"foo/bar"}""")
        assertEquals(-32601, r.obj("error")!!.int("code"))
    }
}
