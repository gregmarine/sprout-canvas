plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.symmetricalpalmtree.sprout.canvas.lab"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.sprout.canvas.lab"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Every device this Lab is installed on — BOOX, Supernote, Wacom Movink, S26U — is
            // 64-bit ARM. Shipping only arm64-v8a drops three unused ABIs' worth of Onyx native
            // libraries, including the one 4 KB-aligned library that fails Play's 16 KB page-size
            // check. A consuming app that uses :canvas-onyx wants this too (PLAN.md §5.7).
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            // The Onyx SDK ships its own libc++_shared.so, which collides with any other
            // dependency that bundles one. Packaging fails outright without this (PLAN.md §5.7).
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }

    buildTypes {
        debug {
            // Installs alongside a release build — the fleet carries both (PLAN.md D14).
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":canvas"))

    // The BOOX adapter. Opt-in by decision (D1) — the Lab opts in because it is the conformance
    // harness for every platform. A phone-only app simply omits this line and never inherits the
    // Onyx SDK's native libraries or the insecure BOOX maven repo.
    implementation(project(":canvas-onyx"))

    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
