# Build.APP — Android App & 2D Game Builder

Build.APP adalah aplikasi Android yang membantu membuat aplikasi WebView dan game 2D berbasis HTML/CSS/JavaScript, melihat preview, lalu membangun APK melalui GitHub Actions.

## Fitur

- UI gelap dengan font monospace dan komponen rounded.
- Nama aplikasi dan `applicationId` bisa diatur sebelum build.
- Upload logo opsional; workflow mengubahnya menjadi ikon launcher untuk APK hasil.
- Permission INTERNET selalu aktif; pilihan tambahan ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, dan VIBRATE diteruskan ke AndroidManifest hasil.
- Kamera dan mikrofon pada WebView meminta izin Android saat halaman benar-benar meminta akses melalui `getUserMedia`. Permission tidak dipaksakan saat aplikasi dibuka.
- Preset prompt APP, GAME 2D, dan UTILITY.
- Generate HTML melalui OpenRouter dengan preview WebView sebelum build.
- Simpan HTML, build APK, instal, simpan, bagikan, dan riwayat build lokal.
- API key dan GitHub token disimpan terenkripsi menggunakan Android Keystore.

## Batasan produk

Build.APP versi ini membuat APK berbasis WebView, bukan compiler Kotlin/Java native, Unity, Godot, atau game 3D. Output yang dibuat AI harus berupa satu dokumen HTML dengan CSS dan JavaScript inline. Fitur Android native yang tidak dijembatani (misalnya Bluetooth, sensor, notifikasi native, atau background service) tidak otomatis tersedia hanya karena prompt menyebutkannya. Kamera/mikrofon dapat digunakan melalui API web yang kompatibel dan permission Android yang dipilih.

## Stack build

| Komponen | Versi |
|---|---|
| Android Gradle Plugin | 8.6.1 |
| Gradle | 8.9 |
| Kotlin | 2.0.21 |
| JDK | 17 |
| compileSdk | 35 |
| targetSdk | 34 |
| minSdk Builder | 26 (Android 8.0+) |
| minSdk APK hasil | 24 (Android 7.0+) |

AGP 8.6 mendukung API 35 dan membutuhkan Gradle minimal 8.7 serta JDK 17. Konfigurasi menggunakan Gradle 8.9.

## Struktur

- `app/`: aplikasi Builder Jetpack Compose.
- `generated-template/`: proyek Android WebView yang dikompilasi untuk setiap output.
- `.github/workflows/build-builder.yml`: membangun APK Build.APP.
- `.github/workflows/build-generated.yml`: mengompilasi template menjadi APK hasil.

## Setup pertama

1. Buat repository GitHub privat dan unggah seluruh isi proyek ini ke root repository.
2. Aktifkan GitHub Actions. Pastikan file `.github/workflows/build-generated.yml` ada pada branch yang dikonfigurasi.
3. Buka Build.APP → menu → Settings.
4. Masukkan OpenRouter API key, GitHub owner, nama repository, branch (`main`), dan fine-grained personal access token.
5. Token harus memiliki repository permissions: **Contents: Read and write** dan **Actions: Read and write**.
6. Buat APK Builder melalui Actions → Build Build.APP → Run workflow, atau build modul `app` melalui Android Studio/AndroidForge.

Jangan masukkan token atau API key ke source code maupun prompt. Simpan repository privat karena HTML yang dikirim untuk build disimpan pada `build-inputs/<project-slug>/project.json`.

## Alur penggunaan

1. Isi nama aplikasi dan package ID valid, misalnya `com.tein.generated.aplikasiku`.
2. Pilih logo (opsional).
3. Pilih permission tambahan yang memang dibutuhkan aplikasi. INTERNET selalu disertakan.
4. Isi prompt atau pilih preset APP / GAME 2D / UTILITY.
5. Tekan **GENERATE PROJECT**, lalu cek hasil di Preview.
6. Tekan **BUILD APK**. Build.APP mengunggah input ke repository, menjalankan workflow, menunggu hasil, lalu mengunduh artifact APK.
7. Gunakan Instal APK, Simpan, atau Bagikan. Pada pemasangan pertama, Android dapat meminta izin instal aplikasi dari Build.APP.

## Tentang signing

Workflow menggunakan debug signing key yang dibuat oleh runner GitHub Actions. APK dapat dipasang untuk pengujian. Untuk menerbitkan pembaruan berkelanjutan ke package ID yang sama, siapkan release keystore tetap di GitHub Actions secrets. Karena debug key sementara dapat berubah antar build, APK baru dengan package ID yang sama mungkin memerlukan penghapusan versi sebelumnya terlebih dahulu.

## Pemeriksaan

Pemeriksaan sumber mencakup parser XML/YAML dan validasi struktur project. Build APK end-to-end harus dibuktikan oleh GitHub Actions setelah source diunggah; jangan menganggap build berhasil sebelum workflow berstatus `success` dan artifact APK tersedia.
