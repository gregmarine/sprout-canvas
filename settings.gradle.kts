pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // BOOX SDK — required by :canvas-onyx (Phase 4). Onyx publishes over plain HTTP, hence
        // isAllowInsecureProtocol. Consuming apps that use the Onyx adapter must add this same
        // block; apps that do not never see it, which is the whole point of D1's split.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
            // Only these groups come from here. Without the filter, every dependency in every
            // module — including :canvas, which must stay vendor-free — would be looked up against
            // an insecure host first, and a BOOX outage would fail builds that have no business
            // talking to BOOX at all.
            //
            // The four entries below are the complete list, and the last three are not obvious:
            // onyxsdk-base depends on three artifacts that were published to jcenter and never
            // migrated anywhere else, so the BOOX repo's proxy is now the only place they exist.
            // None may be excluded — dropping mmkv in particular risks NoClassDefFoundError at
            // runtime, because onyxsdk-base references it (PLAN.md §5.7).
            content {
                includeGroup("com.onyx.android.sdk")
                includeGroup("pub.devrel")             // easypermissions
                includeGroup("com.tencent")            // mmkv
                includeGroup("com.jakewharton.hugo.fix") // hugo-annotations
            }
        }
    }
}

rootProject.name = "sprout-canvas"

include(":canvas")
include(":canvas-onyx")
include(":lab")
