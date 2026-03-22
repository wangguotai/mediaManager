plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.androidKotlin)
}

android {
    namespace = "com.wgt.rn_android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildToolsVersion.get().toString()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}

dependencies {
    // 基础依赖
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    
    // React Native 依赖（rn-android 需要直接编写 RN 代码）
    compileOnly(libs.react.android)
    compileOnly(libs.react.hermes.android)
    
    // rn-host 依赖
    // 注意：rn-android 是 Library 模块，不能直接依赖 AAR 文件
    // 只能使用 project 依赖，rn-host 的代码会在编译时可用
    compileOnly(project(":rn-plugin:rn-host"))
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
