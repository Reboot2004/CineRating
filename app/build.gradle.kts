import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    FileInputStream(localPropsFile).use { localProps.load(it) }
}
fun resolveOmdbKey(): String {
    return localProps.getProperty("omdbApiKey")
        ?: System.getenv("OMDB_API_KEY")
        ?: (project.findProperty("omdbApiKey") as String?)
        ?: ""
}

android {
    namespace = "com.cinerating"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cinerating"
        minSdk = 21
        targetSdk = 34
        versionCode = 13
        versionName = "1.13"
        buildConfigField("String", "OMDB_API_KEY", "\"${resolveOmdbKey()}\"")
        buildConfigField("String", "UPDATE_VERSION_URL", "\"https://reboot2004.github.io/CineRating/version.json\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProps.getProperty("cineRating.storeFile")
                ?: System.getenv("CINERATING_STORE_FILE")
            val storePass = localProps.getProperty("cineRating.storePassword")
                ?: System.getenv("CINERATING_STORE_PASSWORD")
            val keyAlias = localProps.getProperty("cineRating.keyAlias")
                ?: System.getenv("CINERATING_KEY_ALIAS")
            val keyPass = localProps.getProperty("cineRating.keyPassword")
                ?: System.getenv("CINERATING_KEY_PASSWORD")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePass
                keyAlias?.let { this.keyAlias = it }
                keyPassword = keyPass
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Uses release keystore when configured, otherwise falls back to debug
            // so first-time contributors can still build. CI always provides the key.
            signingConfig = signingConfigs.getByName(
                if ((localProps.getProperty("cineRating.storeFile")
                        ?: System.getenv("CINERATING_STORE_FILE")) != null
                ) "release" else "debug"
            )
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // On-device OCR for poster grids the accessibility tree can't see.
    // Model downloads via Play Services on first use (TV has Play Store).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
