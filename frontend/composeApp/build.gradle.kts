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
            implementation(project(":rn-plugin:rn-android"))
            implementation(files("../rn-plugin/output/rn-host.aar"))
            implementation(libs.react.android)
            implementation(libs.react.hermes.android)
        }
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.featureMedia)
            implementation(projects.featureCommon)
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
    add("kspAndroid", project(":ksp-processor"))
    add("kspIosArm64", project(":ksp-processor"))
    add("kspIosSimulatorArm64", project(":ksp-processor"))
}

// 配置KSP任务依赖
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
    .matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }.all {
    dependsOn("kspCommonMainKotlinMetadata")
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

// 应用更新 InitFeature.kt 的脚本
apply(from = "../scripts/composeApp/updateInitFeature.gradle.kts")
