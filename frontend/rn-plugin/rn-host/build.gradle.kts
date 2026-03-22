/**
 * React Native 宿主模块
 * 
 * 这个模块：
 * 1. 编译时依赖 RN 源码（解压后的 classes.jar）
 * 2. 运行时由最终应用提供 RN SO 库
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
val rnSourceExtracted = project(":rn-plugin:rn-source").file("extracted")
val reactExtracted = rnSourceExtracted.resolve("react-android-0.82.1")
val hermesExtracted = rnSourceExtracted.resolve("hermes-android-0.82.1")

dependencies {
    // 编译时依赖 RN classes.jar（不包含在最终 AAR 中）
    if (reactExtracted.resolve("classes.jar").exists()) {
        println("📦 使用 rn-source 解压的 React Native")
        compileOnly(files(reactExtracted.resolve("classes.jar")))
    } else {
        println("⚠️  rn-source 解压文件不存在，使用 Maven 依赖")
        compileOnly(libs.react.android)
    }
    
    if (hermesExtracted.resolve("classes.jar").exists()) {
        compileOnly(files(hermesExtracted.resolve("classes.jar")))
    } else {
        compileOnly(libs.react.hermes.android)
    }
    
    // Soloader
    compileOnly("com.facebook.soloader:soloader:0.12.1")
    
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
}
