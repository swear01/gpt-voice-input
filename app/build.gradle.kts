import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Release signing
//
// CI (GitHub Actions) injects the keystore and credentials through environment
// variables (see .github/workflows/release.yml). Local builds may instead
// provide a gitignored keystore.properties plus keystore/release.keystore.
// Without either, release builds are produced unsigned (debug signing is used
// for day-to-day development). Keystores and passwords are never committed.
// ---------------------------------------------------------------------------

fun loadLocalSigningProps(): Properties {
    val props = Properties()
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    return props
}

fun resolveReleaseKeystore(): File? {
    val envB64 = System.getenv("GVI_KEYSTORE_BASE64")
    if (!envB64.isNullOrBlank()) {
        val f = File(rootProject.layout.buildDirectory.get().asFile, "signing/release.keystore")
        f.parentFile.mkdirs()
        f.writeBytes(Base64.getDecoder().decode(envB64))
        return f
    }
    val local = rootProject.file("keystore/release.keystore")
    return local.takeIf { it.exists() }
}

val localSigningProps = loadLocalSigningProps()

android {
    namespace = "org.gptvoiceinput"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.gptvoiceinput"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GVI_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("GVI_VERSION_NAME") ?: "0.1.0"
    }

    sourceSets {
        getByName("main") {
            // Only the generic public default ships. Personal configuration
            // is runtime data (Settings → Advanced → Import), never an asset.
            assets.srcDirs("src/main/assets")
        }
    }

    signingConfigs {
        create("release") {
            resolveReleaseKeystore()?.let { ks ->
                storeFile = ks
                storePassword = System.getenv("GVI_STORE_PASSWORD") ?: localSigningProps.getProperty("storePassword")
                keyAlias = System.getenv("GVI_KEY_ALIAS") ?: localSigningProps.getProperty("keyAlias")
                keyPassword = System.getenv("GVI_KEY_PASSWORD") ?: localSigningProps.getProperty("keyPassword")
                require(
                    !storePassword.isNullOrEmpty() &&
                        !keyAlias.isNullOrEmpty() &&
                        !keyPassword.isNullOrEmpty()
                ) {
                    "Release keystore found but signing credentials are missing. " +
                        "Set keystore.properties or GVI_STORE_PASSWORD/GVI_KEY_ALIAS/GVI_KEY_PASSWORD."
                }
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
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Required for Robolectric to inflate real layouts/resources.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
