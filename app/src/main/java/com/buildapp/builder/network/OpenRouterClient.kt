package com.buildapp.builder.network

import com.buildapp.builder.data.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenRouterClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateHtml(userPrompt: String, config: BuildConfig): String = withContext(Dispatchers.IO) {
        require(config.openRouterKey.isNotBlank()) { "API key OpenRouter belum diisi." }
        require(config.modelId.isNotBlank()) { "Model ID belum diisi." }
        require(userPrompt.length <= 18_000) { "Prompt terlalu panjang. Batas awal 18.000 karakter." }

        val primaryModel = config.modelId.trim()
        val fallbackModel = if (primaryModel == DEFAULT_MODEL) FALLBACK_MODEL else DEFAULT_MODEL
        val candidates = listOf(primaryModel, fallbackModel).distinct()
        var lastError: Exception? = null

        for ((index, model) in candidates.withIndex()) {
            try {
                return@withContext requestHtml(model, userPrompt, config.openRouterKey)
            } catch (error: Exception) {
                val canRetry = index < candidates.lastIndex && (
                    error is java.io.IOException ||
                    error is ModelUnavailableException ||
                    error is IllegalArgumentException
                )
                if (!canRetry) throw error
                lastError = error
            }
        }

        throw IllegalStateException("Model utama dan fallback gagal: ${lastError?.message ?: "Tidak ada respons AI."}")
    }

    private fun requestHtml(model: String, userPrompt: String, apiKey: String): String {
        val systemPrompt = """
            Kamu adalah senior front-end engineer yang membuat aplikasi web satu-file untuk Android WebView.
            Hasilkan SATU dokumen HTML lengkap berisi CSS dan JavaScript inline.
            Aturan wajib:
            - Output hanya kode HTML. Jangan gunakan Markdown atau penjelasan.
            - Mulai dengan <!doctype html> dan tutup dengan </html>.
            - Mobile-first untuk layar Android, responsive, touch-friendly, dan aman untuk WebView.
            - Gunakan HTML semantik, CSS modern, ukuran teks terbaca, serta state dan error handling.
            - Simpan data lokal dengan localStorage bila memang dibutuhkan.
            - Jangan memakai framework, CDN, import module, atau aset eksternal kecuali diminta pengguna.
            - Jangan membuat tombol/fitur palsu. Semua kontrol harus benar-benar berfungsi.
            - Game harus ringan, touch-first, dan 2D. Jangan mengklaim membuat native Android atau game 3D.
            - Hindari akses file, permission browser, popup, dan javascript bridge native.
            - Pastikan tidak ada teks placeholder, TODO, atau kode yang terpotong.
        """.trimIndent()

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 12_000)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt.trim()))
            })
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Build.APP")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                val detail = message.ifBlank { body.take(260).ifBlank { "Permintaan gagal." } }
                if (response.code in setOf(404, 408, 429, 502, 503, 504)) {
                    throw ModelUnavailableException("OpenRouter ${response.code}: $detail")
                }
                throw IllegalStateException("OpenRouter ${response.code}: $detail")
            }

            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
                ?: throw IllegalArgumentException("Respons AI tidak memiliki choices.")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalArgumentException("Respons AI kosong.")
            val raw = extractContent(message.opt("content"))
            normalizeHtml(raw)
        }
    }

    private class ModelUnavailableException(message: String) : IllegalStateException(message)

    private const val DEFAULT_MODEL = "cohere/north-mini-code:free"
    private const val FALLBACK_MODEL = "nvidia/nemotron-3.5-lightning:free"

    private fun extractContent(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                append(item.optString("text"))
            }
        }
        else -> ""
    }

    private fun normalizeHtml(raw: String): String {
        var value = raw.trim()
        value = value.replace(Regex("^```(?:html)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val doctypeIndex = value.indexOf("<!doctype html", ignoreCase = true)
        val htmlIndex = value.indexOf("<html", ignoreCase = true)
        val start = listOf(doctypeIndex, htmlIndex).filter { it >= 0 }.minOrNull()
        if (start != null && start > 0) value = value.substring(start)

        require(value.contains("<html", ignoreCase = true) && value.contains("</html>", ignoreCase = true)) {
            "AI tidak mengembalikan dokumen HTML lengkap. Coba ulangi atau pilih model lain."
        }
        require(value.length >= 120) { "Hasil HTML terlalu pendek dan kemungkinan tidak lengkap." }
        return value
    }
}
