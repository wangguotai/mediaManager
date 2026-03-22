/**
 * React Native 宿主模块
 * 
 * 这个模块：
 * 1. 依赖 RN 源码（解压后的 classes.jar）
 * 2. 提供 ReactHostManager 等核心类
 * 3. 其他模块只需要依赖 rn-host
 */

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.androidKotlin)
}

android {
    namespace = "com.wgt.rn.host"
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
        debug {
            isMinifyEnabled = false
        }
    }
    
    buildFeatures {
        buildConfig = true
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
    
    // 配置 AAR 输出
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// 从 rn-source 获取解压后的路径
val rnSourceExtracted = rootProject.file("rn-plugin/rn-source/extracted")
val reactClasses = rnSourceExtracted.resolve("react-android-0.82.1/classes.jar")
val hermesClasses = rnSourceExtracted.resolve("hermes-android-0.82.1/classes.jar")

dependencies {
    // 依赖 RN classes.jar（使用 api 让依赖传递）
    if (reactClasses.exists()) {
        println("📦 rn-host: 使用 rn-source 的 React Native")
        api(files(reactClasses))
    } else {
        println("⚠️  rn-host: 使用 Maven RN")
        api(libs.react.android)
    }
    
    if (hermesClasses.exists()) {
        api(files(hermesClasses))
    } else {
        api(libs.react.hermes.android)
    }
    
    // Soloader
    api("com.facebook.soloader:soloader:0.12.1")
    
    // Facebook Infer 注解 (RN 依赖)
    api("com.facebook.infer.annotation:infer-annotation:0.18.0")
    
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
}
