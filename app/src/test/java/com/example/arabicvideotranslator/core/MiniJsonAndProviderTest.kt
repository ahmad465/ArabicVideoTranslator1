package com.example.arabicvideotranslator.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

class MiniJsonTest {
    @Test fun parsesNestedAndEscapes() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null],"s":"x\"y\n\u0627\u0644","o":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), v["a"])
        assertEquals("x\"y\nال", v["s"]); assertEquals(emptyMap<String, Any?>(), v["o"])
    }
    @Test fun rejectsInvalid() {
        for (bad in listOf("", "{", "[1,]", "{\"a\"}", "tru", "\"abc", "{} x", "[1 2]", "\"\\q\""))
            try { MiniJson.parse(bad); fail("accepted: $bad") } catch (_: IllegalArgumentException) {}
    }
}

class GoogleTranslateProviderTest {
    private lateinit var server: ServerSocket
    @Volatile private var status = 200
    @Volatile private var response = ""
    @Volatile private var lastBody = ""
    @Volatile private var lastKeyHeader: String? = null
    @Volatile private var lastPath = ""

    /** خادم HTTP/1.1 بسيط (طلب واحد لكل اتصال) على ServerSocket كي لا نعتمد على com.sun.*. */
    @Before fun up() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                sock.use { s ->
                    val input = s.getInputStream()
                    fun readLine(): String {
                        val sb = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
                        return sb.toString()
                    }
                    val requestLine = readLine()
                    var len = 0; var key: String? = null
                    while (true) {
                        val h = readLine(); if (h.isEmpty()) break
                        val i = h.indexOf(':'); val name = h.substring(0, i).trim().lowercase(); val v = h.substring(i + 1).trim()
                        if (name == "content-length") len = v.toInt()
                        if (name == "x-goog-api-key") key = v
                    }
                    val body = ByteArray(len); var off = 0
                    while (off < len) { val r = input.read(body, off, len - off); if (r < 0) break; off += r }
                    lastBody = String(body, Charsets.UTF_8); lastKeyHeader = key; lastPath = requestLine.split(" ").getOrElse(1) { "" }
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $status X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }
    @After fun down() = server.close()

    private fun provider(key: String = "KEY", transport: HttpTransport = UrlConnectionTransport(1000, 2000)) =
        GoogleTranslateProvider(key, transport, "http://127.0.0.1:${server.localPort}/v2")

    @Test fun successDecodesArabicAndHtmlEntities() {
        response = """{"data":{"translations":[{"translatedText":"مرحبا &amp; أهلاً"},{"translatedText":"&quot;عالم&quot;"}]}}"""
        val out = provider().translate(listOf("Hello & hi", "world"), "en", "ar")
        assertEquals(listOf("مرحبا & أهلاً", "\"عالم\""), out)
        assertEquals("KEY", lastKeyHeader)
        assertFalse("المفتاح يجب ألا يظهر في الرابط", lastPath.contains("KEY"))
        assertTrue(lastBody.contains("q=Hello+%26+hi")); assertTrue(lastBody.contains("q=world"))
        assertTrue(lastBody.contains("target=ar")); assertTrue(lastBody.contains("source=en"))
    }
    @Test fun omitsSourceWhenNull() {
        response = """{"data":{"translations":[{"translatedText":"x"}]}}"""
        provider().translate(listOf("a"), null, "ar"); assertFalse(lastBody.contains("source="))
    }
    @Test fun http429And5xxAreRetryable() {
        for (s in listOf(429, 500, 503)) {
            status = s; response = "{}"
            try { provider().translate(listOf("a"), "en", "ar"); fail() } catch (_: RetryableProviderException) {}
        }
    }
    @Test fun authErrorsAreFatal() {
        for (s in listOf(401, 403)) {
            status = s; response = "{}"
            try { provider().translate(listOf("a"), "en", "ar"); fail() } catch (_: FatalProviderException) {}
        }
    }
    @Test fun badRequestFatalWithServerMessage() {
        status = 400; response = """{"error":{"message":"Invalid Value"}}"""
        try { provider().translate(listOf("a"), "xx", "ar"); fail() }
        catch (e: FatalProviderException) { assertTrue(e.message!!.contains("Invalid Value")) }
    }
    @Test fun malformedOrMismatchedResponsesAreFatal() {
        for (body in listOf("not json", "{}", """{"data":{"translations":[]}}""", """{"data":{"translations":[{"x":1}]}}""")) {
            status = 200; response = body
            try { provider().translate(listOf("a"), "en", "ar"); fail("accepted $body") } catch (_: FatalProviderException) {}
        }
    }
    @Test fun networkFailureIsRetryable() {
        val t = object : HttpTransport { override fun post(url: String, headers: Map<String, String>, body: String) =
            throw IOException("timeout") }
        try { provider(transport = t).translate(listOf("a"), "en", "ar"); fail() } catch (_: RetryableProviderException) {}
    }
    @Test fun blankKeyIsFatalWithoutNetwork() {
        try { provider(key = " ").translate(listOf("a"), "en", "ar"); fail() } catch (_: FatalProviderException) {}
    }
    @Test fun endToEndWithPipelineAndRealSocketRetry() {
        var n = 0
        val flaky = object : HttpTransport {
            val real = UrlConnectionTransport(1000, 2000)
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
                if (n++ == 0) status = 503 else status = 200
                response = """{"data":{"translations":[{"translatedText":"ترجمة"}]}}"""
                return real.post(url, headers, body)
            }
        }
        val r = TranslationPipeline(provider(transport = flaky), sleeper = {}).translate(listOf(Cue(0, 1000, "hi")), "en")
        assertTrue(r.isComplete); assertEquals("ترجمة", r.translated[0]!!.text); assertEquals(2, n)
    }
}
