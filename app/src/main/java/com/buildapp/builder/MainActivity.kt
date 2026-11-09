package com.buildapp.builder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.buildapp.builder.data.BuildConfig
import com.buildapp.builder.data.BuildConfigStore
import com.buildapp.builder.network.GitHubBuildClient
import com.buildapp.builder.network.OpenRouterClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF080D18)
private val Panel = Color(0xFF111A2B)
private val PanelRaised = Color(0xFF18243A)
private val Stroke = Color(0xFF293B59)
private val Muted = Color(0xFFA5B2C8)
private val Bright = Color(0xFFF4F7FF)
private val Accent = Color(0xFF70A9FF)
private val AccentViolet = Color(0xFF9C8BFF)
private val Success = Color(0xFF72E0B0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = Typography()
        val mono = FontFamily.Monospace
        val monoTypography = base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = mono),
            displayMedium = base.displayMedium.copy(fontFamily = mono),
            displaySmall = base.displaySmall.copy(fontFamily = mono),
            headlineLarge = base.headlineLarge.copy(fontFamily = mono),
            headlineMedium = base.headlineMedium.copy(fontFamily = mono),
            headlineSmall = base.headlineSmall.copy(fontFamily = mono),
            titleLarge = base.titleLarge.copy(fontFamily = mono),
            titleMedium = base.titleMedium.copy(fontFamily = mono),
            titleSmall = base.titleSmall.copy(fontFamily = mono),
            bodyLarge = base.bodyLarge.copy(fontFamily = mono),
            bodyMedium = base.bodyMedium.copy(fontFamily = mono),
            bodySmall = base.bodySmall.copy(fontFamily = mono),
            labelLarge = base.labelLarge.copy(fontFamily = mono),
            labelMedium = base.labelMedium.copy(fontFamily = mono),
            labelSmall = base.labelSmall.copy(fontFamily = mono)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Ink,
                    background = Ink,
                    surface = Panel,
                    surfaceVariant = PanelRaised,
                    onSurface = Bright,
                    onSurfaceVariant = Muted,
                    outline = Stroke
                ),
                typography = monoTypography
            ) {
                BuildAppScreen()
            }
        }
    }
}

private data class LogoPayload(
    val base64: String,
    val bitmap: Bitmap,
    val fileName: String
)

private data class BuildHistoryItem(
    val name: String,
    val packageId: String,
    val apkPath: String,
    val createdAt: Long
)

