import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.merged.voice"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Offline speech recognition (Vosk, Apache-2.0). No Google speech service, no internet while using it.
    api("com.alphacephei:vosk-android:0.3.47")
    api("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.core:core-ktx:1.13.1")
}

// ---------------------------------------------------------------------------------------------
// The small English voice model (~40 MB) is downloaded ONCE at build time and packed into the app
// as assets, so the phone never needs internet to recognise your voice.
// If you build offline, put the unzipped model folder at src/main/assets/model-en-us (it must
// contain a file called "uuid") and this step is skipped.
// ---------------------------------------------------------------------------------------------
val modelDir = layout.projectDirectory.dir("src/main/assets/model-en-us").asFile
val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

val downloadVoskModel by tasks.registering {
    description = "Downloads the small offline English voice model (only if it is not already there)"
    onlyIf { !File(modelDir, "uuid").exists() }
    doLast {
        try {
            val work = layout.buildDirectory.get().asFile
            val tmpZip = File(work, "vosk-model.zip")
            val tmpDir = File(work, "vosk-model-tmp")
            tmpZip.parentFile.mkdirs()
            URL(modelUrl).openStream().use { input -> tmpZip.outputStream().use { out -> input.copyTo(out) } }
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            ZipInputStream(tmpZip.inputStream().buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val out = File(tmpDir, e.name)
                    if (!out.canonicalPath.startsWith(tmpDir.canonicalPath)) throw GradleException("Unsafe entry in model zip")
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { o -> z.copyTo(o) }
                    }
                    e = z.nextEntry
                }
            }
            val inner = tmpDir.listFiles()!!.first { it.isDirectory }
            modelDir.deleteRecursively()
            inner.copyRecursively(modelDir, true)
            File(modelDir, "uuid").writeText(UUID.randomUUID().toString())
            tmpZip.delete()
            tmpDir.deleteRecursively()
        } catch (e: Exception) {
            logger.warn("Voice model download failed: ${e.message}. The app will build, but voice commands will report that the model is missing.")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadVoskModel) }
