pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://repo.opencollab.dev/maven-snapshots")
        }
        maven {
            url = uri("https://repo.opencollab.dev/maven-releases")
        }
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "OxClient"
include(":app")