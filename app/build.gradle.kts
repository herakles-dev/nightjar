plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    jacoco
}

android {
    namespace = "dev.herakles.nightjar"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.herakles.nightjar"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Standard build/ops convention — install scripts expect app-debug.apk
    setProperty("archivesBaseName", "app")

    // Uses AGP's default auto-generated debug signing config (~/.android/debug.keystore) —
    // no committed keystore needed for a debug-only build.

    // Release signing: read entirely from environment variables, never from a committed file
    // or a hardcoded value here. A maintainer building a public release sources the keystore
    // path/passwords from a local secrets file (outside this repo) before running
    // `./gradlew assembleRelease`; anyone else building this project gets an unsigned release
    // variant, which is fine for local inspection/testing but not for distribution — only a
    // build with these four variables set produces an installable, updatable release APK.
    val releaseKeystorePath = System.getenv("NIGHTJAR_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("NIGHTJAR_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("NIGHTJAR_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("NIGHTJAR_KEY_PASSWORD")
    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Lint runs on every CI build. One androidx.lifecycle detector (NullSafeMutableLiveData)
    // crashes on this AGP/Kotlin pairing's UAST analysis -- a tooling version mismatch, not a
    // finding -- and this app uses no LiveData, so that single check is disabled. Everything
    // else runs and a lint error fails the build.
    lint {
        disable += "NullSafeMutableLiveData"
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    buildFeatures {
        compose = true
        // Generates dev.herakles.nightjar.BuildConfig.DEBUG — the release/debug switch
        // DebugProbe.kt gates every COVERT_DEBUG log line behind.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.extensions.configure<JacocoTaskExtension> {
                    // Robolectric loads classes through its own loader; without these the
                    // report is empty.
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Room schema export — must be turned on while FireflyDatabase is still version 1.
// The Room Gradle plugin is not applied in this project, so this is the ksp-arg form rather
// than a `room { schemaDirectory(...) }` block.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    // v6 send plumbing: androidx.core.content.FileProvider for FireflyShare.kt's
    // private-cache share Uris. Was only a transitive dependency (pinned to 1.13.1 by other
    // androidx libs above) before this task made it a direct one -- this project has no
    // gradle/libs.versions.toml, so it's declared the same direct-string way every other
    // dependency here is.
    implementation("androidx.core:core-ktx:1.13.1")

    // Firefly Jar persistence layer — FireflyLog.kt
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Robolectric + androidx.test give ImageStegoCarrierTest (#11) a real, pixel-accurate
    // Bitmap (getPixel/setPixel/BitmapFactory.decodeResource) in a plain JVM unit test —
    // android.jar's own Bitmap methods are stubs ("not mocked") without this.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// Coverage: ./gradlew jacocoTestReport -> app/build/reports/jacoco/jacocoTestReport/
// Compose/generated classes are excluded so the number reflects hand-written logic.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val excluded = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*ComposableSingletons*.*", "**/*_Impl*.*",
    )
    classDirectories.setFrom(
        fileTree("$buildDir/tmp/kotlin-classes/debug") { exclude(excluded) }
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec") }
    )
}

// Mutation testing on the pure-JVM signal-processing core (modem, Reed-Solomon, FFT,
// spectrogram, acoustic detector): ./gradlew pitest -> app/build/reports/pitest/index.html
// A mutant that survives is a behavior change no test notices, so this measures how well
// the suite catches bugs rather than which lines it merely executes.
val pitestRuntime: Configuration by configurations.creating
dependencies {
    pitestRuntime("org.pitest:pitest-command-line:1.17.4")
}
tasks.register<JavaExec>("pitest") {
    group = "verification"
    description = "Mutation tests the JVM-only signal-processing core."
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin")
    classpath = pitestRuntime
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    val pkg = "dev.herakles.nightjar"
    val testClasses = listOf(
        "AcousticCarrierTest", "ReedSolomonTest", "AcousticDetectorTest", "SpectrogramTest",
        "ReedSolomonExactTest", "FftSpectrogramExactTest", "AcousticDetectorExactTest",
        "AcousticWireFormatTest",
    ).joinToString(",") { "$pkg.$it" }
    doFirst {
        // Production classes must come from the classes directory: the unit-test classpath carries
        // them only inside a jar, and pitest never mutates jar contents (it would mutate the tests
        // themselves instead, which is what the first baseline did).
        val mainClasses = file("$buildDir/tmp/kotlin-classes/debug")
        val cp = listOf(mainClasses) + unitTest.get().classpath.files.filter {
            it.exists() && it.name != "classes.jar"
        }
        args(
            "--reportDir", "$buildDir/reports/pitest",
            "--sourceDirs", "$projectDir/src/main/java",
            "--targetClasses", "$pkg.AcousticCarrier*,$pkg.ReedSolomon*,$pkg.Fft*,$pkg.Spectrogram*,$pkg.AcousticDetector*",
            "--targetTests", testClasses,
            "--excludedClasses", "$pkg.*Test,$pkg.*Test$*",
            "--classPath", cp.joinToString(","),
            "--outputFormats", "HTML,XML",
            "--timestampedReports=false",
            "--threads", "10",
            "--timeoutConst", "8000",
            "--failWhenNoMutations=false",
        )
    }
}
