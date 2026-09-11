import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * Release signing, read from `keystore.properties` at the repo root or from environment variables.
 *
 * Both are gitignored and neither is required: a fresh clone and CI build an *unsigned* release,
 * which is what lets the pipeline verify the release variant's merged manifest without anyone
 * handing it a key. See docs/play-listing.md.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.yputria.remoteear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yputria.remoteear"
        minSdk = 29
        targetSdk = 36
        // Play retires a version code permanently the moment a bundle carrying it is uploaded -
        // even to a discarded release, even to internal testing. Code 1 went up with the broken
        // privacy-policy bullets; this is the fixed artifact and needs its own pair.
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "REMOTEEAR_STORE_FILE")?.let {
                storeFile = file(it)
                storePassword = signingValue("storePassword", "REMOTEEAR_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "REMOTEEAR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "REMOTEEAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 on. For a microphone app the size reduction is beside the point; what matters is
            // that the shipped binary contains only what is reachable, so "no network code" is a
            // property of the artifact and not just of the source.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Null when no key is configured, which produces an unsigned release rather than a
            // build failure - see the note above.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
