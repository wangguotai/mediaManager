package com.wgt.rn_module

import android.app.Application
import android.content.Context
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.JSBundleLoader
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.annotations.UnstableReactNativeAPI
import com.facebook.react.defaults.DefaultComponentsRegistry
import com.facebook.react.defaults.DefaultReactHostDelegate
import com.facebook.react.defaults.DefaultTurboModuleManagerDelegate
import com.facebook.react.fabric.ComponentFactory
import com.facebook.react.runtime.ReactHostImpl
import com.facebook.react.runtime.hermes.HermesInstance
import com.facebook.react.shell.MainReactPackage
import java.io.File
import java.io.FileOutputStream

/**
 * RN 容器管理器
 * 使用新架构 ReactHost API，支持多 Bundle 管理和生命周期控制
 */
class RNContainerManager private constructor(private val application: Application) {

    companion object {
        @Volatile
        private var instance: RNContainerManager? = null

        @JvmStatic
        fun getInstance(context: Context): RNContainerManager {
            return instance ?: synchronized(this) {
                instance ?: RNContainerManager(context.applicationContext as Application).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Host 配置数据类
     */
    data class HostConfig(
        val hostId: String,
        val bundleAssetName: String,
        val mainComponentName: String,
        val jsMainModulePath: String = "index",
        val useDeveloperSupport: Boolean = BuildConfig.DEBUG,
        // V7 §3.2：热更新 override 路径——非空时优先从该路径加载 bundle（跳过 assets 复制）
        val bundleOverridePath: String? = null
    )

    /**
     * 存储所有创建的 ReactHost 实例
     */
    private val reactHostMap = mutableMapOf<String, ReactHost>()

    /**
     * 存储 Host 配置
     */
    private val hostConfigMap = mutableMapOf<String, HostConfig>()

    // 全局 ReactContext 监听器列表
    private val globalListeners = mutableListOf<ReactInstanceEventListener>()

    /**
     * 将 assets 中的 bundle 复制到文件系统
     */
    private fun copyBundleFromAssets(assetName: String): String {
        val bundleFile = File(application.filesDir, assetName)
        
        // 如果文件已存在且大小符合，直接返回路径
        try {
            application.assets.open(assetName).use { input ->
                val assetSize = input.available()
                if (bundleFile.exists() && bundleFile.length() == assetSize.toLong()) {
                    android.util.Log.d("RNContainerManager", "Bundle 已存在且大小匹配: ${bundleFile.absolutePath}")
                    return bundleFile.absolutePath
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RNContainerManager", "检查 bundle 大小时出错", e)
        }
        
        // 复制文件
        try {
            application.assets.open(assetName).use { input ->
                FileOutputStream(bundleFile).use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.d("RNContainerManager", "Bundle 复制成功: ${bundleFile.absolutePath}, 大小: ${bundleFile.length()}")
            return bundleFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("RNContainerManager", "复制 bundle 失败: $assetName", e)
            throw e
        }
    }

    /**
     * 创建 ReactHost 实例（新架构）
     */
    @OptIn(UnstableReactNativeAPI::class)
    private fun createReactHost(config: HostConfig): ReactHost {
        android.util.Log.d("RNContainerManager", "创建 ReactHost: hostId=${config.hostId}, bundleName=${config.bundleAssetName}, useDevSupport=${config.useDeveloperSupport}")
        
        // V7 §3.2：热更新——优先用 override 路径（从后端下载的 cache bundle），无则从 assets 复制
        // 最小 10KB 阈值：后端占位 bundle 只有 205 字节，不应当作真正的 bundle 使用
        val bundleFilePath = config.bundleOverridePath?.let { path ->
            val f = java.io.File(path)
            if (f.exists() && f.length() > 10240) {
                android.util.Log.d("RNContainerManager", "使用热更新 bundle: $path (${f.length()} bytes)")
                path
            } else {
                android.util.Log.w("RNContainerManager", "override bundle 太小或不存在 (${f.length()} bytes), 回退 assets")
                copyBundleFromAssets(config.bundleAssetName)
            }
        } ?: copyBundleFromAssets(config.bundleAssetName)
        
        val bundleLoader = JSBundleLoader.createFileLoader(
            bundleFilePath,
            config.bundleAssetName,
            false
        )

        val defaultTmmDelegateBuilder = DefaultTurboModuleManagerDelegate.Builder()

        val packageList: List<ReactPackage> = listOf(
            MainReactPackage()
        )

        val defaultReactHostDelegate = DefaultReactHostDelegate(
            jsMainModulePath = config.jsMainModulePath,
            jsBundleLoader = bundleLoader,
            reactPackages = packageList,
            jsRuntimeFactory = HermesInstance(),
            turboModuleManagerDelegateBuilder = defaultTmmDelegateBuilder,
            exceptionHandler = { exception ->
                throw exception
            }
        )

        val componentFactory = ComponentFactory()
        DefaultComponentsRegistry.register(componentFactory)

        return ReactHostImpl(
            application,
            defaultReactHostDelegate,
            componentFactory,
            config.useDeveloperSupport,
            config.useDeveloperSupport
        )
    }

    /**
     * 获取或创建 ReactHost 实例
     */
    fun getOrCreateReactHost(
        hostId: String = "default",
        bundleAssetName: String = "index.android.bundle",
        mainComponentName: String = "MediaManagerApp",
        jsMainModulePath: String = "index",
        useDevelopmentSupport: Boolean? = null,
        // V7 §3.2：热更新 bundle 路径（优先于 assets）
        bundleOverridePath: String? = null
    ): ReactHost {
        return reactHostMap.getOrPut(hostId) {
            val config = HostConfig(
                hostId = hostId,
                bundleAssetName = bundleAssetName,
                mainComponentName = mainComponentName,
                jsMainModulePath = jsMainModulePath,
                useDeveloperSupport = useDevelopmentSupport ?: BuildConfig.DEBUG,
                bundleOverridePath = bundleOverridePath
            )
            hostConfigMap[hostId] = config
            createReactHost(config).apply {
                globalListeners.forEach { listener ->
                    addReactInstanceEventListener(listener)
                }
            }
        }
    }

    fun getDefaultReactHost(): ReactHost = getOrCreateReactHost("default")
    fun getHostConfig(hostId: String): HostConfig? = hostConfigMap[hostId]
    fun hasInstance(hostId: String): Boolean = reactHostMap.containsKey(hostId)

    fun preloadReactContext(hostId: String = "default") {
        reactHostMap[hostId]?.start()
    }

    fun reloadReactContext(hostId: String = "default", reason: String = "reload") {
        reactHostMap[hostId]?.reload(reason)
    }

    fun addReactInstanceListener(listener: ReactInstanceEventListener) {
        globalListeners.add(listener)
        reactHostMap.values.forEach { host ->
            host.addReactInstanceEventListener(listener)
        }
    }

    fun removeReactInstanceListener(listener: ReactInstanceEventListener) {
        globalListeners.remove(listener)
        reactHostMap.values.forEach { host ->
            host.removeReactInstanceEventListener(listener)
        }
    }

    fun destroyReactHost(hostId: String) {
        reactHostMap[hostId]?.let { host ->
            host.destroy("destroy_$hostId", null)
            reactHostMap.remove(hostId)
            hostConfigMap.remove(hostId)
        }
    }

    fun destroyAll() {
        reactHostMap.forEach { (hostId, host) ->
            host.destroy("destroy_all_$hostId", null)
        }
        reactHostMap.clear()
        hostConfigMap.clear()
    }

    fun getCurrentReactContext(hostId: String = "default"): ReactContext? {
        return reactHostMap[hostId]?.currentReactContext
    }

    fun getAllHostIds(): Set<String> = reactHostMap.keys
}
