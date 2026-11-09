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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenRouter client for single-file Android WebView projects.
 *
 * Handles:
 * - model-level fallback,
 * - long completions and truncated output continuation,
 * - one focused repair pass before returning an error,
 * - text responses returned as either a string or content-block array.
 */
data class ModelOption(
    val id: String,
    val name: String,
    val contextLength: Int,
    val promptPricePerMillion: Double,
    val completionPricePerMillion: Double
) {
    val isFree: Boolean
        get() = promptPricePerMillion == 0.0 && completionPricePerMillion == 0.0

    val contextLabel: String
        get() = if (contextLength > 0) "${contextLength / 1000}K context" else "context unknown"

    val priceLabel: String
        get() = if (isFree) {
            "FREE · quota provider berlaku"
        } else {
            String.format(
                java.util.Locale.US,
                "IN $%.3f/M · OUT $%.3f/M",
                promptPricePerMillion,
                completionPricePerMillion
            )
        }
}

/**
 * OpenRouter model catalogue is fetched at runtime. Do not keep a fixed list of
 * provider model IDs in the UI: availability and pricing change frequently.
 */
object OpenRouterClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(apiKey: String): List<ModelOption> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API key belum diisi." }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models?output_modalities=text&sort=pricing-low-to-high")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .header("X-Title", "Build.APP")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    "Gagal mengambil katalog model OpenRouter (${response.code}): " +
                        message.ifBlank { body.take(240) }
                )
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: throw IllegalStateException("Katalog model OpenRouter tidak memiliki daftar data.")

            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue

                    val architecture = item.optJSONObject("architecture")
                    val outputs = architecture?.optJSONArray("output_modalities")
                    if (outputs != null && (0 until outputs.length()).none {
                            outputs.optString(it).equals("text", ignoreCase = true)
                        }) continue

                    val pricing = item.optJSONObject("pricing") ?: continue
                    val input = pricing.optString("prompt").toDoubleOrNull() ?: continue
                    val output = pricing.optString("completion").toDoubleOrNull() ?: continue
                    val context = item.optInt("context_length", 0).coerceAtLeast(0)

                    add(
                        ModelOption(
                            id = id,
                            name = item.optString("name").ifBlank { id },
                            contextLength = context,
                            promptPricePerMillion = input * 1_000_000.0,
                            completionPricePerMillion = output * 1_000_000.0
                        )
                    )
                }
            }.distinctBy { it.id }
                .sortedWith(
                    compareBy<ModelOption> { if (it.isFree) 0 else 1 }
                        .thenBy { it.promptPricePerMillion + it.completionPricePerMillion }
                        .thenByDescending { it.contextLength }
                )
                .take(100)
        }
    }

    suspend fun generateHtml(
        userPrompt: String,
        config: BuildConfig,
        onProgress: suspend (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        require(config.openRouterKey.isNotBlank()) { "API key OpenRouter belum diisi." }
        require(config.modelId.isNotBlank()) { "Model ID belum diisi." }
        require(userPrompt.isNotBlank()) { "Deskripsi project masih kosong." }
        require(userPrompt.length <= 18_000) { "Prompt terlalu panjang. Batas awal 18.000 karakter." }

        val primary = config.modelId.trim()
        val candidates = listOf(primary, DEFAULT_MODEL, FALLBACK_MODEL).distinct()
        var lastError: Exception? = null

        for ((index, model) in candidates.withIndex()) {
            try {
                onProgress("Menghubungi model ${model.substringAfterLast('/') }…")
                return@withContext generateCompleteDocument(model, userPrompt, config.openRouterKey, onProgress)
            } catch (error: Exception) {
                lastError = error
                val retryable = error is IOException ||
                    error is ModelUnavailableException ||
                    error is IncompleteHtmlException
                if (!retryable || index == candidates.lastIndex) {
                    throw IllegalStateException(
                        "Generator gagal pada ${model}. ${error.message ?: "Tidak ada respons valid."}",
                        error
                    )
                }
                onProgress("Model utama bermasalah. Beralih ke model gratis cadangan…")
            }
        }

        throw IllegalStateException(
            "Semua model gagal menghasilkan project. ${lastError?.message ?: "Coba lagi beberapa saat."}",
            lastError
        )
    }

    private suspend fun generateCompleteDocument(
        model: String,
        userPrompt: String,
        apiKey: String,
        onProgress: suspend (String) -> Unit
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", buildUserPrompt(userPrompt)))

        onProgress("AI sedang merancang struktur dan kode…")
        val first = requestCompletion(model, messages, apiKey, INITIAL_MAX_TOKENS)
        var output = first.content.trim()
        var finishReason = first.finishReason

        // A model can reach its output-token limit before closing the HTML document.
        // Continue with the assistant's exact partial answer instead of discarding it.
        var continuation = 0
        while (!isCompleteHtml(output) && continuation < MAX_CONTINUATIONS) {
            continuation++
            onProgress("Melanjutkan kode yang belum selesai ($continuation/$MAX_CONTINUATIONS)…")
            messages.put(JSONObject().put("role", "assistant").put("content", output))
            messages.put(
                JSONObject().put(
                    "role",
                    "user"
                ).put(
                    "content",
                    "Lanjutkan persis dari karakter terakhir jawaban sebelumnya. Jangan ulangi kode yang sudah ada. " +
                        "Keluarkan hanya sisa kode HTML/CSS/JavaScript sampai dokumen benar-benar ditutup dengan </html>. " +
                        "Jangan memberi penjelasan atau Markdown."
                )
            )
            val next = requestCompletion(model, messages, apiKey, CONTINUATION_MAX_TOKENS)
            finishReason = next.finishReason
            val nextContent = next.content.trim()
            if (nextContent.isBlank()) break

            // Some providers ignore continuation instructions and regenerate the full file.
            // Prefer that complete answer; otherwise append only the returned fragment.
            output = if (isCompleteHtml(nextContent)) {
                nextContent
            } else {
                output + "\n" + nextContent
            }
        }

        if (isCompleteHtml(output)) {
            onProgress("Memvalidasi struktur dokumen HTML…")
            return normalizeHtml(output)
        }

        // One repair attempt is cheaper and more useful than returning the old generic error.
        onProgress("Memperbaiki respons AI yang belum lengkap…")
        val repairMessages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt()))
            .put(
                JSONObject().put(
                    "role",
                    "user"
                ).put(
                    "content",
                    buildString {
                        append(buildUserPrompt(userPrompt))
                        append("\n\nHASIL AI SEBELUMNYA (BELUM LENGKAP):\n")
                        append(output.take(MAX_REPAIR_CONTEXT_CHARS))
                        append(
                            "\n\nTUGAS PERBAIKAN: Buat ulang menjadi satu dokumen HTML lengkap dan valid. " +
                                "Jangan menjelaskan, jangan memakai Markdown, jangan memotong fitur inti. " +
                                "Pastikan dimulai dari <!doctype html> atau <html> dan diakhiri </html>. " +
                                "Keluarkan seluruh dokumen final, bukan potongan."
                        )
                    }
                )
            )

        val repaired = requestCompletion(model, repairMessages, apiKey, REPAIR_MAX_TOKENS)
        if (isCompleteHtml(repaired.content)) {
            onProgress("Auto-repair berhasil. Memeriksa HTML final…")
            return normalizeHtml(repaired.content)
        }

        val reason = finishReason.ifBlank { "tidak diketahui" }
        throw IncompleteHtmlException(
            "Respons model masih tidak lengkap setelah $continuation lanjutan dan 1 auto-repair " +
                "(finish_reason: $reason). Kurangi kompleksitas project atau coba model lain."
        )
    }

    private fun requestCompletion(
        model: String,
        messages: JSONArray,
        apiKey: String,
        maxTokens: Int
    ): ModelResponse {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.15)
            put("max_tokens", maxTokens)
            put("messages", messages)
            put("provider", JSONObject().put("allow_fallbacks", true))
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
                val errorObject = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                val message = errorObject?.optString("message").orEmpty()
                    .ifBlank { body.take(300) }
                    .ifBlank { "Permintaan gagal tanpa detail dari provider." }

                if (response.code in setOf(408, 429, 502, 503, 504) ||
                    response.code == 404 ||
                    response.code == 529
                ) {
                    throw ModelUnavailableException("OpenRouter ${response.code}: $message")
                }
                if (response.code == 401 || response.code == 403) {
                    throw IllegalStateException(
                        "API key ditolak OpenRouter (${response.code}). Periksa API key dan status kredit/limit akun."
                    )
                }
                throw IllegalStateException("OpenRouter ${response.code}: $message")
            }

            val root = runCatching { JSONObject(body) }.getOrElse {
                throw IncompleteHtmlException("Respons OpenRouter bukan JSON yang valid.")
            }
            val choices = root.optJSONArray("choices")
                ?: throw IncompleteHtmlException(
                    root.optJSONObject("error")?.optString("message")
                        ?: "Respons AI tidak memiliki daftar choices."
                )
            val choice = choices.optJSONObject(0)
                ?: throw IncompleteHtmlException("Respons AI tidak memiliki pilihan jawaban.")
            val message = choice.optJSONObject("message")
                ?: throw IncompleteHtmlException("Respons AI kosong.")
            val text = extractContent(message.opt("content"))
            val finishReason = choice.optString("finish_reason").orEmpty()

            if (text.isBlank()) {
                val refusal = message.optString("refusal").orEmpty()
                throw IncompleteHtmlException(
                    refusal.ifBlank {
                        "Model mengembalikan jawaban kosong (finish_reason: ${finishReason.ifBlank { "unknown" }})."
                    }
                )
            }
            ModelResponse(text, finishReason)
        }
    }

    private fun systemPrompt(): String = """
        Kamu adalah senior Android WebView application engineer dan game developer 2D.
        Tugasmu menghasilkan satu dokumen HTML mandiri untuk dijadikan APK melalui Android WebView.
        KELUARAN WAJIB:
        - Satu dokumen HTML lengkap, dimulai dengan <!doctype html> atau <html> dan diakhiri </html>.
        - Semua CSS dan JavaScript harus inline di dokumen yang sama.
        - Jangan gunakan Markdown code fence, pengantar, penjelasan, atau teks di luar HTML.
        KUALITAS:
        - Pahami ruang lingkup prompt dan implementasikan fungsi utama terlebih dahulu.
        - Mobile-first untuk Android portrait dan landscape bila relevan.
        - Interaksi touch harus nyaman, layout responsif, teks terbaca, dan state jelas.
        - Semua tombol harus memiliki aksi nyata; jangan membuat tombol dekoratif atau placeholder.
        - Sertakan loading, empty state, error state, dan konfirmasi untuk aksi penting bila relevan.
        - Untuk game 2D gunakan Canvas/SVG/CSS yang ringan, game loop yang aman, pause/resume, skor, dan restart.
        - Simpan preferensi atau progress lokal dengan localStorage bila sesuai.
        KOMPATIBILITAS:
        - Jangan memerlukan framework, CDN, module import, server, atau aset eksternal kecuali diminta.
        - Jangan memakai JavaScript bridge native atau menganggap permission Android tersedia otomatis.
        - Jangan mengklaim membuat Kotlin native, Unity, Godot, atau game 3D.
        - Hindari popup, mixed content, akses file lokal, dan API browser yang tidak didukung.
        - Tutup semua tag, fungsi, dan blok script. Pastikan tidak ada TODO atau teks terpotong.
        Jika permintaan terlalu besar untuk satu HTML, buat versi playable/prototype yang solid dengan mekanik inti dan jelaskan batasannya hanya melalui UI aplikasinya, bukan di luar HTML.
    """.trimIndent()

    private fun buildUserPrompt(prompt: String): String =
        "Buat project berdasarkan spesifikasi pengguna berikut. Implementasikan fitur prioritas yang benar-benar bisa berjalan. " +
            "Kembalikan satu file HTML penuh.\n\nSPESIFIKASI:\n" + prompt.trim()

    private fun extractContent(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") == "text" || item.has("text")) {
                    append(item.optString("text"))
                }
            }
        }
        else -> ""
    }

    private fun isCompleteHtml(raw: String): Boolean {
        val value = raw.trim()
        return value.contains("<html", ignoreCase = true) &&
            value.contains("</html>", ignoreCase = true) &&
            value.contains("<body", ignoreCase = true) &&
            value.contains("</body>", ignoreCase = true)
    }

    private fun normalizeHtml(raw: String): String {
        var value = raw.trim()
        value = value
            .replace(Regex("^\u0060\u0060\u0060(?:html)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\u0060\u0060\u0060$"), "")
            .trim()

        val htmlIndex = value.indexOf("<html", ignoreCase = true)
        val doctypeIndex = value.indexOf("<!doctype html", ignoreCase = true)
        val start = listOf(htmlIndex, doctypeIndex).filter { it >= 0 }.minOrNull()
            ?: throw IncompleteHtmlException("Tag HTML pembuka tidak ditemukan.")
        value = value.substring(start)

        val closeIndex = value.lastIndexOf("</html>", ignoreCase = true)
        if (closeIndex < 0) throw IncompleteHtmlException("Tag </html> penutup tidak ditemukan.")
        value = value.substring(0, closeIndex + "</html>".length)

        require(value.length >= 120) { "Hasil HTML terlalu pendek dan kemungkinan tidak lengkap." }
        require(value.contains("<body", ignoreCase = true) && value.contains("</body>", ignoreCase = true)) {
            "Dokumen HTML belum memiliki body yang lengkap."
        }
        return value
    }

    private data class ModelResponse(
        val content: String,
        val finishReason: String
    )

    private class ModelUnavailableException(message: String) : IOException(message)
    private class IncompleteHtmlException(message: String) : IOException(message)

    private const val DEFAULT_MODEL = "openrouter/free"
    private const val FALLBACK_MODEL = "openrouter/free"

    // North Mini Code supports up to 64K output tokens; 32K leaves room for prompt/context.
    private const val INITIAL_MAX_TOKENS = 32_000
    private const val CONTINUATION_MAX_TOKENS = 16_000
    private const val REPAIR_MAX_TOKENS = 24_000
    private const val MAX_CONTINUATIONS = 2
    private const val MAX_REPAIR_CONTEXT_CHARS = 90_000
}