@Composable
private fun BuildAppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configStore = remember { BuildConfigStore(context) }
    var config by remember { mutableStateOf(configStore.load()) }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showAllPermissions by rememberSaveable { mutableStateOf(false) }
    var showAdvancedSettings by rememberSaveable { mutableStateOf(false) }
    var projectName by rememberSaveable { mutableStateOf("Aplikasi Baru") }
    var packageId by rememberSaveable { mutableStateOf(createDefaultPackageId()) }
    var versionCodeInput by rememberSaveable { mutableStateOf("1") }
    var versionNameInput by rememberSaveable { mutableStateOf("1.0") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var logo by remember { mutableStateOf<LogoPayload?>(null) }
    var optionalPermissions by remember { mutableStateOf(setOf<String>()) }
    var generatedHtml by remember { mutableStateOf<String?>(null) }
    var generatedApk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SIAP // menunggu instruksi") }
    var previewVersion by remember { mutableStateOf(0) }

    val history = remember {
        mutableStateListOf<BuildHistoryItem>().apply { addAll(loadBuildHistory(context)) }
    }

    val saveHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri: Uri? ->
        val html = generatedHtml
        if (uri != null && html != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(html.toByteArray(Charsets.UTF_8))
                } ?: error("Tidak dapat membuka lokasi penyimpanan.")
            }.onSuccess { toast(context, "HTML tersimpan.") }
                .onFailure { toast(context, "Gagal menyimpan: ${it.message}") }
        }
    }

    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        val apk = generatedApk
        if (uri != null && apk != null && apk.exists()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> apk.inputStream().use { it.copyTo(output) } }
                    ?: error("Tidak dapat membuka lokasi penyimpanan.")
            }.onSuccess { toast(context, "APK berhasil disimpan.") }
                .onFailure { toast(context, "Gagal menyimpan APK: ${it.message}") }
        }
    }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching { readLogo(context, uri) }
                    .onSuccess {
                        logo = it
                        status = "LOGO SIAP // ${it.fileName}"
                    }
                    .onFailure { status = "LOGO ERROR // ${it.message}" }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Brand bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Panel)
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showMenu = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                ) { Text("≡", fontSize = 20.sp) }
                Column(Modifier.weight(1f)) {
                    Text("Build.APP", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("ANDROID PROJECT BUILDER", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp)
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF20242B)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = com.buildapp.builder.R.drawable.ic_build_app),
                        contentDescription = "Build.APP",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("IDE JADI APK.", color = Bright, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp)
                Text("Ceritakan aplikasi atau game yang ingin kamu buat. AI akan menyiapkan project untuk dipreview dan dibuild.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Success))
                    Text("AI BUILDER  •  ANDROID APK", color = Accent, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                    .background(PanelRaised).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("PENGATURAN PROJECT", color = Bright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Nama, ikon, package ID, dan permission", color = Muted, fontSize = 9.sp)
                }
                TextButton(onClick = { showAdvancedSettings = !showAdvancedSettings }) {
                    Text(if (showAdvancedSettings) "TUTUP  −" else "ATUR  +", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            androidx.compose.animation.AnimatedVisibility(visible = showAdvancedSettings) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(number = "01", title = "IDENTITAS PROJECT", subtitle = "NAMA DAN PACKAGE APLIKASI") {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nama aplikasi") },
                    placeholder = { Text("Contoh: flappyBird") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = packageId,
                    onValueChange = {
                        packageId = it.lowercase(Locale.US)
                            .filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }
                            .take(120)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Package ID") },
                    supportingText = { Text("com.tein.generated.aplikasi") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = versionNameInput,
                        onValueChange = { versionNameInput = it.filter { c -> c.isLetterOrDigit() || c in "._+-" }.take(30) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Version name") },
                        placeholder = { Text("1.1") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = versionCodeInput,
                        onValueChange = { versionCodeInput = it.filter(Char::isDigit).take(9) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Version code") },
                        placeholder = { Text("2") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            SectionCard(number = "02", title = "LOGO (OPSIONAL)", subtitle = "ICON LAUNCHER APK") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0xFF202328)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (logo != null) {
                            Image(
                                bitmap = logo!!.bitmap.asImageBitmap(),
                                contentDescription = "Logo aplikasi",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("APP", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(logo?.fileName ?: "Belum ada logo", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("PNG / JPG / WEBP · otomatis dipotong persegi", color = Muted, fontSize = 9.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { logoPicker.launch("image/*") },
                        shape = RoundedCornerShape(11.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 9.dp)
                    ) { Text(if (logo == null) "Pilih logo" else "Ganti logo", fontSize = 11.sp) }
                    if (logo != null) {
                        OutlinedButton(
                            onClick = { logo = null },
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 9.dp)
                        ) { Text("Hapus", fontSize = 11.sp) }
                    }
                }
            }

            SectionCard(number = "03", title = "PERMISSION", subtitle = "AKSES YANG DITAMBAHKAN KE APK") {
                PermissionRow(label = "INTERNET", detail = "selalu aktif", checked = true, enabled = false, onCheckedChange = {})
                PermissionRow(
                    label = "ACCESS_NETWORK_STATE",
                    detail = "status koneksi internet",
                    checked = "ACCESS_NETWORK_STATE" in optionalPermissions,
                    onCheckedChange = { optionalPermissions = toggleSet(optionalPermissions, "ACCESS_NETWORK_STATE", it) }
                )
                PermissionRow(
                    label = "CAMERA",
                    detail = "kamera melalui WebView saat diminta",
                    checked = "CAMERA" in optionalPermissions,
                    onCheckedChange = { optionalPermissions = toggleSet(optionalPermissions, "CAMERA", it) }
                )
                if (showAllPermissions) {
                    PermissionRow(
                        label = "RECORD_AUDIO",
                        detail = "mikrofon / perekaman web",
                        checked = "RECORD_AUDIO" in optionalPermissions,
                        onCheckedChange = { optionalPermissions = toggleSet(optionalPermissions, "RECORD_AUDIO", it) }
                    )
                    PermissionRow(
                        label = "VIBRATE",
                        detail = "getaran perangkat",
                        checked = "VIBRATE" in optionalPermissions,
                        onCheckedChange = { optionalPermissions = toggleSet(optionalPermissions, "VIBRATE", it) }
                    )
                }
                OutlinedButton(
                    onClick = { showAllPermissions = !showAllPermissions },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text(if (showAllPermissions) "SEMBUNYIKAN PERMISSION" else "TAMPILKAN SEMUA (+)", fontSize = 10.sp)
                }
                Text(
                    "Camera / microphone meminta izin Android saat fitur web benar-benar memakainya.",
                    color = Muted,
                    fontSize = 9.sp
                )
            }

            }
            }

            SectionCard(number = "04", title = "DESKRIPSI IDE", subtitle = "APA YANG INGIN KAMU BANGUN?") {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { prompt = "Buat aplikasi Android WebView yang berfungsi sebagai dashboard modern. Sertakan navigasi, penyimpanan lokal, validasi input, dan tampilan responsif." },
                        label = { Text("APP", fontSize = 10.sp) },
                        shape = RoundedCornerShape(10.dp)
                    )
                    FilterChip(
                        selected = false,
                        onClick = { prompt = "Buat game 2D mobile yang ringan dan responsif untuk sentuhan. Sertakan menu mulai, skor, pause, game over, tombol ulangi, dan simpan skor tertinggi." },
                        label = { Text("GAME 2D", fontSize = 10.sp) },
                        shape = RoundedCornerShape(10.dp)
                    )
                    FilterChip(
                        selected = false,
                        onClick = { prompt = "Buat aplikasi utilitas sederhana dengan UI modern, fungsi utama yang benar-benar berjalan, validasi, dan penyimpanan lokal." },
                        label = { Text("UTILITY", fontSize = 10.sp) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(18_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Apa yang ingin dibuat?") },
                    placeholder = { Text("Contoh: Buat game Flappy Bird dengan 3 tema warna...") },
                    minLines = 5,
                    maxLines = 10,
                    shape = RoundedCornerShape(13.dp)
                )
                Button(
                    onClick = {
                        if (projectName.isBlank()) {
                            status = "ERROR // nama project wajib diisi"
                            return@Button
                        }
                        if (!isValidPackageId(packageId)) {
                            status = "ERROR // package ID tidak valid"
                            return@Button
                        }
                        if (prompt.isBlank()) {
                            status = "ERROR // prompt masih kosong"
                            return@Button
                        }
                        if (config.openRouterKey.isBlank()) {
                            showSettings = true
                            status = "SETUP // masukkan OpenRouter API key di Settings"
                            return@Button
                        }
                        busy = true
                        generatedHtml = null
                        generatedApk = null
                        status = "AI // mengirim instruksi..."
                        scope.launch {
                            try {
                                generatedHtml = OpenRouterClient.generateHtml(prompt, config) { stage ->
                                    withContext(Dispatchers.Main) {
                                        status = "AI // $stage"
                                    }
                                }
                                previewVersion++
                                status = "OK // HTML dibuat. Periksa preview sebelum build."
                            } catch (error: Exception) {
                                status = "AI ERROR // ${error.message ?: "generate gagal"}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Bright, contentColor = Ink)
                ) {
                    if (busy && generatedHtml == null) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Ink)
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(if (busy && generatedHtml == null) "AI SEDANG MEMBUAT..." else "BUAT PROJECT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            StatusPanel(status = status, busy = busy)

            generatedHtml?.let { html ->
                SectionCard(number = "05", title = "PREVIEW", subtitle = "CEK HASIL SEBELUM BUILD APK") {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(390.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color.White),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                setBackgroundColor(android.graphics.Color.WHITE)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.setSupportMultipleWindows(false)
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                webViewClient = WebViewClient()
                                tag = previewVersion
                                loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
                            }
                        },
                        update = { webView ->
                            if (webView.tag != previewVersion) {
                                webView.tag = previewVersion
                                webView.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
                            }
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { saveHtmlLauncher.launch("${fileSlug(projectName)}.html") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp)
                        ) { Text("SIMPAN HTML", fontSize = 10.sp) }
                        Button(
                            onClick = {
                                if (!isValidPackageId(packageId)) {
                                    status = "ERROR // package ID tidak valid"
                                    return@Button
                                }
                                if ((versionCodeInput.toIntOrNull() ?: 0) < 1 || versionNameInput.isBlank()) {
                                    status = "ERROR // version name/code tidak valid"
                                    return@Button
                                }
                                if (config.githubOwner.isBlank() || config.githubRepo.isBlank() || config.githubToken.isBlank()) {
                                    showSettings = true
                                    status = "SETUP // isi GitHub di Settings untuk build APK"
                                    return@Button
                                }
                                busy = true
                                generatedApk = null
                                status = "BUILD // menyiapkan source..."
                                scope.launch {
                                    try {
                                        val apk = GitHubBuildClient.buildApk(
                                            context = context,
                                            config = config,
                                            project = GitHubBuildClient.ProjectSpec(
                                                name = projectName,
                                                packageId = packageId,
                                                html = html,
                                                permissions = optionalPermissions.toList(),
                                                iconBase64 = logo?.base64.orEmpty(),
                                                versionCode = versionCodeInput.toInt(),
                                                versionName = versionNameInput
                                            )
                                        ) { message -> status = "BUILD // $message" }
                                        generatedApk = apk
                                        val item = BuildHistoryItem(projectName, packageId, apk.absolutePath, System.currentTimeMillis())
                                        history.removeAll { it.packageId == packageId }
                                        history.add(0, item)
                                        while (history.size > 6) history.removeAt(history.lastIndex)
                                        saveBuildHistory(context, history)
                                        status = "SUCCESS // APK selesai dibuat"
                                    } catch (error: Exception) {
                                        status = "BUILD ERROR // ${error.message ?: "build gagal"}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Bright, contentColor = Ink)
                        ) { Text(if (busy) "BUILDING..." else "BUILD APK", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            generatedApk?.let { apk ->
                ApkResultCard(
                    name = projectName,
                    apk = apk,
                    iconBitmap = logo?.bitmap,
                    onInstall = { installApk(context, apk) },
                    onSave = { saveApkLauncher.launch(apk.name) },
                    onShare = { shareApk(context, apk) }
                )
            }

            if (history.isNotEmpty()) {
                SectionCard(number = "06", title = "PROJECT SELESAI", subtitle = "RIWAYAT BUILD DI PERANGKAT") {
                    history.take(5).forEachIndexed { index, item ->
                        HistoryRow(
                            item = item,
                            index = index + 1,
                            onOpen = {
                                val file = File(item.apkPath)
                                if (file.exists() && file.length() > 0L) {
                                    generatedApk = file
                                    projectName = item.name
                                    packageId = item.packageId
                                    status = "HISTORY // ${item.name} siap digunakan"
                                } else {
                                    status = "HISTORY ERROR // file APK tidak lagi tersedia"
                                }
                            }
                        )
                    }
                }
            }

            Text(
                "OUTPUT: ANDROID WEB APP / GAME 2D  •  BUILD: GITHUB ACTIONS",
                color = Muted,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = config,
            onDismiss = { showSettings = false },
            onSave = { updated ->
                configStore.save(updated)
                config = updated
                showSettings = false
                status = "SETTINGS // tersimpan secara lokal"
            }
        )
    }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            containerColor = Panel,
            shape = RoundedCornerShape(20.dp),
            title = { Text("BUILD.APP MENU", fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { showMenu = false; showSettings = true }) { Text("SETTINGS  /  API & GITHUB") }
                    TextButton(onClick = { showMenu = false; status = "INFO // Build.APP membuat APK berbasis WebView." }) { Text("ABOUT  /  VERSION INFO") }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            showMenu = false
                            projectName = "Aplikasi Baru"
                            packageId = createDefaultPackageId()
                            prompt = ""
                            logo = null
                            optionalPermissions = emptySet()
                            showAllPermissions = false
                            showAdvancedSettings = false
                            generatedHtml = null
                            generatedApk = null
                            previewVersion++
                            status = "READY // project baru"
                        }
                    ) { Text("NEW PROJECT  /  RESET PROJECT") }
                }
            },
            confirmButton = { TextButton(onClick = { showMenu = false }) { Text("TUTUP") } }
        )
    }
}

@Composable
private fun SectionCard(
    number: String,
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(21.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFE7E9ED)),
                        contentAlignment = Alignment.Center
                    ) { Text(number, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Column {
                        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                        Text(subtitle, color = Muted, fontSize = 8.sp, letterSpacing = 0.7.sp)
                    }
                }
                content()
            }
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 10.sp, color = if (checked) Bright else Color(0xFFB4B8C0))
            Text(detail, fontSize = 8.sp, color = Muted)
        }
        if (!enabled) Text("AUTO", fontSize = 8.sp, color = Success)
    }
}

@Composable
private fun StatusPanel(status: String, busy: Boolean) {
    val isSuccess = status.startsWith("SUCCESS") || status.startsWith("OK")
    val isError = status.contains("ERROR")
    val stage = when {
        status.startsWith("AI") -> "AI GENERATION"
        status.startsWith("BUILD") -> "ANDROID BUILD"
        isSuccess -> "PROJECT READY"
        isError -> "NEEDS ATTENTION"
        else -> "SYSTEM STATUS"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stage, color = if (isError) Color(0xFFFF8C91) else Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(status.substringAfter("//", status).trim(), color = Bright, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Accent)
                } else {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (isSuccess) Success else if (isError) Color(0xFFFF8C91) else Muted))
                }
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = Accent,
                    trackColor = PanelRaised
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("PROSES BERJALAN", color = Muted, fontSize = 8.sp, letterSpacing = 0.8.sp)
                    Text("JANGAN TUTUP APLIKASI", color = Muted, fontSize = 8.sp, letterSpacing = 0.5.sp)
                }
            }
        }
    }
}

@Composable
private fun ApkResultCard(
    name: String,
    apk: File,
    iconBitmap: Bitmap?,
    onInstall: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102B29)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28665B))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(62.dp).clip(RoundedCornerShape(17.dp)).background(PanelRaised),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = "Ikon $name",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(id = com.buildapp.builder.R.drawable.ic_build_app),
                            contentDescription = "Ikon aplikasi",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("APK BERHASIL DIBUAT", color = Success, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${apk.length() / 1024} KB  •  SIAP DIPASANG", color = Muted, fontSize = 9.sp)
                }
            }
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Bright, contentColor = Ink)
            ) { Text("INSTAL APLIKASI", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("SIMPAN APK", fontSize = 10.sp) }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("BAGIKAN", fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: BuildHistoryItem, index: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelRaised)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF20242B)), contentAlignment = Alignment.Center) {
            Text("$index", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.packageId, fontSize = 8.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onOpen) { Text("BUKA", fontSize = 9.sp) }
    }
}

