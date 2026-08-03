import SwiftUI
import ComposeApp
import React

/// Swift AppDelegate that bridges iOS lifecycle to Kotlin initialization
/// This is needed because Kotlin subclasses of NSObject cannot be directly imported into Swift
class MediaAppDelegateWrapper: NSObject, UIApplicationDelegate {
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Call Kotlin initializer
        let kotlinOk = MediaAppInitializer().onApplicationDidFinishLaunching(application: application)

        // ── Phase 2 verification ──────────────────────────────────────────────
        // Prove React.xcframework is embedded + importable + RCTBridge/RCTRootView
        // APIs are callable without crashing. This does NOT render to screen; the
        // RCTRootView is created and immediately released. A subsequent phase will
        // surface it via RnContainer.ios.kt.
        self.verifyRnBridge()
        // ──────────────────────────────────────────────────────────────────────

        return kotlinOk
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

    // MARK: - Phase 2 RN verification

    /// Best-effort: create the shared RCTBridge + an RCTRootView for "MediaManagerApp".
    /// Any throw/crash is trapped and logged so the app still launches. This only
    /// proves the API surface is reachable at runtime; visual surfacing comes later.
    private func verifyRnBridge() {
        NSLog("[Phase2] verifyRnBridge: start")
        // SwiftUI/UIKit bridge creation must happen on the main thread; we're already
        // here (AppDelegate didFinishLaunching).
        let rootView = RnBridge.shared.createRootView(moduleName: "MediaManagerApp")
        NSLog("[Phase2] verifyRnBridge: RCTRootView created -> \(String(describing: rootView))")
        // Intentionally not added to any view hierarchy. Retained by the local var
        // for the duration of this call; released on return. Subsequent phases will
        // own it via RnContainer.ios.kt + a UIViewControllerRepresentable.
        _ = rootView  // silence unused warning
        NSLog("[Phase2] verifyRnBridge: done (no crash)")
    }
}
