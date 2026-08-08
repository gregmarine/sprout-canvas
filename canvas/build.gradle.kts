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

    compilerOptions {
        // Annotations on a constructor property (@ColorInt on ToolSpec.color, @InkChannels on
        // StrokeSamples.channels) currently land on the value parameter only, so a consumer reading
        // the *getter* sees nothing and lint stays silent. This is Kotlin's announced future
        // default (KT-73255); opting in early puts the annotation on the property too, which is the
        // only reason to annotate a public API in the first place.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // The ONLY required dependency. A drawing library must not dictate the host app's stack —
    // no coroutines, no serialization, no Material, no Compose (PLAN.md §6).
    //
    // `api`, not `implementation`: @ColorInt, @IntDef, @MainThread and @RestrictTo appear on the
    // public surface, and an annotation a consumer's compiler cannot resolve is an annotation that
    // does nothing. Still exactly one dependency.
    api(libs.androidx.annotation)

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