@Composable
private fun SettingsDialog(
    initial: BuildConfig,
    onDismiss: () -> Unit,
    onSave: (BuildConfig) -> Unit
) {
    var apiKey by remember(initial) { mutableStateOf(initial.openRouterKey) }
    var modelId by remember(initial) { mutableStateOf(initial.modelId) }
    var owner by remember(initial) { mutableStateOf(initial.githubOwner) }
    var repo by remember(initial) { mutableStateOf(initial.githubRepo) }
    var branch by remember(initial) { mutableStateOf(initial.githubBranch) }
    var token by remember(initial) { mutableStateOf(initial.githubToken) }
    val modelScope = rememberCoroutineScope()
    var modelOptions by remember { mutableStateOf<List<OpenRouterClient.ModelOption>>(emptyList()) }
    var modelDropdownOpen by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf("Katalog model belum dimuat.") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(20.dp),
        title = { Text("SETTINGS", fontSize = 15.sp) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("OPENROUTER AI", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenRouter API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp)
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it.trim().take(180) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model ID") },
                    supportingText = { Text("Bisa pilih otomatis atau masukkan model ID sendiri.") },
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled = !loadingModels && apiKey.isNotBlank(),
                        onClick = {
                            loadingModels = true
                            modelStatus = "Menghubungi OpenRouter dan mengambil katalog model…"
                            modelScope.launch {
                                try {
                                    modelOptions = OpenRouterClient.listModels(apiKey)
                                    modelDropdownOpen = modelOptions.isNotEmpty()
                                    modelStatus = "Terhubung · ${modelOptions.size} model teks ditemukan."
                                } catch (error: Exception) {
                                    modelStatus = "Katalog gagal dimuat: ${error.message ?: "periksa API key dan koneksi."}"
                                } finally {
                                    loadingModels = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (loadingModels) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(if (loadingModels) "MEMUAT…" else "MUAT KATALOG MODEL", fontSize = 9.sp)
                    }
                    TextButton(onClick = {
                        modelId = "openrouter/free"
                        modelStatus = "Menggunakan router gratis otomatis. Batas kuota provider tetap berlaku."
                    }) {
                        Text("AUTO FREE", fontSize = 9.sp)
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = modelDropdownOpen,
                    onDismissRequest = { modelDropdownOpen = false },
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    modelOptions.take(30).forEach { option ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(option.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${option.id} · ${option.contextLabel} · ${option.priceLabel}",
                                        fontSize = 8.sp,
                                        color = Muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            onClick = {
                                modelId = option.id
                                modelDropdownOpen = false
                                modelStatus = "Model dipilih: ${option.id}"
                            }
                        )
                    }
                }
                Text(modelStatus, color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                Text(
                    "Model gratis tetap memiliki batas penggunaan dan dapat berubah ketersediaannya.",
                    color = Muted,
                    fontSize = 8.sp,
                    lineHeight = 12.sp
                )
                Text("GITHUB ACTIONS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(owner, { owner = it }, Modifier.fillMaxWidth(), label = { Text("GitHub owner") }, singleLine = true, shape = RoundedCornerShape(11.dp))
                OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text("Repository") }, singleLine = true, shape = RoundedCornerShape(11.dp))
                OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("Branch") }, singleLine = true, shape = RoundedCornerShape(11.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Fine-grained GitHub token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp)
                )
                Text("Token dan API key disimpan terenkripsi di perangkat. Jangan bagikan kredensial melalui chat.", color = Muted, fontSize = 9.sp)
                Text("Izin token: Contents Read/Write + Actions Read/Write. Gunakan repository privat.", color = Muted, fontSize = 9.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    BuildConfig(
                        openRouterKey = apiKey.trim(),
                        modelId = modelId.trim().ifBlank { "openrouter/free" },
                        githubOwner = owner.trim(),
                        githubRepo = repo.trim(),
                        githubBranch = branch.trim().ifBlank { "main" },
                        githubToken = token.trim()
                    )
                )
            }) { Text("SIMPAN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )
}

private suspend fun readLogo(context: Context, uri: Uri): LogoPayload = withContext(Dispatchers.IO) {
    val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: throw IllegalArgumentException("File gambar tidak dapat dibaca.")
    val longest = maxOf(source.width, source.height).coerceAtLeast(1)
    val scale = minOf(1f, 512f / longest)
    val resized = Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true
    )
    val bytes = ByteArrayOutputStream().use { output ->
        resized.compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }
    require(bytes.size <= 2_000_000) { "Ukuran logo terlalu besar setelah diproses." }
    val fileName = queryDisplayName(context, uri) ?: "custom-logo.png"
    LogoPayload(Base64.encodeToString(bytes, Base64.NO_WRAP), resized, fileName)
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun loadBuildHistory(context: Context): List<BuildHistoryItem> = runCatching {
    val raw = context.getSharedPreferences("build_history", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    val array = JSONArray(raw)
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                BuildHistoryItem(
                    item.optString("name"),
                    item.optString("packageId"),
                    item.optString("apkPath"),
                    item.optLong("createdAt")
                )
            )
        }
    }
}.getOrDefault(emptyList())

