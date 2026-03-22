import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}


kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
            api(projects.protobufGen)
            // 添加协程依赖
            api(libs.kotlinx.coroutines.core)

            // Compose 基础依赖 - 使用 api 暴露给依赖此模块的项目
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            api(libs.compose.components.resources)
            api(libs.compose.uiToolingPreview)

            // Lifecycle 相关依赖 - 使用 api 暴露，确保使用 Compose Multiplatform 提供的版本
            // 这样依赖 shared-ui 的模块会自动使用这些版本，避免 KLIB resolver 警告
            api(libs.androidx.lifecycle.viewmodelCompose)
            api(libs.androidx.lifecycle.runtimeCompose)
        }

        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
            api(libs.androidx.appcompat)
            api(libs.androidx.core)
            api(libs.compose.uiToolingPreview)
            api(libs.androidx.activity.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.wgt.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildToolsVersion.get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
