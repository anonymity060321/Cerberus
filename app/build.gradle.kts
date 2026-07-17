plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystoreFile = providers.environmentVariable("CERBERUS_KEYSTORE_FILE")
val releaseKeystorePassword = providers.environmentVariable("CERBERUS_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("CERBERUS_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("CERBERUS_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { provider -> provider.orNull?.isNotBlank() == true }

android {
    namespace = "com.yiran.cerberus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yiran.cerberus"
        minSdk = 36
        targetSdk = 36
        versionCode = 31
        versionName = "1.6.0-alpha3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = "29.0.14206865"

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            pickFirsts.add("**/libjnidispatch.so")
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 必须开启混淆，ProGuard 规则才会生效
            isMinifyEnabled = true
            // 必须开启资源压缩以减小体积
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

tasks.configureEach {
    if (name.endsWith("Release")) {
        doFirst {
            check(releaseSigningReady) {
                "Release signing requires CERBERUS_KEYSTORE_FILE, " +
                    "CERBERUS_KEYSTORE_PASSWORD, CERBERUS_KEY_ALIAS and " +
                    "CERBERUS_KEY_PASSWORD."
            }
            check(file(releaseKeystoreFile.get()).isFile) {
                "Release keystore does not exist: ${releaseKeystoreFile.get()}"
            }
        }
    }
}

// --- 优化的 Rust 构建集成 ---

val ndkDirProvider = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    .sdkComponents.ndkDirectory.map { it.asFile.absolutePath }

tasks.register<Exec>("cargoBuildAndroid") {
    group = "build"
    description = "Compile Rust library for Android"
    workingDir = file("../rust")
    inputs.files(
        fileTree("../rust/src"),
        file("../rust/Cargo.toml"),
        file("../rust/Cargo.lock"),
        file("../rust/build.rs")
    )
    outputs.dir(file("src/main/jniLibs"))
    
    val ndkPath = ndkDirProvider.getOrElse(System.getenv("ANDROID_NDK_HOME") ?: "")
    environment("ANDROID_NDK_HOME", ndkPath)
    
    val targets = listOf("aarch64-linux-android", "x86_64-linux-android")
    val archMap = mapOf(
        "aarch64-linux-android" to "arm64-v8a",
        "x86_64-linux-android" to "x86_64"
    )

    commandLine("cargo", "ndk")
    targets.forEach { target ->
        args("-t", archMap[target]!!)
    }
    args("-o", "${projectDir}/src/main/jniLibs", "build", "--release", "--locked")
}

tasks.register<Exec>("generateUniFFIBindings") {
    group = "build"
    description = "Generate Kotlin bindings using UniFFI"
    dependsOn("cargoBuildAndroid")
    workingDir = file("../rust")
    inputs.file(file("../rust/target/aarch64-linux-android/release/librust_core.so"))
    outputs.dir(file("src/main/java/uniffi"))
    
    commandLine(
        "cargo", "run", "--locked", "--bin", "uniffi-bindgen",
        "generate", "--library", "target/aarch64-linux-android/release/librust_core.so",
        "--language", "kotlin", 
        "--out-dir", "${projectDir}/src/main/java"
    )
}

tasks.named("preBuild") {
    dependsOn("generateUniFFIBindings")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended) 
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.autofill)
    
    implementation(libs.jna) {
        artifact { type = "aar" }
    }
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
