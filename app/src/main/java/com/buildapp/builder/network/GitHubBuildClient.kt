package com.buildapp.builder.network

import android.content.Context
import android.util.Base64
import com.buildapp.builder.data.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object GitHubBuildClient {
    private const val API = "https://api.github.com"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class ProjectSpec(
        val name: String,
        val packageId: String,
        val html: String,
        val permissions: List<String> = emptyList(),
        val iconBase64: String = "",
        val versionCode: Int = 1,
        val versionName: String = "1.0"
    )

    suspend fun buildApk(
        context: Context,
        config: BuildConfig,
        project: ProjectSpec,
        onProgress: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        validateConfig(config)
        validateProject(project)

        val owner = config.githubOwner.trim()
        val repo = config.githubRepo.trim()
        val repositoryMetadata = getJson("$API/repos/$owner/$repo", config)
        check(repositoryMetadata.optBoolean("private", false)) {
            "Repository build harus PRIVATE. Repository publik tidak boleh menerima prompt atau source aplikasi."
        }
        val branch = config.githubBranch.trim().ifBlank { "main" }
        val slug = makeSlug(project.name)
        val projectPath = "build-inputs/$slug/project.json"
        val projectJson = JSONObject().apply {
            put("name", project.name.trim())
            put("packageId", project.packageId.trim())
            put("versionCode", project.versionCode)
            put("versionName", project.versionName)
            put("html", project.html)
            put("permissions", org.json.JSONArray(project.permissions.distinct()))
            put("iconBase64", project.iconBase64)
        }.toString()

        onProgress("Mengirim source ke repository privat…")
        val encodedProject = Base64.encodeToString(projectJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val uploadBody = JSONObject().apply {
            put("message", "Build.APP: upload generated project $slug")
            put("content", encodedProject)
            put("branch", branch)
        }
        apiRequest(
            method = "PUT",
            url = "$API/repos/$owner/$repo/contents/$projectPath",
            config = config,
            body = uploadBody.toString()
        ).use { response ->
            if (!response.isSuccessful) throw apiError("Upload project", response.code, response.body?.string().orEmpty())
        }

        onProgress("Memulai GitHub Actions…")
        val dispatchBody = JSONObject().apply {
            put("ref", branch)
            put("inputs", JSONObject().put("project_slug", slug))
        }
        val dispatchedAt = System.currentTimeMillis() / 1000L
        val dispatchResponse = apiRequest(
            method = "POST",
            url = "$API/repos/$owner/$repo/actions/workflows/build-generated.yml/dispatches",
            config = config,
            body = dispatchBody.toString()
        ).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError("Workflow dispatch", response.code, body)
            body
        }

        var runId = runCatching {
            JSONObject(dispatchResponse).optLong("workflow_run_id", 0L)
        }.getOrDefault(0L)

        if (runId <= 0L) {
            onProgress("Menunggu antrean build…")
            runId = findRunId(owner, repo, branch, slug, dispatchedAt, config)
        }

        onProgress("Build berjalan di GitHub Actions. Jangan tutup proses sebelum selesai…")
        var runUrl = ""
        var completed = false
        var attempts = 0
        while (attempts < 150 && !completed) {
            val run = getJson(
                "$API/repos/$owner/$repo/actions/runs/$runId",
                config
            )
            val status = run.optString("status")
            val conclusion = run.optString("conclusion")
            runUrl = run.optString("html_url")
            if (status == "completed") {
                completed = true
                if (conclusion != "success") {
                    throw IllegalStateException("GitHub Actions gagal ($conclusion). Detail: $runUrl")
                }
            } else {
                onProgress("Build APK: ${status.ifBlank { "menunggu" }}…")
                delay(5_000)
            }
            attempts++
        }
        if (!completed) throw IllegalStateException("Build belum selesai setelah 12,5 menit. Periksa workflow: $runUrl")

        onProgress("Build selesai. Mengambil APK…")
        val artifacts = getJson(
            "$API/repos/$owner/$repo/actions/runs/$runId/artifacts",
            config
        ).optJSONArray("artifacts") ?: throw IllegalStateException("GitHub tidak mengembalikan daftar artifact.")

        val artifactName = "generated-apk-$slug"
        var artifactId = 0L
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.optJSONObject(index) ?: continue
            if (artifact.optString("name") == artifactName && !artifact.optBoolean("expired", false)) {
                artifactId = artifact.optLong("id")
                break
            }
        }
        if (artifactId <= 0L) throw IllegalStateException("Artifact APK tidak ditemukan. Periksa hasil workflow: $runUrl")

        val outputDir = File(context.cacheDir, "generated").apply { mkdirs() }
        val apkFile = File(outputDir, "$slug.apk")
        if (apkFile.exists()) apkFile.delete()
        val downloadRequest = requestBuilder(
            "$API/repos/$owner/$repo/actions/artifacts/$artifactId/zip",
            config
        ).get().build()

        client.newCall(downloadRequest).execute().use { response ->
            if (!response.isSuccessful) throw apiError("Download artifact", response.code, response.body?.string().orEmpty())
            val input = response.body?.byteStream() ?: throw IllegalStateException("File artifact kosong.")
            var found = false
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true)) {
                        var total = 0L
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_APK_BYTES) { "APK melebihi batas 180 MB." }
                                output.write(buffer, 0, count)
                            }
                        }
                        found = true
                        break
                    }
                    zip.closeEntry()
                }
            }
            if (!found || !apkFile.isFile || apkFile.length() == 0L) {
                apkFile.delete()
                throw IllegalStateException("Artifact berhasil diunduh, tetapi file APK tidak ditemukan di dalamnya.")
            }
        }
        onProgress("APK siap: ${apkFile.name}")
        apkFile
    }

    private suspend fun findRunId(
        owner: String,
        repo: String,
        branch: String,
        slug: String,
        dispatchedAt: Long,
        config: BuildConfig
    ): Long {
        repeat(30) {
            val url = "$API/repos/$owner/$repo/actions/workflows/build-generated.yml/runs?branch=${encode(branch)}&event=workflow_dispatch&per_page=20"
            val runs = getJson(url, config).optJSONArray("workflow_runs")
            if (runs != null) {
                for (index in 0 until runs.length()) {
                    val run = runs.optJSONObject(index) ?: continue
                    val title = run.optString("display_title")
                    val createdAt = run.optString("created_at")
                    val recentEnough = createdAt.isBlank() || runCatching {
                        Instant.parse(createdAt).epochSecond >= dispatchedAt - 90
                    }.getOrDefault(false)
                    if (title.contains(slug) && recentEnough) {
                        val id = run.optLong("id", 0L)
                        if (id > 0L) return id
                    }
                }
            }
            delay(3_000)
        }
        throw IllegalStateException("Workflow sudah dipanggil, tetapi ID build tidak ditemukan. Periksa tab Actions di repository.")
    }

    private fun getJson(url: String, config: BuildConfig): JSONObject {
        apiRequest("GET", url, config).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError("GitHub API", response.code, body)
            return JSONObject(body)
        }
    }

    private fun apiRequest(
        method: String,
        url: String,
        config: BuildConfig,
        body: String? = null
    ): okhttp3.Response {
        val builder = requestBuilder(url, config)
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: "{}").toRequestBody(jsonType))
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType))
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }
        return client.newCall(builder.build()).execute()
    }

    private fun requestBuilder(url: String, config: BuildConfig): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.githubToken.trim()}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "BuildAPP-Android")

    private fun validateConfig(config: BuildConfig) {
        require(config.githubOwner.matches(Regex("^[A-Za-z0-9-]{1,39}$"))) { "GitHub owner tidak valid." }
        require(config.githubRepo.matches(Regex("^[A-Za-z0-9_.-]{1,100}$"))) { "Nama repository tidak valid." }
        require(config.githubToken.isNotBlank()) { "GitHub token belum diisi di Settings." }
        require(config.githubBranch.isNotBlank()) { "Branch GitHub belum diisi." }
    }

    private fun validateProject(project: ProjectSpec) {
        require(project.name.isNotBlank() && project.name.length <= 40) { "Nama aplikasi wajib diisi (maksimal 40 karakter)." }
        require(project.packageId.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))) {
            "Package ID tidak valid. Contoh: com.tein.generated.aplikasi"
        }
        require(project.html.contains("<html", ignoreCase = true) && project.html.contains("</html>", ignoreCase = true)) {
            "Dokumen HTML belum lengkap. Generate ulang sebelum build."
        }
        require(project.html.toByteArray(Charsets.UTF_8).size <= MAX_HTML_BYTES) {
            "Ukuran HTML melebihi batas 2 MB. Sederhanakan aplikasi sebelum build."
        }
        val allowedPermissions = setOf("ACCESS_NETWORK_STATE", "CAMERA", "RECORD_AUDIO", "VIBRATE")
        require(project.permissions.all { it in allowedPermissions }) { "Daftar permission berisi nilai yang tidak didukung." }
        require(project.iconBase64.length <= MAX_ICON_BASE64_CHARS) { "Logo terlalu besar. Pilih gambar lain yang lebih kecil." }
    }

    private fun makeSlug(name: String): String {
        val prefix = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(20)
            .ifBlank { "app" }
        return "$prefix-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(6)}"
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun apiError(operation: String, code: Int, body: String): Exception {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        return IllegalStateException("$operation gagal ($code): ${message.ifBlank { body.take(220) }}")
    }

    private const val MAX_APK_BYTES = 180L * 1024L * 1024L
    private const val MAX_HTML_BYTES = 2L * 1024L * 1024L
    private const val MAX_ICON_BASE64_CHARS = 2_800_000
}
