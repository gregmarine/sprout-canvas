plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.symmetricalpalmtree.sprout.canvas.onyx"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Keeps OnyxInkEngineFactory alive through R8 in a consuming app, so the reflective
        // discovery in EngineRegistry can still find it. AGP applies an AAR's consumer rules
        // automatically, so a host that shrinks its build gets this for free (PLAN.md D10).
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)

    // The adapter's own public surface is one factory object. Everything else is internal, and
    // explicit API mode is what keeps it that way — no Onyx type may reach a host app (PLAN.md D1).
    explicitApi()

    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // `api`, not `implementation`: this module's whole reason to exist is to supply an
    // InkEngineFactory from :canvas, and a consumer that adds the adapter is always adding it
    // alongside the core it extends.
    api(project(":canvas"))

    // The BOOX SDK. onyxsdk-pen brings onyxsdk-base (ResManager, the bitmap-backed pens' resource
    // loader), onyxsdk-penbrush and onyxsdk-geometry with it.
    implementation(libs.onyx.sdk.pen)
    implementation(libs.onyx.sdk.device)

    // The SDK's raw-drawing path calls hidden framework methods by reflection. Android 9+ blocks
    // that until an exemption is installed process-wide, which is the one thing this library asks
    // the host to authorise — SproutCanvas.initialize (PLAN.md D11).
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = "canvas-onyx"
            version = providers.gradleProperty("VERSION_NAME").get()

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("sprout-canvas-onyx")
                description.set(
                    "BOOX (Onyx SDK) hardware ink adapter for sprout-canvas. Optional — a host " +
                        "app that does not target BOOX never depends on it."
                )
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
