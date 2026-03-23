plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wgt.rnhost"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        
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
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // React Native - 使用 api 让依赖传递
    api("com.facebook.react:react-android:0.82.1")
    api("com.facebook.react:hermes-android:0.82.1")
    
    // AndroidX
    api("androidx.core:core-ktx:1.17.0")
    api("androidx.appcompat:appcompat:1.7.1")
    api("com.google.android.material:material:1.12.0")
}
