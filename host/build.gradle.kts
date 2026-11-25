plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.mergedhub"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.merged.fileutilities"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += setOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { pickFirsts += setOf("META-INF/**") }
        jniLibs { useLegacyPackaging = true }
    }

}
kotlin {
    jvmToolchain(17)
    // Flutter plugins can bring a newer kotlin-stdlib than the compiler; newer stdlib is fine at runtime.
    compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") }
}
dependencies {
    implementation(project(":All_Media_Studio_GitHub_Project_app"))
    implementation(project(":FileExplorer_app"))
    implementation(project(":FileManagerPro_app"))
    implementation(project(":ImageStudioAndroid_app"))
    implementation(project(":YTSaver_app"))
    implementation(project(":WebGrab_app"))
}
