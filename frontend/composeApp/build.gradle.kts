import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.ksp)
}
// 应用更新 InitFeature.kt 的脚本
apply(from = "../scripts/composeApp/updateInitFeature.gradle.kts")
// 应用 Manager 初始化聚合脚本
apply(from = "../scripts/composeApp/updateInitManager.gradle.kts")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core)
            // EncryptedSharedPreferences：安全存储 JWT token（见 SettingsStorage.android.kt）
            implementation(libs.androidx.security.crypto)
//            implementation(project(":rn-plugin:rn-android"))
        }
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.featureMedia)
            implementation(projects.featureCommon)
            implementation(projects.baseNetwork)
            implementation(projects.rnModule)
            implementation(libs.kotlinx.serialization.json)
            // 对应的生成资源强要求，必须有该依赖
            implementation(libs.compose.components.resources)
            // Coil 3 for KMP image loading
//            implementation(libs.coil.compose)
//            implementation(libs.coil.compose.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 添加KSP生成的代码到源集
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// KSP依赖配置
dependencies {
    add("kspCommonMainMetadata", project(":ksp-processor"))
}

// 确保所有消费 commonMain 的编译任务都依赖 kspCommonMainKotlinMetadata。
// 原第67行用 name.startsWith("ksp") 匹配，只让 KSP 同族任务互相依赖，
// 并未让 compileDebugKotlinAndroid / compileReleaseKotlinAndroid 依赖 KSP 任务，
// 一直被"KSP 判 UP-TO-DATE、产物巧合存在"的缓存幽灵掩盖。
// 强制每次真跑(见下)后幽灵消失，Gradle task-output 校验即暴露此隐式消费缺失：
// "uses this output of :composeApp:kspCommonMainKotlinMetadata without declaring an explicit dependency"。
// 用 compile 前缀统一覆盖 compileDebug/ReleaseKotlinAndroid + compileCommonMainKotlinMetadata 等。
tasks.configureEach {
    if (name.startsWith("compile") && name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

// 彻底禁用 KSP metadata 任务的构建缓存与 UP-TO-DATE 旁路。
// 根因同 feature-common/rn-module：org.gradle.caching + configuration-cache 下，
// KSP 任务会被判 UP-TO-DATE / FROM-CACHE 而不真正生成，build/generated/ksp/... 为空，
// kotlin.srcDir 读到空目录，导致编译报 Unresolved reference。
// upToDateWhen{false}+cacheIf{false} 双保险强制每次真跑，产物永远新鲜。
tasks.matching { it.name == "kspCommonMainKotlinMetadata" }.configureEach {
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
}


android {
    namespace = "com.wgt"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildToolsVersion = libs.versions.android.buildToolsVersion.get().toString()

    defaultConfig {
        applicationId = "com.wgt"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    // 禁用testOnly标志，避免INSTALL_FAILED_TEST_ONLY错误
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "app-${buildType.name}.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.wgt.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.wgt"
            packageVersion = "1.0.0"
        }
    }
}

