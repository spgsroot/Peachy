plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Compile the actual production and regression sources together; no telemetry in :app.
val qaSources = tasks.register<Sync>("prepareQaSources") {
    from(rootProject.file("app/src/main/java/ru/spgsroot/peachy")) {
        include("browser/adblock/NativeFilter.kt", "translate/DetectorPreprocess.kt", "translate/DetectorDecode.kt",
            "translate/BlockMerge.kt", "translate/OcrGrouping.kt", "translate/PageOcr.kt",
            "documents/image/ImageRaster.kt", "documents/image/ImageOps.kt")
    }
    from(rootProject.file("app/src/androidTest/java/ru/spgsroot/peachy/documents/image")) {
        include("ImageRasterExportTest.kt", "ImageRasterJpegMemoryTest.kt", "ImageRasterFreeRotationTest.kt")
    }
    into(layout.buildDirectory.dir("qaSources"))
}
val qaNative = tasks.register<Exec>("buildQaAdblock") {
    inputs.dir(rootProject.file("native/adblock"))
    val output = layout.buildDirectory.dir("qaNative").get().asFile
    outputs.dir(output)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        rootProject.file("native/adblock/build.py"), "release", output, "--perf-qa")
}
android {
    namespace = "ru.spgsroot.peachy.perfqa"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.spgsroot.peachy.perfqa"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName("debug")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "qa-rules.pro")
    }
    sourceSets.getByName("main") {
        java.srcDir(layout.buildDirectory.dir("qaSources"))
        jniLibs.srcDir(layout.buildDirectory.dir("qaNative"))
        assets.srcDir(rootProject.file("app/src/main/assets"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions.jvmTarget = "11"
}
tasks.named("preBuild") { dependsOn(qaSources, qaNative) }
dependencies {
    implementation(files(rootProject.file("app/libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar")))
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.androidx.junit)
}
