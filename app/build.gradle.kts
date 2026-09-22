plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Robolectric + androidx.test give ImageStegoCarrierTest (#11) a real, pixel-accurate
    // Bitmap (getPixel/setPixel/BitmapFactory.decodeResource) in a plain JVM unit test —
    // android.jar's own Bitmap methods are stubs ("not mocked") without this.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
