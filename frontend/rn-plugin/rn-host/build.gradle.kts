/**
 * React Native 宿主模块
 * 
 * 依赖 MyApp/android/rn-aar 生成的 AAR
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

// 查找 MyApp 生成的 AAR
val myAppAar = file("libs/rn-aar-release.aar")
val myAppAarDebug = file("libs/rn-aar-debug.aar")

dependencies {
    // 优先使用 MyApp 生成的 AAR
    if (myAppAar.exists()) {
        println("📦 使用 MyApp 生成的 RN AAR: ${myAppAar.absolutePath}")
        implementation(files(myAppAar))
    } else if (myAppAarDebug.exists()) {
        println("📦 使用 MyApp 生成的 RN AAR (Debug): ${myAppAarDebug.absolutePath}")
        implementation(files(myAppAarDebug))
    } else {
        println("⚠️  MyApp AAR 不存在，使用 Maven 依赖")
        println("   请运行: cd MyApp/android && ./gradlew :rn-aar:assembleRelease")
        

    }

    // 降级方案：使用 Maven 依赖
    implementation(libs.react.android)
    implementation(libs.react.hermes.android)
    
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
}
