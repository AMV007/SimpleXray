import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    // AGP 9 compiles Kotlin itself; org.jetbrains.kotlin.android must not be applied.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.65.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
            }
            task.plugins {
                create("grpc")
            }
        }
    }
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val xrayVersion = versionProps.getProperty("XRAY_CORE_VERSION")
val geoipVersion = versionProps.getProperty("GEOIP_VERSION")
val geositeVersion = versionProps.getProperty("GEOSITE_VERSION")
val goVersion = versionProps.getProperty("GO_VERSION")
val ndkVersionProp = versionProps.getProperty("NDK_VERSION")

/**
 * geoip.dat / geosite.dat are not committed (see .gitignore) and are required at
 * runtime: FileManager.extractAssetsIfNeeded() copies them out of assets and
 * raises an error when they are missing. Download them before the assets are
 * packaged, using the versions pinned in version.properties.
 * The release tag is per-file, so each entry carries its own version.
 */
val rulesDatAssetDir = layout.projectDirectory.dir("src/main/assets").asFile
val rulesDatFiles = mapOf(
    "geoip.dat" to geoipVersion,
    "geosite.dat" to geositeVersion,
)
val rulesDatWantedStamp = "geoip=$geoipVersion\ngeosite=$geositeVersion\n"

val downloadRulesDat = tasks.register("downloadRulesDat") {
    group = "build setup"
    description = "Downloads geoip.dat and geosite.dat into src/main/assets."

    inputs.property("rulesDatVersions", rulesDatWantedStamp)

    doLast {
        rulesDatAssetDir.mkdirs()
        val versionStamp = File(rulesDatAssetDir, ".rules-dat-version")
        val upToDate = versionStamp.exists() && versionStamp.readText() == rulesDatWantedStamp
        rulesDatFiles.forEach { (fileName, version) ->
            val target = File(rulesDatAssetDir, fileName)
            if (upToDate && target.exists() && target.length() > 0) {
                logger.lifecycle("$fileName is up to date, skipping download")
                return@forEach
            }
            val url = "https://github.com/lhear/v2ray-rules-dat/releases/download/$version/$fileName"
            logger.lifecycle("Downloading $url")
            val tmp = File(rulesDatAssetDir, "$fileName.tmp")
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                throw GradleException("Downloaded $fileName is empty: $url")
            }
            if (target.exists() && !target.delete()) {
                tmp.delete()
                throw GradleException("Cannot replace ${target.absolutePath}")
            }
            tmp.renameTo(target)
            logger.lifecycle("Saved $fileName (${target.length()} bytes)")
        }
        versionStamp.writeText(rulesDatWantedStamp)
    }
}

/**
 * libxray.so is the Xray-core binary executed as a child process at runtime. It is
 * gitignored, so a fresh clone builds an APK without it and the core silently
 * fails to start. Provide it automatically, two ways:
 *
 *   -PxrayFromSource   compile Xray-core from source (needs Go + NDK; slow)
 *   default            unpack the official prebuilt android binary from the
 *                      XTLS/Xray-core release (fast, no extra toolchain)
 *
 * Both are pinned to XRAY_CORE_VERSION in version.properties, so switching modes
 * rebuilds the binary when the pinned version changes.
 */
val xrayJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
// abi directory -> release asset suffix / GOARCH used when building from source.
val xrayReleaseAssets = mapOf("arm64-v8a" to "arm64-v8a", "x86_64" to "amd64")
val xrayGoArches = mapOf("arm64-v8a" to "arm64", "x86_64" to "amd64")
val xrayFromSource = project.hasProperty("xrayFromSource")
val xrayWantedStamp = "$xrayVersion ${if (xrayFromSource) "source" else "prebuilt"}\n"

fun xrayBinaryExists(arch: String): Boolean {
    val binary = File(xrayJniLibsDir, "$arch/libxray.so")
    val stamp = File(xrayJniLibsDir, ".xray-core-version")
    return binary.exists() && binary.length() > 0 &&
        stamp.exists() && stamp.readText() == xrayWantedStamp
}

