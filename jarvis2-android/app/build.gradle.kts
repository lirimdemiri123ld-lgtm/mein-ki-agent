plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ch.lirim.jarvis2"
    compileSdk = 36

    defaultConfig {
        // Eigene App-ID: Jarvis 2.0 wird NEBEN dem alten Jarvis installiert und ändert ihn nicht.
        applicationId = "ch.lirim.jarvis2"
        minSdk = 31
        targetSdk = 35
        versionCode = runNumber
        versionName = "2.0.$runNumber"
    }

    signingConfigs {
        create("jarvis2") {
            storeFile = file("jarvis2.keystore")
            storePassword = "jarvis2pw"
            keyAlias = "jarvis2"
            keyPassword = "jarvis2pw"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis2")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis2")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
