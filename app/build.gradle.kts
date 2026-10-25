import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val crateDirectory: DirectoryProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Internal abstract val cargoTargetDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val api: Property<Int>
    @get:Input abstract val release: Property<Boolean>
    @get:Input abstract val cargoNdkVersion: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction fun buildNative() {
        val crate = crateDirectory.get().asFile
        if (!crate.resolve("Cargo.lock").isFile) {
            throw GradleException("Missing anydoc-jni/Cargo.lock. See dependency setup in ANYDOC_INTEGRATION.md.")
        }
        val version = ByteArrayOutputStream()
        exec.exec {
            workingDir(crate)
            commandLine("cargo", "ndk", "--version")
            standardOutput = version
        }
        if (version.toString(Charsets.UTF_8.name()).trim() != "cargo-ndk ${cargoNdkVersion.get()}") {
            throw GradleException("Install cargo-ndk ${cargoNdkVersion.get()} with --locked before building.")
        }
        val output = outputDirectory.get().asFile
        if (output.exists() && !output.deleteRecursively()) throw GradleException("Cannot clear Cargo JNI staging output")
        val arguments = mutableListOf("cargo", "ndk")
        abis.get().forEach { arguments += listOf("-t", it) }
        arguments += listOf("--platform", api.get().toString(), "-o", output.absolutePath, "build", "--locked", "--lib")
        if (release.get()) arguments += "--release"
        exec.exec {
            workingDir(crate)
            commandLine(arguments)
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
            environment("CARGO_TARGET_DIR", cargoTargetDirectory.get().asFile.absolutePath)
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        }
        abis.get().forEach { abi ->
            val directory = output.resolve(abi)
            if (!directory.resolve("libtp1_anydoc.so").isFile) throw GradleException("Missing AnyDoc library for $abi")
            // Dependency cdylibs are side outputs; this wrapper only links Android system libraries.
            directory.listFiles().orEmpty().filter { it.name != "libtp1_anydoc.so" }.forEach {
                if (!it.delete()) throw GradleException("Cannot remove unused native staging output: ${it.name}")
            }
        }
    }
}

val nativeAbis = listOf("arm64-v8a")
val releaseInstrumentation = providers.gradleProperty("tp1.releaseInstrumentation").map(String::toBoolean).getOrElse(false)

android {
    namespace = "com.example.tp1"
    ndkVersion = "30.0.16248370"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.tp1"
        minSdk = 33
        ndk { abiFilters += nativeAbis }
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            if (releaseInstrumentation) {
                signingConfig = signingConfigs.getByName("debug")
                proguardFile("release-instrumentation-rules.pro")
                testProguardFile("release-instrumentation-rules.pro")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            optimization {
                enable = true
                // The separate test APK calls shared runtime APIs that app-only shrinking can remove.
                packageScope = if (releaseInstrumentation) setOf("com.example.tp1.extraction.**")
                    else setOf("androidx.**", "kotlin.**", "kotlinx.**", "com.example.tp1.extraction.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        animationsDisabled = true
    }
    testBuildType = if (releaseInstrumentation) "release" else "debug"
}

val nativeApi = requireNotNull(android.defaultConfig.minSdk)
val selectedNdk = androidComponents.sdkComponents.ndkDirectory
androidComponents.onVariants { variant ->
    val producer = tasks.register<CargoNdkTask>("buildAnyDoc${variant.name.replaceFirstChar { it.uppercaseChar() }}") {
        crateDirectory.set(layout.projectDirectory.dir("src/main/rust/anydoc-jni"))
        ndkDirectory.set(selectedNdk)
        cargoTargetDirectory.set(layout.buildDirectory.dir("intermediates/anydoc/${variant.name}/cargo-target"))
        outputDirectory.set(layout.buildDirectory.dir("generated/anydoc/${variant.name}/jniLibs"))
        abis.set(nativeAbis)
        api.set(nativeApi)
        release.set(variant.buildType == "release")
        cargoNdkVersion.set("4.1.2")
    }
    requireNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(producer, CargoNdkTask::outputDirectory)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
