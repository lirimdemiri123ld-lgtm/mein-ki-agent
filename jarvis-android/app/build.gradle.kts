plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ch.lirim.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.lirim.jarvis"
        minSdk = 31
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis123"
            keyAlias = "jarvis"
            keyPassword = "jarvis123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        noCompress += listOf("mdl", "fst", "conf", "int", "mat", "uuid")
    }
    packaging {
        jniLibs { pickFirsts += listOf("**/libc++_shared.so", "**/liblitertlm_jni.so") }
        resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(listOf("-Xskip-metadata-version-check", "-Xlambdas=class"))
    }
}
