import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
}

val rnSdkAar = layout.projectDirectory.file("libs/rn-sdk-debug.aar")



kotlin {
    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "com.wgt.rn_module"
        compileSdk = 36
        minSdk = 24

        // RN BuildConfig 字段
        buildFeatures {
            buildConfig = true
        }

        defaultConfig {
            buildConfigField("boolean", "IS_NEW_ARCHITECTURE_ENABLED", "true")
            buildConfigField("boolean", "IS_FABRIC_ENABLED", "true")
            buildConfigField("boolean", "IS_EDGE_TO_EDGE_ENABLED", "true")
        }

        packaging {
            pickFirsts += listOf("**/*.so")
        }

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    val xcfName = "rn-moduleKit"

    iosX64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    // Source set declarations.
    // Declaring a target automatically creates a source set with the same name. By default, the
    // Kotlin Gradle Plugin creates additional source sets that depend on each other, since it is
    // common to share sources between related targets.
    // See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.shared)
                // Add KMP dependencies here
            }
        }
        androidMain {
            dependencies {
                // Add Android-specific dependencies here. Note that this source set depends on
                // commonMain by default and will correctly pull the Android artifacts of any KMP
                // dependencies declared in commonMain.
                
                // ============================================================================
                // RN SDK (AAR)
                // ============================================================================
                implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
                
                // ============================================================================
                // React Native 编译依赖 (api 由 rn-sdk 提供，需要在应用中显式声明)
                // ============================================================================
                // Core dependencies
                implementation("com.facebook.fbjni:fbjni:0.7.0")
                implementation("com.facebook.soloader:soloader:0.12.1")
                implementation("com.facebook.yoga:proguard-annotations:1.19.0")
                
                // Fresco (图片加载)
                implementation("com.facebook.fresco:fresco:3.6.0")
                implementation("com.facebook.fresco:middleware:3.6.0")
                implementation("com.facebook.fresco:imagepipeline-okhttp3:3.6.0")
                implementation("com.facebook.fresco:ui-common:3.6.0")
                
                // Network
                implementation("com.squareup.okhttp3:okhttp:4.9.2")
                implementation("com.squareup.okhttp3:okhttp-urlconnection:4.9.2")
                implementation("com.squareup.okio:okio:2.9.0")
                
                // Annotations & Utils
                implementation("javax.inject:javax.inject:1")
                implementation("com.google.code.findbugs:jsr305:3.0.2")
                implementation("com.facebook.infer.annotation:infer-annotation:0.18.0")

                // ============================================================================
                // AndroidX (RN 0.84.1 使用)
                // ============================================================================
                implementation("androidx.annotation:annotation:1.6.0")
                implementation("androidx.core:core-ktx:1.12.0")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.appcompat:appcompat-resources:1.7.0")
                implementation("androidx.autofill:autofill:1.1.0")
                implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
                implementation("androidx.tracing:tracing:1.1.0")
                implementation("com.google.android.material:material:1.11.0")
            }
        }
        iosMain {
            dependencies {
                // Add iOS-specific dependencies here. This a source set created by Kotlin Gradle
                // Plugin (KGP) that each specific iOS target (e.g., iosX64) depends on as
                // part of KMP’s default source set hierarchy. Note that this source set depends
                // on common by default and will correctly pull the iOS artifacts of any
                // KMP dependencies declared in commonMain.
            }
        }
    }

}
