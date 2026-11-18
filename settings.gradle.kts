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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF (com.artifex.mupdf:fitz) is published by Artifex, not on Maven Central.
        // HTTPS only; the content filter keeps this repository from being consulted for
        // any other coordinate.
        maven {
            url = uri("https://maven.ghostscript.com")
            content {
                includeGroup("com.artifex.mupdf")
            }
        }
    }
}

rootProject.name = "Peachy"
include(":app")
include(":buttplug-core")
include(":buttplug-compose")

// Permissionless, locally signed measurement APK. Never part of a normal build.
if (providers.gradleProperty("peachy.perfQa").orNull == "true") {
    include(":perfqa")
    project(":perfqa").projectDir = file("tools/perfqa")
}
