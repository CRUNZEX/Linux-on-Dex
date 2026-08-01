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
    }
}

rootProject.name = "linux-on-dex"
include(":app")
include(":terminal-emulator")
include(":terminal-view")

// These small build adapters compile the upstream sources directly from the
// Termux submodule. Keeping the Gradle glue outside the submodule means an
// update remains a normal `git submodule update --remote` with no local edits.
project(":terminal-emulator").projectDir = file("termux-compat/terminal-emulator")
project(":terminal-view").projectDir = file("termux-compat/terminal-view")
