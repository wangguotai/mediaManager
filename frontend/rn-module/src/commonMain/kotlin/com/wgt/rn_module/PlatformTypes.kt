package com.wgt.rn_module

/**
 * 平台特定 ReactHost 抽象接口
 * 
 * 在 Android 平台映射为实际的 ReactHost
 * 在 iOS 平台提供空实现或等效包装
 */
expect interface PlatformReactHost

/**
 * 平台特定 ReactContext 抽象接口
 * 
 * 在 Android 平台映射为实际的 ReactContext
 * 在 iOS 平台提供空实现或等效包装
 */
expect interface PlatformReactContext
