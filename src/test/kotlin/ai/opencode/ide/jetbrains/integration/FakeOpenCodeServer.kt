package ai.opencode.ide.jetbrains.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FakeOpenCodeServer(val port: Int, private val password: String? = null) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val sseClients = CopyOnWriteArrayList<OutputStream>()
    private val diffResponses = ConcurrentHashMap<String, String>()
    private val diffDelays = ConcurrentHashMap<String, Long>()
    private val failuresRemaining = AtomicInteger(0)
    val receivedPrompts = CopyOnWriteArrayList<String>()
    val receivedPromptUris = CopyOnWriteArrayList<String>()
    
    val activePort: Int
        get() = server.address.port

    fun failNextPosts(count: Int) {
        failuresRemaining.set(count)
    }

    private fun authorized(ex: HttpExchange): Boolean {
        if (password == null) return true
        val header = ex.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Basic ")) return false
        return try {
            val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
            decoded == "opencode:$password"
        } catch (e: Exception) {
            false
        }
    }

    private fun rejectUnauthorized(ex: HttpExchange): Boolean {
        if (authorized(ex)) return false
        ex.sendResponseHeaders(401, -1)
        ex.responseBody.close()
        return true
    }

    init {
        // Catch-all handler for debugging
        server.createContext("/") { ex ->
            println("  [FakeServer] Unhandled Request: ${ex.requestURI}")
            val resp = "No context found for request".toByteArray()
            ex.sendResponseHeaders(404, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }

        server.createContext("/tui/append-prompt") { ex ->
            if (ex.requestMethod == "POST") {
                if (rejectUnauthorized(ex)) return@createContext
                val body = ex.requestBody.reader().readText()
                if (failuresRemaining.get() > 0) {
                    failuresRemaining.decrementAndGet()
                    println("  [FakeServer] POST /tui/append-prompt -> injected 500 failure")
                    ex.sendResponseHeaders(500, -1)
                    ex.responseBody.close()
                    return@createContext
                }
                println("  [FakeServer] POST /tui/append-prompt: $body")
                // Keep raw JSON for assertions in tests.
                receivedPrompts.add(body)
                receivedPromptUris.add(ex.requestURI.toString())
                
                val resp = "{}".toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } else {
                ex.sendResponseHeaders(405, 0)
                ex.responseBody.close()
            }
        }

        server.createContext("/global/health") { ex ->
            if (rejectUnauthorized(ex)) return@createContext
            val resp = "OK".toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        
        server.createContext("/event") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.sendResponseHeaders(200, 0)
            
            val os = ex.responseBody
            sseClients.add(os)
            println("  [FakeServer] SSE Client connected")
            
            // Keep connection open
            try {
                // Initial ping to confirm connection
                os.write(": ping\n\n".toByteArray())
                os.flush()
                
                // We need to keep this thread alive but allow interruption
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    // Keep-alive
                    // os.write(": ping\n\n".toByteArray())
                    // os.flush() 
                    // Note: sending too many pings might fill buffers if test is short.
                }
            } catch (e: Exception) {
                // println("  [FakeServer] SSE Client disconnected: ${e.message}")
            } finally {
                sseClients.remove(os)
            }
        }
        
        server.createContext("/session") { ex ->
            // Path: /session/{id}/diff?messageID=...
            val path = ex.requestURI.path
            val query = ex.requestURI.query
            
            if (path.endsWith("/diff")) {
                val messageId = query?.split("&")
                    ?.find { it.startsWith("messageID=") }
                    ?.substringAfter("=")
                
                val body = diffResponses[messageId] ?: "[]"
                val delay = diffDelays[messageId] ?: 0
                
                if (delay > 0) Thread.sleep(delay)
                
                println("  [FakeServer] GET Diff for $messageId -> $body")
                
                val resp = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } else {
                ex.sendResponseHeaders(404, 0)
                ex.responseBody.close()
            }
        }
        
        server.executor = Executors.newCachedThreadPool()
    }
    
    fun start() {
        server.start()
        println("  [FakeServer] Started on port $port")
    }
    
    fun stop() {
        server.stop(0)
        println("  [FakeServer] Stopped")
    }
    
    fun broadcast(json: String) {
        val msg = "data: $json\n\n".toByteArray(Charsets.UTF_8)
        for (client in sseClients) {
            try {
                client.write(msg)
                client.flush()
            } catch (e: Exception) {
                sseClients.remove(client)
            }
        }
        println("  [FakeServer] Broadcast: $json")
    }
    
    fun setDiffResponse(messageId: String, json: String, delayMs: Long = 0) {
        diffResponses[messageId] = json
        if (delayMs > 0) diffDelays[messageId] = delayMs
    }
}
