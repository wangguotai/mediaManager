rootProject.name = "mediaManager"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {

        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        
        // React Native 依赖仓库
        maven {
            url = uri("https://repo1.maven.org/maven2")
        }
        // Facebook Maven 仓库 (React Native)
        maven {
            url = uri("https://maven.facebook.com")
        }
        // JitPack 仓库
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
include(":shared")

include(":protobuf-gen")
include(":base-network")
include(":feature-media")
include(":ksp-processor")
include(":feature-common")
include(":rn-module")
