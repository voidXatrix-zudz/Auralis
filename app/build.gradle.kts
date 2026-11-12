import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing lives in keystore.properties (gitignored). When it is absent the release build
// stays unsigned; debug builds are unaffected. Keep ONE keystore for every published release.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

// Optional Clerk publishable key. Supply it in gradle.properties (CLERK_PUBLISHABLE_KEY=pk_test_…)
// or in local.properties. Clerk publishable keys are safe to ship in the client, but keeping it out
// of the repo (local.properties is gitignored) is the tidier default. When it is blank the app
// builds and runs exactly as before with no sign-in gate.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val clerkPublishableKey: String =
    (project.findProperty("CLERK_PUBLISHABLE_KEY") as String?)
        ?: localProperties.getProperty("CLERK_PUBLISHABLE_KEY")
        ?: ""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.auralis.app"
    // Clerk (and its okhttp 5 / androidx.browser 1.10 transitive deps) require API 36 to compile
    // against. targetSdk stays at 35 so runtime behaviour is unchanged.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.auralis.app"
        minSdk = 26
        targetSdk = 35
        // Single source of truth for app versioning. Bump BOTH for every release:
        // versionCode must always increase; versionName is what the updater compares.
        versionCode = 2
        versionName = "1.2.0"

        // Blank when no key was supplied; ClerkAuthRepository skips auth in that case.
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"$clerkPublishableKey\"")
    }

    signingConfigs {
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
            // Minification is enabled in Stage 7 once Room/Media3 keep rules are verified on device.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only when keystore.properties exists; otherwise the output is unsigned.
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        // Backports java.nio.file and URLDecoder.decode(String, Charset) to API 26+ so
        // NewPipeExtractor's Utils.decodeUrlUtf8 does not throw NoSuchMethodError on Android 12.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.newpipe.extractor)
    implementation(libs.clerk.android.api)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
