import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    // 连接Kotlin的MediaAppDelegate，使iOS应用生命周期与Kotlin代码绑定
    @UIApplicationDelegateAdaptor(MediaAppDelegateWrapper.self) var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { oldPhase, newPhase in
            switch newPhase {
            case .background:
                MediaAppInitializer().onApplicationDidEnterBackground()
            case .inactive:
                // App is transitioning or being interrupted
                break
            case .active:
                if oldPhase == .background || oldPhase == .inactive {
                    MediaAppInitializer().onApplicationWillEnterForeground()
                }
            @unknown default:
                break
            }
        }
    }
}
