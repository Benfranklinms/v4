plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.benfranklin.itune"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.benfranklin.itune"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "0.2.4"
        // APP_ABIS lets the CI smoke test add x86_64 for the emulator; release builds are arm64 only.
        ndk { abiFilters += (System.getenv("APP_ABIS") ?: "arm64-v8a").split(",").map { it.trim() } }
        buildConfigField("String", "UPDATE_REPO", "\"Benfranklinms/v4\"")
        // Optional scrobbling keys, injected by CI from secrets; empty = feature hidden.
        buildConfigField("String", "LASTFM_API_KEY", "\"${System.getenv("LASTFM_API_KEY") ?: ""}\"")
        buildConfigField("String", "LASTFM_API_SECRET", "\"${System.getenv("LASTFM_API_SECRET") ?: ""}\"")
    }

    // Release key: keystore/release.p12.enc (openssl aes-256-cbc, pbkdf2 200k). CI decrypts it with the
    // APP_STORE_PASSWORD secret; local builds point APP_STORE_FILE at the plain key in the private secrets/ folder.
    val storeFileEnv = System.getenv("APP_STORE_FILE")
    val releaseStore = if (storeFileEnv != null) file(storeFileEnv) else rootProject.file("keystore/release.p12")
    val storePass = System.getenv("APP_STORE_PASSWORD")
    val hasReleaseKey = releaseStore.exists() && !storePass.isNullOrEmpty()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStore
                storeType = "PKCS12"
                storePassword = storePass
                keyAlias = "itune"
                keyPassword = storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = false
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.glance.appwidget)
    implementation("${libs.jna.get()}@aar")
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.profileinstaller)
    implementation(libs.haze)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
}

// The glass guard (live blur on real phones, legible album-tinted chrome) runs with every release build.
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }
