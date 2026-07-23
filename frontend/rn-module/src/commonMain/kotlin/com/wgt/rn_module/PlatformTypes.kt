package com.wgt.rn_module

/**
 * 平台特定 ReactHost 抽象
 * 使用 sealed interface 避免 typealias 与 final class 的兼容性问题
 */
expect sealed interface PlatformReactHost

/**
 * 平台特定 ReactContext 抽象
 * 使用 sealed interface 避免 typealias 与 final class 的兼容性问题
 */
expect sealed interface PlatformReactContext
