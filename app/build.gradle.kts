import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
android {
    namespace = "ru.spgsroot.peachy"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.spgsroot.peachy"
        manifestPlaceholders["peachyAppLabel"] = "@string/app_name"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "3.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (providers.gradleProperty("peachy.browserQa").orNull == "true") {
                applicationIdSuffix = ".browserqa"
                manifestPlaceholders["peachyAppLabel"] = "Peachy Browser QA"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material:material-icons-extended")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Bundled on-device OCR (no runtime model download)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.korean)

    // Minimal arm64 on-device text detector runtime
    implementation(files("libs/onnxruntime-1.32.0-minimal-arm64-v8a.aar"))

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Network
    implementation(libs.okhttp)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security Crypto
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Documents: PDF engine (AGPL-3.0, same licence as this app) and PKCS#7 signing
    implementation(libs.mupdf.fitz)
    implementation(libs.bouncycastle.bcpkix)

    // Buttplug
    implementation(project(":buttplug-core"))
    implementation(project(":buttplug-compose"))

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// BouncyCastle 1.78 ships the same multi-release OSGI metadata from four artifacts; only one copy
// can win, and none of it is needed at runtime on Android.
androidComponents {
    onVariants { variant ->
        variant.packaging.resources.excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        val nativeBuild = tasks.register<AdblockBuildTask>("build${variant.name.replaceFirstChar { it.uppercase() }}Adblock") {
            source.set(rootProject.layout.projectDirectory.dir("native/adblock"))
            profile.set(requireNotNull(variant.buildType))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(nativeBuild, AdblockBuildTask::outputDirectory)
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val verifyApk = tasks.register<VerifyShieldApk>("verify${variantName}ShieldApk") {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            verifier.set(rootProject.layout.projectDirectory.file("tools/adblock/verify-apk.py"))
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(verifyApk) }
    }
}

abstract class AdblockBuildTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val source: DirectoryProperty
    @get:Input abstract val profile: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun build() {
        processes.exec {
            commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
                source.file("build.py").get().asFile, profile.get(), outputDirectory.get().asFile)
        }
    }
}

abstract class VerifyShieldApk @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val verifier: RegularFileProperty

    @TaskAction fun verify() {
        val apks = apkDirectory.asFileTree.matching { include("*.apk") }.files
        check(apks.isNotEmpty()) { "No APK available for ABI verification" }
        processes.exec {
            commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3", verifier.get().asFile)
            args(apks.map { it.absolutePath })
        }
    }
}

