import SwiftUI
import ComposeApp

/// Swift AppDelegate that bridges iOS lifecycle to Kotlin initialization
/// This is needed because Kotlin subclasses of NSObject cannot be directly imported into Swift
class MediaAppDelegateWrapper: NSObject, UIApplicationDelegate {
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
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
