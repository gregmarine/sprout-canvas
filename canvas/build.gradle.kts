plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

/** The on-demand golden-image suite. See the task registration near the bottom of this file. */
val GOLDEN_TASK = "goldenTest"
val GOLDEN_TEST_CLASS = "com.symmetricalpalmtree.sprout.canvas.golden.GoldenImageTest"

android {
    namespace = "com.symmetricalpalmtree.sprout.canvas"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // The golden scenes are defined once and rendered by both tiers — Robolectric on the JVM
        // and instrumented on a device. Two copies of the scene definitions would drift, and a
        // golden suite whose two tiers disagree about what they are drawing measures nothing.
        getByName("test") { kotlin.srcDir("src/goldenShared/kotlin") }
        getByName("androidTest") { kotlin.srcDir("src/goldenShared/kotlin") }

        // The committed goldens live with the JVM tier's resources; the instrumented tier reads the
        // same files through assets, so both compare against one set of images.
        getByName("androidTest") { assets.srcDir("src/test/resources") }
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

    // The instrumented tier (PLAN.md §4.2). Stylus input is synthesized with MotionEvent.obtain,
    // so no human and no stylus are needed — but a real digitizer's declared motion ranges are,
    // which is the one thing a JVM test genuinely cannot supply.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

/**
 * The golden-image suite (PLAN.md D13, §4.1.1).
 *
 * Registered as its own task rather than folded into `test`, because pixel comparison and geometry
 * assertions want opposite things from a build: geometry should gate every commit, and pixels
 * should be looked at deliberately by someone who can tell a regression from an improvement.
 *
 * It reuses the unit tests' own classpath, which is what carries AGP's generated Robolectric
 * configuration — the golden suite runs in exactly the environment the rest of the JVM tests do.
 */
afterEvaluate {
    val unitTests = tasks.named<Test>("testDebugUnitTest")

    tasks.withType<Test>().configureEach {
        // Goldens are on demand. Left in the ordinary suite they would turn every toolchain bump
        // into a red build for a reason that has nothing to do with the change being made.
        if (name != GOLDEN_TASK) filter { excludeTestsMatching(GOLDEN_TEST_CLASS) }
    }

    tasks.register<Test>(GOLDEN_TASK) {
        group = "verification"
        description =
            "Runs the golden-image render regression suite (on demand — not part of `check`). " +
                "Pass -Psprout.golden.regenerate=true to accept the current rendering."
        testClassesDirs = files(unitTests.map { it.testClassesDirs })
        classpath = files(unitTests.map { it.classpath })
        dependsOn(unitTests.map { it.dependsOn })
        filter { includeTestsMatching(GOLDEN_TEST_CLASS) }
        systemProperty(
            "sprout.golden.regenerate",
            providers.gradleProperty("sprout.golden.regenerate").getOrElse("false"),
        )
        // The goldens are read from and written to the source tree, so there is nothing here for
        // Gradle to cache sensibly — and a cached "up to date" golden run is a golden run that did
        // not happen.
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
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
