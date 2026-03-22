# React Native ProGuard 规则
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }
-dontwarn com.facebook.react.**

# Keep native methods
-keepclassmembers class * { native <methods>; }

# Keep JavaScript interfaces
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactMethod <methods>;
}

-keepclassmembers class * {
    @com.facebook.react.uimanager.annotations.ReactProp <methods>;
}

-keepclassmembers class * {
    @com.facebook.react.uimanager.annotations.ReactPropGroup <methods>;
}
