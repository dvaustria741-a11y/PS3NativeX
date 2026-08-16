plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "net.rpcs3"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.ps3native.standard"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_JOB_POOLS=compile=8;link=1",
                    "-DCMAKE_JOB_POOL_COMPILE=compile",
                    "-DCMAKE_JOB_POOL_LINK=link"
                )
            }
        }
    }

    signingConfigs {
        create("custom-key") {
            val keystoreAlias = System.getenv("KEYSTORE_ALIAS") ?: ""
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""

            // Committed fallback keystore, same file every build/every
            // machine. This used to fall back to keytool-generating a
            // *fresh* ~/debug.keystore whenever KEYSTORE_PATH wasn't set --
            // which on GitHub Actions is every single debug build, since
            // runners are wiped between runs. That meant every CI debug
            // APK carried a brand-new random signing certificate, so
            // Android refused to install it over the previous one (or the
            // sideloader force-uninstalled first, wiping app data/settings
            // -- which is exactly why driver picks and other settings
            // looked like they "weren't persisting": the app itself was
            // being reinstalled from scratch on every build). Using one
            // fixed, repo-committed keystore for the non-release path
            // keeps every debug build's signature identical, so installs
            // are always clean in-place updates.
            val repoDebugKeystore = rootProject.file("debug.keystore")

            if (keystorePath.isNotEmpty() && file(keystorePath).exists() && file(keystorePath).length() > 0) {
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
                storeFile = file(keystorePath)
                storePassword = keystorePassword
            } else if (repoDebugKeystore.exists() && repoDebugKeystore.length() > 0) {
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeFile = repoDebugKeystore
                storePassword = "android"
            } else {
                // Last-resort fallback only -- should not be hit in this
                // repo since debug.keystore is committed, but keeps a
                // fresh checkout buildable even if it's ever deleted.
                val debugKeystoreFile = file("${System.getProperty("user.home")}/debug.keystore")

                println("⚠️ No committed or custom keystore found! generating an ephemeral one.")

                if (!debugKeystoreFile.exists()) {
                    Runtime.getRuntime().exec(
                        arrayOf(
                            "keytool", "-genkeypair",
                            "-v", "-keystore", debugKeystoreFile.absolutePath,
                            "-storepass", "android",
                            "-keypass", "android",
                            "-alias", "androiddebugkey",
                            "-keyalg", "RSA",
                            "-keysize", "2048",
                            "-validity", "10000",
                            "-dname", "CN=Android Debug,O=Android,C=US"
                        )
                    ).waitFor()
                }

                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeFile = debugKeystoreFile
                storePassword = "android"
            }
        }
    }

    flavorDimensions += "branding"

    productFlavors {
        create("standard") {
            dimension = "branding"
            applicationId = "com.ps3native.standard"
        }
        create("antutu") {
            dimension = "branding"
            applicationId = "com.antutu.ABenchMark"
        }
        create("ludashi") {
            dimension = "branding"
            applicationId = "com.ludashi.benchmark"
        }
        create("pubg") {
            dimension = "branding"
            applicationId = "com.tencent.ig"
        }
    }

    buildTypes {
        debug {
            // Was unset, so AGP fell back to its own built-in "debug"
            // signingConfig, which auto-creates ~/.android/debug.keystore
            // if missing -- a fresh, different certificate on every CI
            // runner. Pinning this to the same custom-key config the
            // release build uses (which now prefers the repo-committed
            // debug.keystore) keeps every debug APK's signature identical
            // across builds and machines.
            signingConfig = signingConfigs.getByName("custom-key")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=RelWithDebInfo")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("custom-key")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }


    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    sourceSets["main"].assets.srcDir(
        files(layout.buildDirectory.dir("generated/rpcs3-assets"))
            .builtBy("copyRpcs3Icons", "copyRpcs3Patches")
    )

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
    }
}

base.archivesName = "ps3native"

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.tooling.preview.android)
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-json:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-client-logging:3.0.3")
}

val copyRpcs3Icons by tasks.registering(Copy::class) {
    from(file("../../bin/Icons"))
    into(layout.buildDirectory.dir("generated/rpcs3-assets/Icons"))
}

val copyRpcs3Patches by tasks.registering(Copy::class) {
    from(file("../../bin/patches")) {
        include("patch.yml")
    }
    into(layout.buildDirectory.dir("generated/rpcs3-assets/patches"))
}
