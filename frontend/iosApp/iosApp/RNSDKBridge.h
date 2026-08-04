#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * RNSDK ObjC Bridge — 供 Kotlin/Native cinterop 调用.
 *
 * RNSDK Swift 类 (RNContainerManager/RNContainerView) 用 @objcMembers 标记,
 * 但 Kotlin/Native 无法直接 import Swift module. 通过此 ObjC wrapper 暴露 C 接口.
 *
 * 在 build.gradle.kts 中配置 cinterop:
 * ```
 * iosTarget.compilations.getByName("main").cinterops.create("RNSDKBridge") {
 *     defFile = project.file("src/iosMain/cinterop/RNSDKBridge.def")
 *     packageName = "rnsdk.bridge"
 * }
 * ```
 */

/// 初始化 RNSDK (对应 AppDelegate didFinishLaunching)
/// useDevSupport: DEBUG=true 连 Metro, Release=false 用离线 jsbundle
void RNSDKBridge_initialize(BOOL useDevSupport);

/// 创建 RN 视图 (对标 Android createReactSurfaceView)
/// moduleName: JS 侧 AppRegistry.registerComponent 注册的模块名
/// 返回 UIView, 宿主直接 addSubview
UIView *_Nullable RNSDKBridge_createView(NSString *moduleName);

/// 使用自定义 bundle 路径创建 RN 视图 (多 Bundle / 热更新场景)
UIView *_Nullable RNSDKBridge_createViewWithBundle(NSString *moduleName, NSString *bundlePath);

/// 设置离线 jsbundle 路径
void RNSDKBridge_setBundleURL(NSURL *url);

/// 设置 Metro server URL (Debug)
void RNSDKBridge_setDevServerURL(NSString *urlString);

NS_ASSUME_NONNULL_END