val downloadXrayCore = tasks.register("downloadXrayCore") {
    group = "build setup"
    description = "Unpacks the official Xray-core android binary into src/main/jniLibs."

    inputs.property("xrayVersion", xrayVersion)
    inputs.property("mode", "prebuilt")

    doLast {
        if (!xrayFromSource && xrayReleaseAssets.keys.all { xrayBinaryExists(it) }) {
            logger.lifecycle("libxray.so $xrayVersion already present, skipping")
            return@doLast
        }
        xrayReleaseAssets.forEach { (arch, releaseArch) ->
            if (!xrayFromSource && xrayBinaryExists(arch)) {
                logger.lifecycle("libxray.so ($arch) is up to date, skipping download")
                return@forEach
            }
            val url = "https://github.com/XTLS/Xray-core/releases/download/" +
                "$xrayVersion/Xray-android-$releaseArch.zip"
            logger.lifecycle("Downloading $url")
            val workDir = File(temporaryDir, arch).apply { mkdirs() }
            val zip = File(workDir, "xray.zip")
            URI(url).toURL().openStream().use { input ->
                zip.outputStream().use { output -> input.copyTo(output) }
            }
            // Xray ships an "xray" executable inside the zip; extract just that entry.
            val unzipDir = File(workDir, "unzipped").apply { mkdirs() }
            ZipFile(zip).use { zf ->
                val entry = zf.entries().asSequence().firstOrNull { it.name == "xray" }
                    ?: throw GradleException("No 'xray' entry in $url")
                zf.getInputStream(entry).use { input ->
                    File(unzipDir, "libxray.so").outputStream().use { output -> input.copyTo(output) }
                }
            }
            replaceFile(File(unzipDir, "libxray.so"), File(xrayJniLibsDir, "$arch/libxray.so"))
            logger.lifecycle("Saved libxray.so ($arch)")
        }
        File(xrayJniLibsDir, ".xray-core-version").writeText(xrayWantedStamp)
    }
}

val buildXrayFromSource = tasks.register("buildXrayFromSource") {
    group = "build setup"
    description = "Compiles Xray-core from source into src/main/jniLibs (needs Go + NDK)."

    inputs.property("xrayVersion", xrayVersion)
    inputs.property("goVersion", goVersion)
    inputs.property("ndkVersion", ndkVersionProp)
    inputs.property("mode", "source")

    doLast {
        if (xrayGoArches.keys.all { xrayBinaryExists(it) }) {
            logger.lifecycle("libxray.so $xrayVersion (source) already present, skipping")
            return@doLast
        }
        val goExecutable = System.getenv("GO") ?: "go"
        val goVersionOutput = runCatching {
            providers.exec {
                commandLine(goExecutable, "version")
                isIgnoreExitValue = true
            }.standardOutput.asText.get()
        }.getOrDefault("")
        if (goVersionOutput.isBlank() || !goVersionOutput.contains("go version")) {
            throw GradleException(
                "Building Xray-core from source requires Go on PATH. Install Go $goVersion " +
                    "or drop -PxrayFromSource to use the official prebuilt binary.",
            )
        }

        val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val ndkDir = File(sdkDir, "ndk/$ndkVersionProp")
        if (!ndkDir.isDirectory) {
            throw GradleException(
                "NDK $ndkVersionProp not found at $ndkDir. Install it or drop " +
                    "-PxrayFromSource to use the official prebuilt binary.",
            )
        }
        val toolchain = File(ndkDir, "toolchains/llvm/prebuilt")
        val hostDir = toolchain.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw GradleException("No LLVM toolchain under $toolchain")

        val srcDir = File(temporaryDir, "Xray-core")
        if (!File(srcDir, ".git").isDirectory) {
            logger.lifecycle("Cloning Xray-core $xrayVersion")
            providers.exec {
                commandLine(
                    "git", "clone", "--depth", "1", "--branch", xrayVersion,
                    "https://github.com/XTLS/Xray-core.git", srcDir.absolutePath,
                )
            }.result.get()
        }
        val commitId = providers.exec {
            commandLine("git", "rev-parse", "HEAD")
            workingDir(srcDir)
        }.standardOutput.asText.get().trim().take(7)

        xrayGoArches.forEach { (arch, goArch) ->
            if (xrayBinaryExists(arch)) {
                logger.lifecycle("libxray.so ($arch) is up to date, skipping build")
                return@forEach
            }
            val ccName = if (arch == "arm64-v8a") {
                "aarch64-linux-android24-clang"
            } else {
                "x86_64-linux-android24-clang"
            }
            val cc = File(hostDir, "bin/$ccName")
            if (!cc.isFile) {
                throw GradleException("Android clang not found: $cc")
            }
            logger.lifecycle("Building libxray.so for $arch ($commitId)")
            val out = File(temporaryDir, "xray-$arch")
            providers.exec {
                commandLine(
                    goExecutable, "build", "-o", out.absolutePath, "-trimpath", "-buildvcs=false",
                    "-ldflags=-X github.com/xtls/xray-core/core.build=$commitId " +
                        "-s -w -buildid= -checklinkname=0",
                    "./main",
                )
                workingDir(srcDir)
                environment("GOOS", "android")
                environment("GOARCH", goArch)
                environment("CGO_ENABLED", "1")
                environment("CC", cc.absolutePath)
            }.result.get()
            replaceFile(out, File(xrayJniLibsDir, "$arch/libxray.so"))
            logger.lifecycle("Saved libxray.so ($arch)")
        }
        File(xrayJniLibsDir, ".xray-core-version").writeText(xrayWantedStamp)
    }
}

