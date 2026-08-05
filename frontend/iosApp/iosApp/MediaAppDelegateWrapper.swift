import SwiftUI
import ComposeApp
import RNSDK

/// Swift AppDelegate that bridges iOS lifecycle to Kotlin initialization
/// This is needed because Kotlin subclasses of NSObject cannot be directly imported into Swift
class MediaAppDelegateWrapper: NSObject, UIApplicationDelegate {
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 初始化 RNSDK (对标 Android TestAarApplication.onCreate)
        // 模拟器无 Metro server, 统一用离线 jsbundle (main.jsbundle)
        RNSDK.initialize(useDevSupport: false)

        // Call Kotlin initializer
        return MediaAppInitializer().onApplicationDidFinishLaunching(application: application)
    }
    
    // Note: Background/Foreground lifecycle is handled by SwiftUI's scenePhase in iOSApp.swift
    // These methods are kept for compatibility with older iOS versions
    func applicationDidEnterBackground(_ application: UIApplication) {
        MediaAppInitializer().onApplicationDidEnterBackground()
    }
    
    func applicationWillEnterForeground(_ application: UIApplication) {
        MediaAppInitializer().onApplicationWillEnterForeground()
    }
    
    func applicationWillTerminate(_ application: UIApplication) {
        MediaAppInitializer().onApplicationWillTerminate()
    }
}
