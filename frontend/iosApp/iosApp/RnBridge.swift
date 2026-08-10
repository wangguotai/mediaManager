import UIKit
import React

/// Minimal Swift wrapper proving React.xcframework is usable from the iosApp target.
///
/// Phase 2 verification only: this creates a shared RCTBridge from the prebuilt
/// `main.jsbundle` (copied from media-manager's index.android.bundle) and exposes
/// a factory for RCTRootView. The goal is to prove `import React`,
/// `RCTBridge(initWithBundleURL:)`, and `RCTRootView(bridge:moduleName:)` all
/// compile and link against the embedded React.xcframework — runtime init success
/// (no crash) is a bonus, not a hard requirement of this phase.
///
/// On iOS the equivalent of Android's `ReactHost.createSurface(componentName:)`
/// is `RCTRootView(bridge:moduleName:initialProperties:)`.
final class RnBridge {

    /// Shared singleton bridge. Lazily created on first use.
    /// Kept alive for the app lifetime so multiple RCTRootViews can reuse it.
    static let shared = RnBridge()

    /// The lazily-initialized RCTBridge. Nil until first `createRootView` call.
    private(set) var bridge: RCTBridge?

    private init() {}

    /// Create (if needed) the shared bridge and return an RCTRootView for `moduleName`.
    ///
    /// - Parameters:
    ///   - moduleName: JS module name registered via AppRegistry.registerComponent
    ///                 (media-manager uses "MediaManagerApp").
    ///   - bundleName: Name of the .jsbundle resource inside the app bundle
    ///                 (default "main" -> main.jsbundle). No extension.
    /// - Returns: An RCTRootView bound to the shared bridge. Caller is responsible
    ///            for retaining it (e.g. by adding to a view hierarchy).
    @discardableResult
    func createRootView(moduleName: String, bundleName: String = "main") -> RCTRootView {
        ensureBridge(bundleName: bundleName)
        // RCTRootView must be created on the main thread.
        return RCTRootView(
            bridge: bridge!,
            moduleName: moduleName,
            initialProperties: nil
        )
    }

    /// Lazily build the shared RCTBridge from the bundled main.jsbundle.
    /// No-op if the bridge already exists.
    private func ensureBridge(bundleName: String) {
        if bridge != nil { return }
        guard let bundleURL = Bundle.main.url(forResource: bundleName, withExtension: "jsbundle") else {
            // Cannot find main.jsbundle in the app bundle. Phase 2 verification
            // needs this file present; log and bail out rather than crash.
            NSLog("[RnBridge] main.jsbundle not found in app bundle (looked for \(bundleName).jsbundle)")
            return
        }
        NSLog("[RnBridge] creating RCTBridge with bundleURL=\(bundleURL.path)")
        // RCTBridge(initWithBundleURL:launchOptions:) is deprecated under RN 0.84
        // new arch but remains the simplest entry point for a standalone RN surface
        // not using a full RCTAppDelegate. Suppression scoped to this call.
        bridge = RCTBridge(
            bundleURL: bundleURL,
            moduleProvider: { nil },
            launchOptions: nil
        )
    }
}
