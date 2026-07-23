import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.ksp)
}

kotlin {

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "com.wgt.feature"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    val xcfName = "featuresKit"

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
                implementation(project(":shared"))
            }
        }

        androidMain {
            dependencies {
                // Add Android-specific dependencies here. Note that this source set depends on
                // commonMain by default and will correctly pull the Android artifacts of any KMP
                // dependencies declared in commonMain.
            }
        }


        iosMain {
            dependencies {
                // Add iOS-specific dependencies here. This a source set created by Kotlin Gradle
                // Plugin (KGP) that each specific iOS target (e.g., iosX64) depends on as
                // part of KMP's default source set hierarchy. Note that this source set depends
                // on common by default and will correctly pull the iOS artifacts of any
                // KMP dependencies declared in commonMain.
            }
        }
    }

}

// 添加KSP生成的代码到源集
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// KSP依赖配置 - 仅生成 commonMain 代码
dependencies {
    add("kspCommonMainMetadata", project(":ksp-processor"))
//    add("kspAndroid", project(":ksp-processor"))
//    add("kspIosArm64", project(":ksp-processor"))
//    add("kspIosSimulatorArm64", project(":ksp-processor"))
}

// 禁用 KSP metadata 任务的构建缓存。
// 原因：KSP 的缓存键不能随 ksp-processor 源码变更而失效，processor 变更后
// 会命中旧的（空的）缓存快照，Gradle 从缓存恢复时会先清空输出目录，
// 把真实生成的代码删掉，导致 compileAndroidMain 等任务报 Unresolved reference。
// 该任务执行很快，禁用缓存可保证每次都真正生成代码。
tasks.named("kspCommonMainKotlinMetadata") {
    outputs.cacheIf { false }
}

// 确保所有消费 commonMain 的编译任务都依赖 kspCommonMainKotlinMetadata，
// 否则 clean 后 build/generated/ksp/... 被清理，编译任务不会重新触发生成，
// 导致 KSP 生成的符号（如 permission）无法解析。
// 注意：compileAndroidMain 和 compileCommonMainKotlinMetadata 都不以 "compileKotlin" 开头，
// 故用 "compile" 前缀统一覆盖。
tasks.configureEach {
    if (name.startsWith("compile") && name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}