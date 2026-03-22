package com.wgt.rn_android.bridge

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Media Manager 原生模块
 * 
 * 提供给 React Native 调用的原生功能
 */
class MediaManagerModule(reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "MediaManager"
        const val EVENT_MEDIA_SELECTED = "onMediaSelected"
        const val EVENT_MEDIA_UPLOADED = "onMediaUploaded"
    }

    override fun getName(): String = MODULE_NAME

    /**
     * 获取媒体列表
     */
    @ReactMethod
    fun getMediaList(promise: Promise) {
        try {
            // TODO: 实现获取媒体列表的逻辑
            val mediaList = Arguments.createArray().apply {
                // 示例数据
                pushMap(Arguments.createMap().apply {
                    putString("id", "1")
                    putString("uri", "content://media/1")
                    putString("type", "image")
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                })
            }
            promise.resolve(mediaList)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message, e)
        }
    }

    /**
     * 选择媒体
     */
    @ReactMethod
    fun selectMedia(options: ReadableMap?, promise: Promise) {
        try {
            val mediaType = options?.getString("mediaType") ?: "all"
            val maxCount = options?.getInt("maxCount") ?: 1
            
            // TODO: 实现选择媒体的逻辑
            
            promise.resolve(Arguments.createMap().apply {
                putString("result", "success")
                putString("message", "Media selection started")
            })
        } catch (e: Exception) {
            promise.reject("ERROR", e.message, e)
        }
    }

    /**
     * 上传媒体
     */
    @ReactMethod
    fun uploadMedia(mediaId: String, options: ReadableMap?, promise: Promise) {
        try {
            // TODO: 实现上传媒体的逻辑
            
            promise.resolve(Arguments.createMap().apply {
                putString("mediaId", mediaId)
                putString("status", "uploading")
                putDouble("progress", 0.0)
            })
        } catch (e: Exception) {
            promise.reject("ERROR", e.message, e)
        }
    }

    /**
     * 删除媒体
     */
    @ReactMethod
    fun deleteMedia(mediaId: String, promise: Promise) {
        try {
            // TODO: 实现删除媒体的逻辑
            
            promise.resolve(Arguments.createMap().apply {
                putString("mediaId", mediaId)
                putBoolean("success", true)
            })
        } catch (e: Exception) {
            promise.reject("ERROR", e.message, e)
        }
    }

    /**
     * 添加事件监听器（React Native 要求）
     */
    @ReactMethod
    fun addListener(eventName: String) {
        // 设置事件监听器
    }

    /**
     * 移除事件监听器（React Native 要求）
     */
    @ReactMethod
    fun removeListeners(count: Int) {
        // 移除事件监听器
    }

    /**
     * 发送事件到 React Native
     */
    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(eventName, params)
    }

    /**
     * 通知媒体已选择
     */
    fun notifyMediaSelected(mediaInfo: Map<String, Any>) {
        sendEvent(EVENT_MEDIA_SELECTED, mediaInfo.toWritableMap())
    }

    /**
     * 通知媒体已上传
     */
    fun notifyMediaUploaded(mediaId: String, success: Boolean, url: String?) {
        sendEvent(EVENT_MEDIA_UPLOADED, Arguments.createMap().apply {
            putString("mediaId", mediaId)
            putBoolean("success", success)
            putString("url", url)
        })
    }

    /**
     * 将 Map 转换为 WritableMap
     */
    private fun Map<String, Any>.toWritableMap(): WritableMap {
        val map = Arguments.createMap()
        forEach { (key, value) ->
            when (value) {
                is String -> map.putString(key, value)
                is Int -> map.putInt(key, value)
                is Double -> map.putDouble(key, value)
                is Boolean -> map.putBoolean(key, value)
                is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                    map.putMap(key, (value as Map<String, Any>).toWritableMap())
                else -> map.putString(key, value.toString())
            }
        }
        return map
    }
}