// ---------------------------------------------------------------------------
// Optional packaging of the staged LibreOffice engine, debug variant only.
//
// Opt-in; both properties are required together:
//   -Ppeachy.officeEngine.stage=<per-ABI stage directory, read-only>
//   -Ppeachy.officeEngine.javaGlue=<android/Bootstrap/src/org/libreoffice/kit at the pin>
// Optional: -Ppeachy.officeEngine.python=<interpreter>
// (default: "python" on Windows, "python3" elsewhere)
//
// With neither property nothing is registered, nothing Office-related is read,
// copied or downloaded, and the release variant is never wired. The normal build
// and release artifacts stay unchanged.
//
// tools/office-packaging/office_engine_package.py validates the pin, applicationId,
// ABI, ELF machine/alignment, manifest flags, every staged hash, the asset set,
// licences and the four pinned MPL-2.0 Java sources, then atomically publishes the
// payload into its own build/office-engine/debug/payload root. Three copy tasks sync
// the asset, jniLibs and java categories into the directories AGP assigns to those
// generated-source properties. verifyOfficeEngineDebugNativeLibs checks AGP's merged
// libraries for the chosen ABI against the jniLibs copy and refuses cross-ABI leaks. No pickFirst
// rule is added anywhere.
// ---------------------------------------------------------------------------
val officeEngineStage = providers.gradleProperty("peachy.officeEngine.stage")
val officeEngineJavaGlue = providers.gradleProperty("peachy.officeEngine.javaGlue")
val officeEnginePython = providers.gradleProperty("peachy.officeEngine.python")
    .orElse(if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3")

if (officeEngineStage.isPresent != officeEngineJavaGlue.isPresent) {
    throw GradleException(
        "Office engine packaging needs both -Ppeachy.officeEngine.stage and " +
            "-Ppeachy.officeEngine.javaGlue, or neither of them."
    )
}

if (officeEngineStage.isPresent) {
    val officeEngineScript = rootProject.file("tools/office-packaging/office_engine_package.py")
    // Owned only by prepareOfficeEngineDebug and outside every AGP source category, so atomic
    // publication cannot replace or race AGP's generated directories.
    val officeEnginePayload = layout.buildDirectory.dir("office-engine/debug/payload")
    val officeEngineApplicationId = requireNotNull(android.defaultConfig.applicationId) {
        "Office engine packaging requires a concrete applicationId."
    }
    val officeEngineMinSdk = requireNotNull(android.defaultConfig.minSdk) {
        "Office engine packaging requires minSdk."
    }

    val prepareOfficeEngine = tasks.register<OfficeEnginePrepareTask>("prepareOfficeEngineDebug") {
        group = "office"
        description = "Validates one staged LibreOffice ABI and publishes the debug payload."
        stage.fileValue(file(officeEngineStage.get()))
        javaGlue.fileValue(file(officeEngineJavaGlue.get()))
        pythonExecutable.set(officeEnginePython)
        scriptFile.fileValue(officeEngineScript)
        applicationId.set(officeEngineApplicationId)
        minSdkVersion.set(officeEngineMinSdk)
        preparationRoot.set(officeEnginePayload)
    }

    // Each category is copied into the directory AGP assigns to that generated-source property.
    // outputDirectory is never preset here: AGP owns its location and overwrites any convention.
    val generatedAssets = tasks.register<OfficeEngineGeneratedSourceTask>("officeEngineDebugAssets") {
        description = "Copies validated LibreOffice assets into the debug asset source directory."
        sourceDirectory.set(prepareOfficeEngine.flatMap { it.preparationRoot.dir("assets") })
    }
    val generatedJniLibs = tasks.register<OfficeEngineGeneratedSourceTask>("officeEngineDebugJniLibs") {
        description = "Copies the validated engine libraries into the debug jniLibs directory."
        sourceDirectory.set(prepareOfficeEngine.flatMap { it.preparationRoot.dir("jniLibs") })
    }
    val generatedJava = tasks.register<OfficeEngineGeneratedSourceTask>("officeEngineDebugJava") {
        description = "Copies the pinned LibreOffice Java glue into the debug Java source directory."
        sourceDirectory.set(prepareOfficeEngine.flatMap { it.preparationRoot.dir("java") })
    }

    androidComponents {
        onVariants(selector().withBuildType("debug")) { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generatedAssets,
                OfficeEngineGeneratedSourceTask::outputDirectory
            )
            variant.sources.jniLibs?.addGeneratedSourceDirectory(
                generatedJniLibs,
                OfficeEngineGeneratedSourceTask::outputDirectory
            )
            variant.sources.java?.addGeneratedSourceDirectory(
                generatedJava,
                OfficeEngineGeneratedSourceTask::outputDirectory
            )

            val verifyMerged = tasks.register<OfficeEngineVerifyMergedTask>("verifyOfficeEngineDebugNativeLibs") {
                description = "Checks merged engine libraries and cross-ABI isolation."
                preparedJniLibs.set(generatedJniLibs.flatMap { it.outputDirectory })
                mergedNativeLibs.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
                pythonExecutable.set(officeEnginePython)
                scriptFile.fileValue(officeEngineScript)
            }
            tasks.matching { it.name == "packageDebug" || it.name == "bundleDebug" }
                .configureEach { dependsOn(verifyMerged) }
        }
    }
}

abstract class OfficeEnginePrepareTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stage: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaGlue: DirectoryProperty

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val minSdkVersion: Property<Int>

    @get:OutputDirectory
    abstract val preparationRoot: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        execOperations.exec {
            commandLine(
                pythonExecutable.get(),
                scriptFile.get().asFile.absolutePath,
                "prepare",
                "--stage", stage.get().asFile.absolutePath,
                "--java-glue", javaGlue.get().asFile.absolutePath,
                "--out", preparationRoot.get().asFile.absolutePath,
                "--app-id", applicationId.get(),
                "--min-sdk", minSdkVersion.get().toString()
            )
        }
    }
}

/**
 * Copies one validated category from the preparation root into the generated-source directory that
 * AGP supplies for [outputDirectory]. AGP owns that directory; this class never assigns it.
 */
abstract class OfficeEngineGeneratedSourceTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun copy() {
        val source = sourceDirectory.get().asFile
        fileSystemOperations.sync {
            from(source)
            into(outputDirectory.get().asFile)
        }
    }
}

abstract class OfficeEngineVerifyMergedTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparedJniLibs: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedNativeLibs: DirectoryProperty

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        execOperations.exec {
            commandLine(
                pythonExecutable.get(),
                scriptFile.get().asFile.absolutePath,
                "verify-merged",
                "--prepared-jni", preparedJniLibs.get().asFile.absolutePath,
                "--merged-native-libs", mergedNativeLibs.get().asFile.absolutePath
            )
        }
    }
}
