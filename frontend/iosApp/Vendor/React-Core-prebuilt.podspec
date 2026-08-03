# Self-contained React-Core-prebuilt podspec for media-manager iosApp.
#
# This podspec does NOT require_relative any react-native source tree helpers
# (ReactNativeCoreUtils / add_rn_third_party_dependencies / min_supported_versions),
# so it can be consumed by an Xcode project that lives in a *different* git repo
# from the RN source (media-manager-ios-rn vs rn_test).
#
# The React.xcframework (prebuilt, downloaded from Maven during Phase 0 in rn_test)
# is vendored at Vendor/React.xcframework next to this podspec. Its static binary
# already has folly+hermes linked in; the third-party header pods declared as
# dependencies below only exist to satisfy the framework's public headers that
# #include <folly/...>, <glog/...>, <boost/...>, <fmt/...>, etc. during `import React`.
#
# Module identity: module_name 'React' + module.modulemap (umbrella React_Core/React_Core-umbrella.h)
# so Swift `import React` resolves via the framework's own module map.

Pod::Spec.new do |s|
  s.name                   = "React-Core-prebuilt"
  s.version                = "0.84.1"
  s.summary                = "The core of React Native prebuilt frameworks (self-contained)."
  s.homepage               = "https://reactnative.dev/"
  s.license                = { :type => "MIT" }
  s.author                 = "Meta Platforms, Inc. and its affiliates"
  s.platforms              = { :ios => "15.1" }
  s.source                 = { :path => '.' }

  # The vendored xcframework contains the full prebuilt React.framework
  # (device arm64 + simulator arm64/x86_64 + maccatalyst). Located at
  # Vendor/React.xcframework relative to this podspec.
  s.vendored_frameworks    = "React.xcframework"

  # Headers come from inside the xcframework slices (React.framework/Headers).
  # header_mappings_dir + source_files + public_header_files point CocoaPods
  # at them so HEADER_SEARCH_PATHS gets the framework header root.
  s.preserve_paths         = '**/*.*'
  s.header_mappings_dir    = 'React.xcframework/Headers'
  s.source_files           = 'React.xcframework/Headers/**/*.{h,hpp}'
  s.public_header_files    = 'React.xcframework/Headers/**/*.h'

  s.module_name            = 'React'
  # module.modulemap lives inside React.framework/Modules/ (per-slice) AND at
  # React.xcframework/Modules/module.modulemap (top-level). CocoaPods resolves
  # the framework module map automatically via vendored_frameworks; declaring
  # it explicitly here ensures `import React` uses the umbrella header.
  s.module_map             = 'React.xcframework/Modules/module.modulemap'

  # Third-party header pods. The prebuilt React.framework binary already links
  # folly+hermes statically, but its public headers still #include <folly/...>,
  # <glog/...>, <boost/...>, <fmt/...>, <double-conversion/...>, <fast_float/...>.
  # We pull these from the public CDN podspecs (header-only or source compile)
  # so the umbrella header chain resolves during `import React`.
  #
  # NOTE: RCT-Folly pulls folly + boost + DoubleConversion + fast_float + fmt
  # transitively, so we only need to declare glog + RCT-Folly + SocketRocket
  # here to get the whole header graph. RCT-Folly's public podspec is on the
  # CDN but it expects to be built as part of an RN project; to keep this
  # podspec self-contained we instead declare the *individual* header pods.
  s.dependency "glog"
  s.dependency "boost"
  s.dependency "DoubleConversion"
  s.dependency "fast_float"
  s.dependency "fmt"
  s.dependency "RCT-Folly"
  s.dependency "SocketRocket"

  # HEADER_SEARCH_PATHS mirrors what add_rn_third_party_dependencies would set
  # in source-build mode, so the framework headers find the third-party includes
  # at the standard CocoaPods layout ($(PODS_ROOT)/<name>).
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => [
      '$(inherited)',
      '$(PODS_ROOT)/glog',
      '$(PODS_ROOT)/boost',
      '$(PODS_ROOT)/DoubleConversion',
      '$(PODS_ROOT)/fast_float/include',
      '$(PODS_ROOT)/fmt/include',
      '$(PODS_ROOT)/SocketRocket',
      '$(PODS_ROOT)/RCT-Folly'
    ]
  }
end
