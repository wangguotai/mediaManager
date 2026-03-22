package com.wgt.rn_android.bridge

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * Media Manager 的 React Native Package
 * 
 * 用于注册自定义的 Native Modules 和 View Managers
 */
class MediaManagerPackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(
            MediaManagerModule(reactContext),
            // 在这里添加更多的 Native Modules
        )
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
        // 如果需要自定义 View Manager，在这里添加
        // return listOf(MyCustomViewManager())
    }
}
