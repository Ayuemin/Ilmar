package com.ayuemin.ilmar.speech

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import com.ayuemin.ilmar.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal class SpeechPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ilmar_speech", Context.MODE_PRIVATE)
    val provider get() = prefs.getString("provider", "") ?: ""
    val model get() = prefs.getString("model", "") ?: ""
    val voice get() = prefs.getString("voice", "alloy") ?: "alloy"
    fun save(provider: String, model: String, voice: String) {
        prefs.edit().putString("provider", provider).putString("model", model.trim()).putString("voice", voice.trim()).apply()
    }
}

internal class SpeechController(private val context: Context, private val scope: CoroutineScope) {
    var playing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var ready = false
    private var initializing = true
    private var pendingSystem: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var task: Job? = null
    private var audio: File? = null
    private var generation = 0
    init { engine = TextToSpeech(context) { status ->
        initializing = false
        ready = status == TextToSpeech.SUCCESS
        if (ready) pendingSystem?.let { pendingSystem = null; speak(it) }
        else { pendingSystem = null; playing = false; error = "Системный синтез речи недоступен. Проверь установленный движок TTS." }
    } }
    fun stop() {
        pendingSystem = null; generation++; task?.cancel(); task = null; engine?.stop(); player?.release(); player = null
        audio?.delete(); audio = null; playing = false
    }
    fun close() { stop(); engine?.shutdown(); engine = null }
    fun speak(text: String) {
        stop(); error = null
        val content = text.replace(Regex("```[^\n]*\n|```|[*#_`]+"), "").trim()
        if (content.isEmpty()) return
        val preferences = SpeechPreferences(context)
        if (preferences.provider.isBlank()) {
            if (!ready) {
                if (initializing) { pendingSystem = content; playing = true }
                else error = "Системный синтез речи недоступен. Проверь установленный движок TTS."
                return
            }
            val language = engine?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (language < 0) { error = "В системном движке не установлен голос для выбранного языка"; return }
            val pieces = content.chunked(3500); val token = generation
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == "last") handler.post { if (token == generation) playing = false } }
                @Deprecated("Android callback") override fun onError(id: String?) { handler.post { if (token == generation) { playing = false; error = "Ошибка системной озвучки" } } }
            })
            playing = true
            pieces.forEachIndexed { i, piece ->
                if (engine?.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                        if (i == pieces.lastIndex) "last" else "$i") == TextToSpeech.ERROR) { error = "Не удалось запустить озвучку"; stop(); return }
            }
            return
        }
        if (preferences.model.isBlank() || preferences.voice.isBlank()) { error = "Выбери модель и голос озвучки"; return }
        if (content.length > 16000) { error = "Для API-озвучки выбери ответ до 16 000 символов"; return }
        playing = true
        task = scope.launch {
            try {
                val (connection, key) = ProviderRegistry(context).catalogTarget(preferences.provider)
                val bytes = SpeechTransport.generate(connection.endpoint, key, preferences.model, preferences.voice, content)
                val target = withContext(Dispatchers.IO) { File.createTempFile("speech-", ".mp3", context.cacheDir).apply { writeBytes(bytes) } }
                currentCoroutineContext().ensureActive(); audio = target
                player = MediaPlayer().apply {
                    setDataSource(target.path)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { stop() }
                    setOnErrorListener { _, _, _ -> error = "Не удалось воспроизвести аудио"; stop(); true }
                    prepareAsync()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? ProviderFailure)?.message ?: "Озвучка недоступна. Проверь поддержку аудио API, ID модели и голос."; stop() }
        }
    }
}

internal object SpeechTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    internal fun request(endpoint: String, key: String, model: String, voice: String, text: String): Request {
        require(ProviderRegistry.validEndpoint(endpoint))
        val chat = ChatEndpoint.resolve(endpoint).toHttpUrl()
        val router = chat.host == "openrouter.ai"
        val body = if (router) JSONObject().put("model", model).put("stream", false)
            .put("modalities", JSONArray(listOf("text", "audio"))).put("audio", JSONObject().put("voice", voice).put("format", "mp3"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Прочитай вслух без изменений:\n$text")))
        else JSONObject().put("model", model).put("voice", voice).put("input", text).put("response_format", "mp3")
        val url = if (router) chat else chat.newBuilder().encodedPath(chat.encodedPath.removeSuffix("/chat/completions") + "/audio/speech").build()
        return Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
    suspend fun generate(endpoint: String, key: String, model: String, voice: String, text: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = request(endpoint, key, model, voice, text)
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(java.io.IOException("Ошибка озвучки"))) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (!it.isSuccessful) throw ProviderFailure.http(it.code)
                    val body = it.body ?: error("Пустое аудио")
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input -> val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            require(output.size() + n <= 16 * 1024 * 1024); output.write(buffer, 0, n) }
                    }
                    val bytes = output.toByteArray(); require(bytes.isNotEmpty())
                    if (request.url.host == "openrouter.ai") {
                        val data = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getJSONObject("audio").getString("data")
                        android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    } else { require(body.contentType()?.type == "audio"); bytes }
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}
