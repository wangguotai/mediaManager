# =============================================================================
# Media Manager — ProGuard / R8 keep rules for release minify
# =============================================================================

# ---------- kotlinx.serialization ----------
# 序列化插件生成的 $$serializer  companion 必须保留，否则反射实例化失败。
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 所有 @Serializable 类的 $serializer 内部类
-keepclassmembers @kotlinx.serialization.Serializable class **$$serializer {
    *;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 反射访问字段名——保留 @SerialName 注解与字段名
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <fields>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# ---------- 应用自有类（KMP Compose + 业务逻辑） ----------
# Compose 编译器生成的可调用入口；业务 DTO/反射使用风险高，整体保留 com.wgt.*
-keep class com.wgt.** { *; }

# ---------- 第三方 SDK / 运行时依赖 ----------
# Ktor 客户端（baseNetwork 模块使用）
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-keep class io.ktor.utils.io.** { *; }

# OkHttp / Okio——R8 官方规则之外的补充（平台使用的反射）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# React Native（rn-module / Android runtime）
-keep class com.facebook.** { *; }
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.soloader.** { *; }
-keep class com.facebook.yoga.** { *; }
-keep class com.facebook.fresco.** { *; }
-keep,includedescriptorclasses class com.facebook.react.bridge.** { *; }
# RN 使用大量 @DoNotStrip 注解；让 R8 尊重它
-keep class com.facebook.proguard.annotations.** { *; }
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
}
-keepclassmembers @com.facebook.proguard.annotations.DoNotStripAnyClass class * { *; }

# androidx.security.crypto（EncryptedSharedPreferences 反射）
-keep class androidx.security.crypto.** { *; }

# WorkManager（Worker 子类反射实例化）
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# Coroutines 内部
-dontwarn kotlinx.coroutines.**

# Ktor 引用了 JVM management 类 (Android 上不存在，仅桌面端用)
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ---------- Kotlin 反射 / 元数据 ----------
# ksp-processor 生成的代码与 Kotlin reflection 元数据保留
-keep class kotlin.** { *; }
-keep class kotlin.reflect.** { *; }
-keepattributes Kotlin*

# rnsdk.bridge cinterop 生成类（iOS 不影响 Android，此处忽略；为防止名字碰撞略加宽）
# (no-op)

# ---------- media.MediaMetadata（PRD task 明确要求） ----------
-keep class media.** { *; }

# ---------- 调试友好 ----------
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
