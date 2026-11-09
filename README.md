# Build.APP — Android App & 2D Game Builder

Build.APP adalah aplikasi Android yang membantu membuat aplikasi WebView dan game 2D berbasis HTML/CSS/JavaScript, melihat preview, lalu membangun APK melalui GitHub Actions.

## Fitur

- UI gelap dengan font monospace dan komponen rounded.
- Nama aplikasi dan `applicationId` bisa diatur sebelum build.
- Upload logo opsional; workflow mengubahnya menjadi ikon launcher untuk APK hasil.
- Permission INTERNET selalu aktif; pilihan tambahan ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, dan VIBRATE diteruskan ke AndroidManifest hasil.
- Kamera dan mikrofon pada WebView meminta izin Android saat halaman benar-benar meminta akses melalui `getUserMedia`. Permission tidak dipaksakan saat aplikasi dibuka.
- Profil generasi LITE, STUDIO, dan GAME 2D untuk mengarahkan hasil AI sesuai kebutuhan.
- Katalog model teks OpenRouter dimuat langsung dari API; nama model dan harga tidak dikunci di source.
- Router `openrouter/free` tersedia sebagai pilihan otomatis, dengan catatan kuota provider tetap berlaku.
- Generate HTML melalui OpenRouter dengan preview WebView sebelum build.
- Nama aplikasi, package ID, version name, version code, ikon, dan permission dapat diatur.
- Simpan HTML, build APK, instal, simpan, bagikan, dan riwayat build lokal.
- API key dan GitHub token disimpan terenkripsi menggunakan Android Keystore.

## Batasan produk

Build.APP pada branch ini tetap menghasilkan APK berbasis WebView, bukan compiler Kotlin/Java native, Unity, Godot, atau game 3D. Output AI masih berupa satu dokumen HTML dengan CSS dan JavaScript inline. Profil LITE/STUDIO/GAME mengubah arahan generasi, bukan mengganti mesin runtime. Fitur Android native yang tidak dijembatani (misalnya Bluetooth, sensor, notifikasi native, atau background service) tidak otomatis tersedia hanya karena prompt menyebutkannya. Kamera/mikrofon dapat digunakan melalui API web yang kompatibel dan permission Android yang dipilih.

Provider AI yang aktif pada implementasi ini adalah OpenRouter. Integrasi Gemini yang aman melalui Firebase AI Logic/App Check memerlukan Firebase project milik developer sendiri; konfigurasi atau credential dari APK pihak lain tidak digunakan. API key OpenRouter saat ini disimpan terenkripsi di perangkat, tetapi untuk distribusi publik jangka panjang sebaiknya dipindahkan ke backend proxy dengan autentikasi, pembatasan kuota, dan rate limit.

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
4. Masukkan OpenRouter API key, GitHub owner, nama repository build, branch (`main`), dan fine-grained personal access token.
5. Repository yang diisi pada Settings harus **PRIVATE**. Repository source Build.APP boleh terpisah dari repository privat yang menampung input build.
6. Token harus memiliki repository permissions: **Contents: Read and write** dan **Actions: Read and write**.
7. (Disarankan) Tambahkan empat release-signing secrets yang dijelaskan di bagian signing.
8. Buat APK Builder melalui Actions → Build Build.APP → Run workflow, atau build modul `app` melalui Android Studio/AndroidForge.

Jangan masukkan token atau API key ke source code maupun prompt. Simpan repository privat karena HTML yang dikirim untuk build disimpan pada `build-inputs/<project-slug>/project.json`.

## Alur penggunaan

1. Isi nama aplikasi dan package ID valid, misalnya `com.tein.generated.aplikasiku`.
2. Pilih logo (opsional).
3. Pilih permission tambahan yang memang dibutuhkan aplikasi. INTERNET selalu disertakan.
4. Pilih profil generasi LITE / STUDIO / GAME 2D dan kategori prompt APP / GAME 2D / UTILITY.
5. Isi prompt, tekan **GENERATE PROJECT**, lalu cek hasil di Preview.
6. Atur version name dan version code sebelum build. Naikkan version code saat membuat update untuk package ID yang sama.
7. Tekan **BUILD APK**. Build.APP mengunggah input ke repository privat, menjalankan workflow, menunggu hasil, lalu mengunduh artifact APK.
8. Gunakan Instal APK, Simpan, atau Bagikan. Pada pemasangan pertama, Android dapat meminta izin instal aplikasi dari Build.APP.

## Tentang signing

Build workflow mendukung dua jalur:

- **Debug fallback:** digunakan jika release keystore belum dikonfigurasi. Cocok untuk pengujian awal, tetapi key debug runner dapat berubah antar-run sehingga APK berikutnya mungkin tidak dapat memperbarui instalasi sebelumnya.
- **Release signing:** gunakan keystore milik developer yang sama pada setiap rilis. Simpan nilainya sebagai GitHub Actions repository secrets dengan nama `BUILDAPP_RELEASE_KEYSTORE_BASE64`, `BUILDAPP_RELEASE_KEYSTORE_PASSWORD`, `BUILDAPP_RELEASE_KEY_ALIAS`, dan `BUILDAPP_RELEASE_KEY_PASSWORD`. Jangan commit file keystore atau memasukkan private key ke dalam APK.

Untuk update aplikasi dengan package ID yang sama, gunakan signing key yang sama dan naikkan `versionCode` setiap rilis. Kehilangan signing key dapat menghalangi update langsung ke aplikasi yang sudah terpasang.

## Pemeriksaan

Pemeriksaan sumber mencakup parser XML/YAML dan validasi struktur project. Build APK end-to-end harus dibuktikan oleh GitHub Actions setelah source diunggah; jangan menganggap build berhasil sebelum workflow berstatus `success` dan artifact APK tersedia.
