import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "it.vfsfitvnm.vimusic"
    compileSdk = 37

    defaultConfig {
        // The source package stays it.vfsfitvnm.vimusic -- this is a fork, and the code is
        // still theirs. The application id is the identity on a device and in a store, and
        // that one has to be ours: the original already occupies the old id on F-Droid, and
        // two apps cannot share it.
        applicationId = "net.m4j.ViMusic"
        minSdk = 23
        targetSdk = 36
        versionCode = 21
        versionName = "0.6.0"
    }

    // Release signing is read from keystore.properties, which is deliberately untracked: the
    // keystore and its password must never enter the repository. Without that file the release
    // build falls back to the debug key below, which is fine for local builds but must not be
    // used for anything published -- the debug key ships with the SDK and everyone has it.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties().apply {
            keystorePropertiesFile.inputStream().use(::load)
        }

        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "ViMusic Debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appName"] = "ViMusic"
            // Falls back to the debug key when no keystore.properties is present, so a fresh
            // clone -- or F-Droid, which signs with its own key anyway -- still builds.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.all {
        kotlin.srcDir("src/$name/kotlin")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.version",
            "kotlin/**",
            "DebugProbesKt.bin"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Route0/1/2.invoke take the RouteHandlerScope as a context parameter, the
        // replacement for the context receivers this project was written against.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":compose-persist"))
    implementation(project(":compose-routing"))
    implementation(project(":compose-reordering"))
    implementation(project(":innertube"))
    implementation(project(":kugou"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ripple)
    implementation(libs.compose.activity)
    implementation(libs.compose.coil)
    implementation(libs.compose.shimmer)

    // Proof-of-origin token generation runs BotGuard in a WebView and talks to YouTube's
    // attestation endpoints directly; see service/potoken.
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.annotation)
    implementation(libs.palette)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)

    implementation(libs.room)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    coreLibraryDesugaring(libs.desugaring)
}
