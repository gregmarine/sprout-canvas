plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.symmetricalpalmtree.sprout.canvas"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs merged Android resources to build its runtime environment.
            isIncludeAndroidResources = true
        }
    }

    // Publish the release variant with sources (PLAN.md D7 — mavenLocal, registry-agnostic).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)

    // Every declaration in the public surface must state its visibility and return type.
    // This is the library's contract; nothing leaks into it by accident (PLAN.md §6).
    explicitApi()
}

dependencies {
    // The ONLY required dependency. A drawing library must not dictate the host app's stack —
    // no coroutines, no serialization, no Material, no Compose (PLAN.md §6).
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = "canvas"
            version = providers.gradleProperty("VERSION_NAME").get()

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("sprout-canvas")
                description.set(
                    "Android stylus capture and rendering canvas for BOOX (Onyx SDK), " +
                        "Supernote, and standard Android stylus devices."
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