// Only the selected mode runs; the other task stays available for manual use.
val provideXrayCore = if (xrayFromSource) buildXrayFromSource else downloadXrayCore

android {
    namespace = "com.simplexray.an"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.simplexray.an"
        versionCode = versionProps.getProperty("APP_VERSION_CODE").toInt()
        versionName = versionProps.getProperty("APP_VERSION_NAME")
        minSdk {
            version = release(29)
        }
        targetSdk {
            version = release(36)
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                arguments += listOf(
                    "APP_CFLAGS+=-DPKGNAME=com/simplexray/an/service " +
                        "-ffile-prefix-map=${rootDir}=.",
                    "APP_LDFLAGS+=-Wl,--build-id=none",
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
        }
    }

    val propsFile = rootProject.file("store.properties")
    val isSigningEnabled = propsFile.exists() &&
        Properties().run {
            propsFile.inputStream().use { load(it) }
            containsKey("storeFile")
        }

    if (isSigningEnabled) {
        val storeProps = Properties().apply { propsFile.inputStream().use { load(it) } }
        signingConfigs.getByName("release") {
            storeFile = rootProject.file(storeProps.getProperty("storeFile"))
            storePassword = storeProps.getProperty("storePassword")
            keyAlias = storeProps.getProperty("keyAlias")
            keyPassword = storeProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (isSigningEnabled) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = if (isSigningEnabled) signingConfigs.getByName("release") else null
            isDebuggable = true
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    ndkVersion = ndkVersionProp

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.directories.addAll(
                listOf("src/main/java", "build/generated/source/proto/main/java"),
            )
            kotlin.directories.addAll(
                listOf("src/main/kotlin", "build/generated/source/proto/main/grpckt"),
            )
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

/**
 * Renames the produced APKs from the default app-<variant>[-<abi>].apk to the
 * simplexray-<abi>.apk scheme the release process expects.
 *
 * AGP 9 removed `applicationVariants`/`outputFileName`, so this listens to the
 * APK artifact and copies it under the new name (the gradle-recipes
 * `listenToArtifacts` pattern).
 */
androidComponents {
    onVariants(selector().all()) { variant ->
        val copyTask = tasks.register<CopyRenamedApks>("renameApksFor${variant.name.replaceFirstChar { it.uppercase() }}") {
            output.set(layout.buildDirectory.dir("outputs/apk/renamed/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        }
        variant.artifacts.use(copyTask).wiredWith { it.input }.toListenTo(SingleArtifact.APK)
    }
}

abstract class CopyRenamedApks : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @TaskAction
    fun taskAction() {
        val outputDir = output.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val builtArtifacts = builtArtifactsLoader.get().load(input.get())
            ?: throw GradleException("Cannot load APKs from ${input.get().asFile}")
        builtArtifacts.elements.forEach { artifact ->
            // The ABI filter is only present on split outputs; the universal one has none.
            val abi = artifact.filters.find { it.filterType.name == "ABI" }?.identifier
            val name = if (abi != null) "simplexray-$abi.apk" else "simplexray-universal.apk"
            File(artifact.outputFile).inputStream().use { input ->
                File(outputDir, name).outputStream().use { out -> input.copyTo(out) }
            }
        }
        // Keep the metadata alongside the renamed APKs, as AGP expects it there.
        builtArtifacts.save(output.get())
    }
}

// Fetch the runtime payload (rule files, Xray-core binary) before it is merged into
// the APK, so a fresh clone builds an APK that does not crash on startup.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(downloadRulesDat)
    }
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) {
        dependsOn(provideXrayCore)
    }
}

/** Moves [source] onto [target], replacing any existing file. */
fun replaceFile(source: File, target: File) {
    target.parentFile?.mkdirs()
    if (target.exists() && !target.delete()) {
        throw GradleException("Cannot replace ${target.absolutePath}")
    }
    if (!source.renameTo(target)) {
        source.copyTo(target, overwrite = true)
        source.delete()
    }
    if (target.length() == 0L) {
        target.delete()
        throw GradleException("Extracted file is empty: ${target.absolutePath}")
    }
}

dependencies {
    implementation("io.grpc:grpc-okhttp:1.74.0")
    implementation("io.grpc:grpc-protobuf:1.74.0")
    implementation("io.grpc:grpc-stub:1.74.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.3")

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.16.0")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview-android")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.navigation:navigation-compose-android:2.9.3")

    implementation("com.github.nanihadesuka:LazyColumnScrollbar:2.2.0")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
