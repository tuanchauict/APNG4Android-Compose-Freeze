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
        // APNG4Android is published via JitPack as com.github.penfeizhou.APNG4Android.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "APNG4Android-Compose-Freeze"
include(":app")
