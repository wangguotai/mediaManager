# rn-android ProGuard 规则
-keep class com.wgt.rn_android.** { *; }
-dontwarn com.wgt.rn_android.**

# 保留 React Native 相关的类
-keep class com.wgt.rn.host.** { *; }
-dontwarn com.wgt.rn.host.**
