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
    
    // rn-host AAR 依赖（由 publish-aar.gradle.kts 脚本动态添加）
    // 如果 AAR 不存在，使用 project 依赖作为开发模式
    val aarFile = rootProject.file("rn-plugin/output/rn-host.aar")
    if (aarFile.exists()) {
        implementation(files(aarFile))
        println("[RN-Android] 使用本地 AAR: ${aarFile.absolutePath}")
    } else {
        // 开发模式：直接依赖 rn-host 项目
        println("[RN-Android] AAR 不存在，使用 project 依赖 (开发模式)")
    }
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
