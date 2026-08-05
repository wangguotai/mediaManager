import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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

        // RNSDK ObjC Bridge (调用 RNSDK Swift 类 via ObjC runtime)
        iosTarget.compilations.getByName("main").cinterops {
            create("RNSDKBridge") {
                defFile = project.file("src/iosMain/cinterop/RNSDKBridge.def")
                packageName = "rnsdk.bridge"
                // RNSDKBridge.h 在 iosApp 目录, 需要指向
                includeDirs(project.file("../iosApp/iosApp"))
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core)
            // EncryptedSharedPreferences：安全存储 JWT token（见 SettingsStorage.android.kt）
            implementation(libs.androidx.security.crypto)
            // WorkManager：后台上传队列（PRD-v8 §2.2，UploadWorker / UploadQueueManager.android.kt）
            implementation(libs.androidx.work.runtime)
//            implementation(project(":rn-plugin:rn-android"))

            // ================================================================
            // RN 运行时依赖（V7 §3.1）——与 rn-module/build.gradle.kts 对齐版本
            // composeApp 需直接声明这些依赖，RnContainer.android.kt 才能引用
            // ReactHost / ReactRootView / Surface 等 RN API。
            // ================================================================
            // RN SDK AAR 由 rn-module 经 androidMainApi 传递暴露（不再直接声明，
            // 避免同一 AAR 被 resolve 两次导致 duplicate class）。
            // com.facebook.* 依赖仍需在此显式声明（rn-module 用 implementation 不传递）。
            implementation("com.facebook.fbjni:fbjni:0.7.0")
            implementation("com.facebook.soloader:soloader:0.12.1")
            implementation("com.facebook.yoga:proguard-annotations:1.19.0")
            implementation("com.facebook.fresco:fresco:3.6.0")
            implementation("com.facebook.fresco:middleware:3.6.0")
            implementation("com.facebook.fresco:imagepipeline-okhttp3:3.6.0")
            implementation("com.facebook.fresco:ui-common:3.6.0")
            implementation("com.squareup.okhttp3:okhttp:4.9.2")
            implementation("com.squareup.okhttp3:okhttp-urlconnection:4.9.2")
            implementation("com.squareup.okio:okio:2.9.0")
            implementation("javax.inject:javax.inject:1")
            implementation("com.google.code.findbugs:jsr305:3.0.2")
            implementation("com.facebook.infer.annotation:infer-annotation:0.18.0")
            implementation("androidx.appcompat:appcompat:1.7.0")
            implementation("androidx.appcompat:appcompat-resources:1.7.0")
            implementation("androidx.autofill:autofill:1.1.0")
            implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
            implementation("androidx.tracing:tracing:1.1.0")
            implementation("com.google.android.material:material:1.11.0")
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

    // ================================================================
    // Release 签名配置 (PRD-v8 §release)
    // - keystore: frontend/composeApp/release.keystore (本仓库提交)
    // - 密码从 local.properties 读取 (不提交)，fallback 到默认值
    //   (开源项目，默认值即 release.keystore 的真实密码)
    // ================================================================
    val signingProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = signingProps.getProperty("RELEASE_STORE_PASSWORD", "media-manager-release-2026")
            keyAlias = signingProps.getProperty("RELEASE_KEY_ALIAS", "media-manager")
            keyPassword = signingProps.getProperty("RELEASE_KEY_PASSWORD", "media-manager-release-2026")
        }
    }

    defaultConfig {
        applicationId = "com.wgt"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.0"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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

