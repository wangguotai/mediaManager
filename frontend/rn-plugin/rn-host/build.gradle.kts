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
    
    // 配置 jniLibs 源目录，包含 SO 库
    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
    
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

dependencies {
    // React Native 核心依赖 - 使用 compileOnly，不打包进 AAR
    // 最终由 composeApp 打包这些依赖
    compileOnly(libs.react.android)
    compileOnly(libs.react.hermes.android)
    
    // AndroidX
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.material)
    compileOnly(libs.kotlinx.coroutines.android)
}
