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
//include(":rn-plugin")
include(":rn-plugin:rn-android")

// 仅在 Android 编译时动态包含 aar-* 模块
// 检查是否正在执行 Android 相关任务
val isAndroidBuild = gradle.startParameter.taskNames.any { task ->
    task.contains("android", ignoreCase = true) ||
    task.contains("assemble", ignoreCase = true) ||
    task.contains("bundle", ignoreCase = true) ||
    task.contains("install", ignoreCase = true) ||
    task.contains("lint", ignoreCase = true)
}

if (isAndroidBuild) {
    File("$settingsDir/build").listFiles { _, name ->
        if (name.startsWith("aar-")) {
            include(":$name")
            project(":$name").projectDir = File("$settingsDir/build/$name")
        }
        true
    }
}
