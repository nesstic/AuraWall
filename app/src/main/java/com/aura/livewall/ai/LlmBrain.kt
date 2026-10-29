package com.aura.livewall.ai

import android.content.Context
import com.aura.livewall.data.CharacterEntity
import com.aura.livewall.data.HistoryEntity
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Reaction(val say: String, val emotion: String = "neutral", val action: String = "none")

/**
 * Локальная LLM (Llama-стиль) через MediaPipe LLM Inference. Модель (.task) кладётся в
 * Android/data/com.aura.livewall/files/llm/ . Интернет не используется.
 * LLM возвращает JSON: реплика + эмоция + действие -> управляет персонажем в реальном времени.
 */
class LlmBrain private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var inst: LlmBrain? = null
        fun get(c: Context): LlmBrain = inst ?: synchronized(this) { inst ?: LlmBrain(c.applicationContext).also { inst = it } }
    }

    private val lock = Mutex()
    @Volatile private var llm: LlmInference? = null

    fun dir(): File = ctx.getExternalFilesDir("llm") ?: File(ctx.filesDir, "llm").apply { mkdirs() }
    fun modelFile(): File? = dir().listFiles()?.firstOrNull { it.isFile && (it.extension == "task" || it.extension == "bin") }
    fun available() = modelFile() != null

    private fun ensure(): LlmInference? {
        llm?.let { return it }
        val f = modelFile() ?: return null
        return runCatching {
            LlmInference.createFromOptions(
                ctx,
                LlmInference.LlmInferenceOptions.builder().setModelPath(f.absolutePath).setMaxTokens(768).build()
            )
        }.getOrNull().also { llm = it }
    }

    /** null -> модель недоступна/ошибка: вызывающий берёт запасную реплику характера. */
    suspend fun react(c: CharacterEntity, input: String, history: List<HistoryEntity>): Reaction? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val model = ensure() ?: return@withContext null
                runCatching { parse(model.generateResponse(prompt(c, history, input))) }.getOrNull()
            }
        }

    fun close() { runCatching { llm?.close() }; llm = null }

    private fun prompt(c: CharacterEntity, hist: List<HistoryEntity>, user: String): String {
        val sys = "Ты — ${c.name}, живой персонаж на обоях телефона. Характер: ${c.persona}\n" +
            "Отвечай коротко (до 18 слов), от первого лица, на языке пользователя.\n" +
            "Ответ — ТОЛЬКО JSON без пояснений: " +
            "{\"say\":\"...\",\"emotion\":\"neutral|happy|angry|sad|surprised|shy\",\"action\":\"none|nod|shake|wave|jump\"}"
        val sb = StringBuilder("<|begin_of_text|>")
        fun turn(role: String, text: String) {
            sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n").append(text).append("<|eot_id|>")
        }
        turn("system", sys)
        hist.asReversed().takeLast(6).forEach {
            if (it.role == "user") turn("user", it.text) else turn("assistant", "{\"say\":${JSONObject.quote(it.text)}}")
        }
        turn("user", user)
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    private fun parse(raw0: String): Reaction {
        val raw = raw0.replace("<|eot_id|>", "").trim()
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s >= 0 && e > s) {
            runCatching {
                val j = JSONObject(raw.substring(s, e + 1))
                val say = j.optString("say", "").trim()
                if (say.isNotEmpty()) return Reaction(say.take(160), j.optString("emotion", "neutral"), j.optString("action", "none"))
            }
        }
        return Reaction(raw.take(160))
    }
}
