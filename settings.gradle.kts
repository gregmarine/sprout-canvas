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

        // BOOX SDK — needed only once :canvas-onyx exists (Phase 4). Onyx publishes over plain
        // HTTP, hence isAllowInsecureProtocol. Consuming apps that use the Onyx adapter must add
        // this same block; apps that do not never see it. See PLAN.md D1, §3.2.
        //
        // maven {
        //     url = uri("http://repo.boox.com/repository/maven-public/")
        //     isAllowInsecureProtocol = true
        // }
    }
}

rootProject.name = "sprout-canvas"

include(":canvas")
include(":lab")
