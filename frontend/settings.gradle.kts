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
        // 本地 flatDir 仓库：承载 rn-module/libs 下的 rn-sdk-debug.aar。
        // rn-module 是 com.android.library（产 AAR），AGP 的 hasLocalAarDeps 校验禁止
        // “AAR 产物模块用 fileTree 直接依赖本地 .aar”。改为经此 flatDir 仓库以“外部依赖”
        // 形式引用（rn-module: implementation("rn-sdk-debug@aar")），绕过该校验。
        // 路径相对 settings 所在目录（frontend）。
        flatDir {
            dirs("rn-module/libs")
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
