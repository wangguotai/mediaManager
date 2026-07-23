import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
}

kotlin {

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
//    androidLibrary {
//        namespace = "com.wgt.rn_module"
//        compileSdk = 36
//        minSdk = 24
//
//        // 编译选项必须与 KSP Processor 的 JVM 版本一致
//        compilerOptions {
//            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
//        }
//    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    val xcfName = "rn-moduleKit"
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
            }
        }
    }
}

// KSP 配置
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

dependencies {
    // 只在 commonMain 中生成代码，这样可以通过 expect/actual 模式在多平台共享
    add("kspCommonMainMetadata", project(":ksp-processor"))
    // 注意：不要添加 kspAndroid/kspAndroidMain，否则会导致重复生成
}

// 彻底禁用 KSP metadata 任务的构建缓存与 UP-TO-DATE 旁路。
// 根因同 feature-common：org.gradle.caching + configuration-cache 下，
// 仅 cacheIf{false} 不阻止"从缓存恢复"和 UP-TO-DATE 跳过，KSP 产物会空，
// 导致下游编译报 Unresolved reference。upToDateWhen{false}+cacheIf{false} 双保险强制每次真跑。
tasks.matching { it.name == "kspCommonMainKotlinMetadata" || it.name == "kspCommonMainMetadata" }.configureEach {
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
}

// 配置 KSP 任务依赖 - 确保所有编译任务都依赖 KSP 生成
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    // 排除 KSP 任务本身，避免循环依赖
    if (!name.contains("ksp", ignoreCase = true)) {
        dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" || it.name == "kspCommonMainMetadata" })
    }
}

// 额外确保所有编译任务依赖 KSP（覆盖 compileAndroidMain 等）
tasks.matching { it.name.startsWith("compile") && !name.contains("ksp", ignoreCase = true) }.configureEach {
    dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" || it.name == "kspCommonMainMetadata" })
}


android {
    namespace = "com.wgt.rn_module"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildToolsVersion.get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // 启用 BuildConfig 生成
        buildFeatures {
            buildConfig = true
        }
    }
//    buildTypes {
//        release {
//            isMinifyEnabled = false
//        }
//        debug {
//            // Debug 配置
//        }
//    }
}
