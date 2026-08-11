package org.gptvoiceinput.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.gptvoiceinput.config.TranscriptionProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verifies the gpt-transcribe wire format against MockWebServer: exact
 * multipart field names, repeated `[]` array encoding, auth header, response
 * parsing, error mapping and no-auto-retry-on-timeout behavior.
 */
class OpenAITranscriberTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun profile(
        context: String = "Neutral context.",
        languages: List<String> = listOf("zh-tw", "en"),
        keywords: List<String> = listOf("ACME_TERM", "ExampleTool"),
    ) = TranscriptionProfile(languages, context, keywords)

    private fun transcriber(client: OkHttpClient = OkHttpClient()): OpenAITranscriber =
        OpenAITranscriber("test-key", client, endpoint = server.url("/").toString())

    private fun wavFile(): File {
        val f = File.createTempFile("gvi-test", ".wav")
        f.writeBytes(ByteArray(100))
        f.deleteOnExit()
        return f
    }

    @Test
    fun `sends the documented gpt-transcribe multipart fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"text":"你好 world","languages":[{"code":"zh"}]}"""),
        )
        val text = transcriber().transcribe(wavFile(), profile())
        assertEquals("你好 world", text)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))

        val body = recorded.body.readUtf8()
        assertTrue("model part", body.contains("name=\"model\""))
        assertTrue("model value", body.contains("gpt-transcribe"))
        assertTrue("file part", body.contains("name=\"file\""))
        assertTrue("filename", body.contains("filename=\""))
        assertTrue("prompt part", body.contains("name=\"prompt\""))
        assertTrue("prompt value", body.contains("Neutral context."))
        assertTrue("languages[] repeated", body.contains("name=\"languages[]\""))
        assertTrue("language zh-tw", body.contains("zh-tw"))
        assertTrue("language en", body.contains("en"))
        assertTrue("keywords[] repeated", body.contains("name=\"keywords[]\""))
        assertTrue("keyword ACME_TERM", body.contains("ACME_TERM"))
        assertTrue("keyword ExampleTool", body.contains("ExampleTool"))
        // For gpt-transcribe the plural `languages` replaces `language`;
        // the singular field must never be sent.
        assertFalse("no singular language field", body.contains("name=\"language\""))
    }

    @Test
    fun `empty keyword and language lists are omitted`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"plain"}"""))
        transcriber().transcribe(
            wavFile(),
            TranscriptionProfile(emptyList(), "", emptyList()),
        )
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("languages[]"))
        assertFalse(body.contains("keywords[]"))
        assertFalse(body.contains("name=\"prompt\""))
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("name=\"model\""))
    }

    @Test
    fun `response without text is a protocol error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"languages":[]}"""))
        val result = runCatching { transcriber().transcribe(wavFile(), profile()) }
        assertTrue(result.exceptionOrNull() is TranscriptionException.Protocol)
    }

    @Test
    fun `401 maps to Unauthorized with a safe app-owned message`() {
        val result = runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody(
                        """{"error":{"message":"Incorrect API key provided: sk-secret123... You can find your API key at https://platform.openai.com/account/api-keys","type":"invalid_request_error"}}""",
                    ),
            )
            runCatching { transcriber().transcribe(wavFile(), profile()) }
        }
        val error = result.exceptionOrNull()
        assertTrue(error is TranscriptionException.Unauthorized)
        // The visible message must be stable and must not leak the server's
        // masked key fragment or the developer URL.
        val message = error!!.message.orEmpty()
        assertEquals("OpenAI rejected the API key", message)
        assertFalse(message.contains("sk-secret123"))
        assertFalse(message.contains("platform.openai.com"))
    }

    @Test
    fun `403 maps to Unauthorized with a safe message`() {
        val result = runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("""{"error":{"message":"You do not have access. sk-abc..."}}"""),
            )
            runCatching { transcriber().transcribe(wavFile(), profile()) }
        }
        val error = result.exceptionOrNull()
        assertTrue(error is TranscriptionException.Unauthorized)
        assertFalse("no key fragment", error!!.message.orEmpty().contains("sk-abc"))
    }

    @Test
    fun `rate limited message is safe and stable`() {
        val result = runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody("""{"error":{"message":"Rate limit reached for sk-secret999"}}"""),
            )
            runCatching { transcriber().transcribe(wavFile(), profile()) }
        }
        val error = result.exceptionOrNull()
        assertTrue(error is TranscriptionException.RateLimited)
        assertFalse("no key fragment", error!!.message.orEmpty().contains("sk-secret999"))
    }

    @Test
    fun `timeout maps to Timeout and is never auto-retried`() = runBlocking {
        val shortClient = OkHttpClient.Builder()
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            MockResponse().setBody("""{"text":"late"}""").setBodyDelay(2, TimeUnit.SECONDS),
        )
        val result = runCatching { transcriber(shortClient).transcribe(wavFile(), profile()) }
        assertTrue(result.exceptionOrNull() is TranscriptionException.Timeout)
        // Exactly one POST: ambiguous timeouts surface to the user for an
        // explicit retry instead of an automatic duplicate billable request.
        assertEquals(1, server.requestCount)
    }
}
