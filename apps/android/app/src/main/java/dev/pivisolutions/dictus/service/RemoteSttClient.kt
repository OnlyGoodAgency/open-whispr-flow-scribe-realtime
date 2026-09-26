package dev.pivisolutions.dictus.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class RemoteSttConfig(
    val url: String,
    val apiKey: String,
    val model: String? = "openai/whisper-large-v3-turbo",
)

/** OpenAI-compatible speech-to-text client used by both the app and the IME service. */
internal class RemoteSttClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(135, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun transcribe(
        samples: FloatArray,
        language: String?,
        config: RemoteSttConfig,
    ): String = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(config.url)
        require(config.apiKey.isNotBlank()) { "The server access token is missing" }

        val wav = WavEncoder.encodePcm16Mono(samples)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "dictation.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .apply {
                config.model?.takeIf { it.isNotBlank() }?.let { addFormDataPart("model", it) }
                if (!language.isNullOrBlank() && language != "auto") {
                    addFormDataPart("language", language)
                }
            }
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .post(multipart)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RemoteSttException("Server returned HTTP ${response.code}", statusCode = response.code)
            }
            val text = runCatching { JSONObject(body).optString("text") }
                .getOrElse { throw RemoteSttException("Server returned invalid JSON", it) }
                .trim()
            if (text.isEmpty()) throw RemoteSttException("Server returned an empty transcription")
            text
        }
    }

    companion object {
        internal fun normalizeEndpoint(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "The server URL is missing" }
            val uri = runCatching { URI(trimmed) }
                .getOrElse { throw IllegalArgumentException("The server URL is invalid") }
            val localDevelopment = uri.host.equals("localhost", ignoreCase = true) ||
                uri.host == "127.0.0.1" || uri.host == "10.0.2.2"
            require(
                !uri.host.isNullOrBlank() &&
                    (uri.scheme.equals("https", ignoreCase = true) ||
                        (localDevelopment && uri.scheme.equals("http", ignoreCase = true))),
            ) { "The server URL must use HTTPS" }
            return when {
                trimmed.endsWith("/v1/audio/transcriptions") -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed/audio/transcriptions"
                else -> "$trimmed/v1/audio/transcriptions"
            }
        }
    }
}

internal class RemoteSttException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause)

/** Converts the recorder's 16 kHz mono floating-point samples into a standard PCM WAV upload. */
internal object WavEncoder {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    fun encodePcm16Mono(samples: FloatArray): ByteArray {
        val pcmBytes = samples.size * 2
        val output = ByteArrayOutputStream(44 + pcmBytes)
        DataOutputStream(output).use { data ->
            data.writeAscii("RIFF")
            data.writeLeInt(36 + pcmBytes)
            data.writeAscii("WAVE")
            data.writeAscii("fmt ")
            data.writeLeInt(16)
            data.writeLeShort(1)
            data.writeLeShort(CHANNELS)
            data.writeLeInt(SAMPLE_RATE)
            data.writeLeInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
            data.writeLeShort(CHANNELS * BITS_PER_SAMPLE / 8)
            data.writeLeShort(BITS_PER_SAMPLE)
            data.writeAscii("data")
            data.writeLeInt(pcmBytes)
            samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                data.writeLeShort(pcm)
            }
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    private fun DataOutputStream.writeLeShort(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }
    private fun DataOutputStream.writeLeInt(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }
}