private fun saveBuildHistory(context: Context, items: List<BuildHistoryItem>) {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject().apply {
                put("name", item.name)
                put("packageId", item.packageId)
                put("apkPath", item.apkPath)
                put("createdAt", item.createdAt)
            }
        )
    }
    context.getSharedPreferences("build_history", Context.MODE_PRIVATE)
        .edit().putString("items", array.toString()).apply()
}

private fun toggleSet(current: Set<String>, value: String, enabled: Boolean): Set<String> =
    if (enabled) current + value else current - value

private fun installApk(context: Context, apk: File) {
    if (!apk.exists() || apk.length() == 0L) {
        toast(context, "File APK tidak ditemukan.")
        return
    }
    if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(context, "Aktifkan izin instal aplikasi untuk Build.APP.") }
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { toast(context, "Tidak dapat membuka installer: ${it.message}") }
}

private fun shareApk(context: Context, apk: File) {
    if (!apk.exists()) {
        toast(context, "APK tidak ditemukan.")
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan APK"))
    }.onFailure { toast(context, "Gagal membagikan APK: ${it.message}") }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun isValidPackageId(value: String): Boolean =
    value.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))

private fun createDefaultPackageId(): String {
    val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
    return "com.tein.generated.a$suffix"
}

private fun fileSlug(value: String): String = value.lowercase(Locale.US)
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .take(32)
    .ifBlank { "generated-app" }
