package org.gptvoiceinput.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.gptvoiceinput.config.TranscriptionProfile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Direct client for the OpenAI Audio Transcriptions endpoint.
 *
 * The only supported provider is OpenAI and the only default model is
 * `gpt-transcribe`. Wire format verified against the official API reference
 * at implementation time (multipart fields: `file`, `model`, `prompt`,
 * `keywords[]`, `languages[]` — the latter replace the singular `language`
 * field for gpt-transcribe; both are never sent).
 *
 * No automatic retry of a possibly-completed POST: ambiguous network/timeout
 * failures surface to the UI, and retry is always an explicit user action.
 */
class OpenAITranscriber(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
    /** Injectable for tests only; production callers use the default. */
    private val endpoint: String = ENDPOINT,
) {

    /** Returns the final transcript. Throws [TranscriptionException] on failure. */
    suspend fun transcribe(audioFile: File, profile: TranscriptionProfile): String =
        withContext(Dispatchers.IO) {
            val request = buildRequest(audioFile, profile)
            val call = client.newCall(request)
            val response = awaitResponse(call)
            response.use { resp ->
                try {
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseError(resp.code, body)
                    }
                    val text = JSONObject(body).optString("text").trim()
                    if (text.isEmpty()) {
                        throw TranscriptionException.Protocol("Empty transcript returned by API")
                    }
                    text
                } catch (e: TranscriptionException) {
                    throw e
                } catch (e: SocketTimeoutException) {
                    // Read of the response body itself timed out — treat like any
                    // ambiguous timeout: never auto-replay, surface for retry.
                    throw TranscriptionException.Timeout(e)
                } catch (e: IOException) {
                    throw TranscriptionException.Network(e)
                } catch (e: Exception) {
                    throw TranscriptionException.Protocol("Unexpected API response", e)
                }
            }
        }

    private fun buildRequest(audioFile: File, profile: TranscriptionProfile): Request {
        val mediaType = "audio/wav".toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
            .apply {
                profile.transcriptionContext.takeIf { it.isNotBlank() }?.let {
                    addFormDataPart("prompt", it)
                }
                profile.expectedLanguages.forEach { addFormDataPart("languages[]", it) }
                profile.keywords.forEach { addFormDataPart("keywords[]", it) }
            }
            .build()

        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
    }

    private suspend fun awaitResponse(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    println("DEBUG onFailure: " + e::class.qualifiedName)
                    if (cont.isCancelled) return
                    cont.resumeWith(
                        Result.failure(
                            when (e) {
                                is SocketTimeoutException -> TranscriptionException.Timeout(e)
                                else -> TranscriptionException.Network(e)
                            },
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    private fun parseError(code: Int, body: String): TranscriptionException {
        val apiMessage = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()
            ?: "Request failed"
        val apiType = runCatching { JSONObject(body).optJSONObject("error")?.optString("type") }
            .getOrNull()
        return when (code) {
            401, 403 -> TranscriptionException.Unauthorized(apiMessage)
            429 -> TranscriptionException.RateLimited(apiMessage)
            in 500..599 -> TranscriptionException.ServerError(code, apiMessage)
            else -> TranscriptionException.ApiError(code, apiMessage, apiType)
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val MODEL = "gpt-transcribe"
        private const val USER_AGENT = "gpt-voice-input/0.1"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

/** Structured, user-presentable transcription failures. */
sealed class TranscriptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class Unauthorized(message: String?) :
        TranscriptionException(message ?: "Invalid API key (401)")

    class RateLimited(message: String?) :
        TranscriptionException(message ?: "Rate limited (429)")

    class ServerError(code: Int, message: String?) :
        TranscriptionException(message ?: "Server error ($code)")

    class ApiError(val code: Int, message: String?, val apiType: String?) :
        TranscriptionException(message ?: "API error ($code)")

    class Timeout(cause: Throwable) :
        TranscriptionException("Request timed out", cause)

    class Network(cause: Throwable) :
        TranscriptionException("Network error", cause)

    class Protocol(message: String, cause: Throwable? = null) :
        TranscriptionException(message, cause)
}
