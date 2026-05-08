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
        maven("https://repo.opencollab.dev/maven-snapshots/")
        maven("https://repo.opencollab.dev/maven-releases/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "OxClient"
include(":app")
