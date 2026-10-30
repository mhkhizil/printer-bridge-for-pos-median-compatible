import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// URL the WebView loads. Resolution order: -PposUrl, the POS_URL environment
// variable, posUrl in local.properties (machine-local, git-ignored), then the
// placeholder below. Blank values are skipped, so an unset POS_URL in CI (for
// example an empty repository variable) falls through instead of producing an
// empty BuildConfig.POS_URL:
//   ./gradlew :app:assembleDebug -PposUrl=https://pos.example.com
//   POS_URL=https://pos.example.com ./gradlew :app:assembleDebug
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use { load(it) }
}
val posUrl: String = listOfNotNull(
    (findProperty("posUrl") as String?)?.takeIf { it.isNotBlank() },
    System.getenv("POS_URL")?.takeIf { it.isNotBlank() },
    localProperties.getProperty("posUrl")?.takeIf { it.isNotBlank() },
).firstOrNull() ?: "https://pos.example.com"

// Release signing. Create `keystore.properties` in the repo root (it is
// git-ignored, never commit it). `storeFile` is relative to the repo root:
//   storeFile=release.jks
//   storePassword=********
//   keyAlias=linkitpos
//   keyPassword=********
// Without that file `:app:assembleRelease` still builds, but the APK is unsigned
// (not installable / not uploadable to a store).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.isNotEmpty()

android {
    namespace = "com.vision.pos.printer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vision.pos.printer"
        minSdk = 24
        targetSdk = 34
        // Incremented for every upload: a higher versionCode is what lets an installed
        // copy be upgraded in place.
        //   1.0.1 = Linkit icon, overscroll reload fix, reliable offline/Refresh screen
        //   1.0.2 = on-demand reload button, responsive layout shim
        //   1.0.3 = recovery and printing hardening: renderer-dead retry, shared Bluetooth
        //           permission request, bridge promise/id fixes, USB interface lookup,
        //           BLE link-drop reporting, no BLE reprint after a failed SPP write;
        //           landscape-only UI from launch
        versionCode = 4
        versionName = "1.0.3"

        // Surfaced to the WebView as BuildConfig.POS_URL; see `posUrl` above.
        buildConfigField("String", "POS_URL", "\"$posUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep release assembly fast and unblocked; run `:app:lint` on demand instead.
    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        // Only configured when keystore.properties exists (see the top of the file).
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
