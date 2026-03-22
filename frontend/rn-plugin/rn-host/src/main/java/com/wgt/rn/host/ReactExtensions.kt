package com.wgt.rn.host

import android.os.Bundle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * 将 Map 转换为 WritableMap (用于传递给 React Native)
 */
fun Map<String, Any?>.toWritableMap(): WritableMap {
    val writableMap = Arguments.createMap()
    forEach { (key, value) ->
        when (value) {
            null -> writableMap.putNull(key)
            is Boolean -> writableMap.putBoolean(key, value)
            is Int -> writableMap.putInt(key, value)
            is Double -> writableMap.putDouble(key, value)
            is String -> writableMap.putString(key, value)
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") 
                writableMap.putMap(key, (value as Map<String, Any?>).toWritableMap())
            is List<*> -> writableMap.putArray(key, value.toWritableArray())
            else -> writableMap.putString(key, value.toString())
        }
    }
    return writableMap
}

/**
 * 将 List 转换为 WritableArray (用于传递给 React Native)
 */
fun List<*>.toWritableArray(): com.facebook.react.bridge.WritableArray {
    val writableArray = Arguments.createArray()
    forEach { value ->
        when (value) {
            null -> writableArray.pushNull()
            is Boolean -> writableArray.pushBoolean(value)
            is Int -> writableArray.pushInt(value)
            is Double -> writableArray.pushDouble(value)
            is String -> writableArray.pushString(value)
            is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                writableArray.pushMap((value as Map<String, Any?>).toWritableMap())
            is List<*> -> writableArray.pushArray(value.toWritableArray())
            else -> writableArray.pushString(value.toString())
        }
    }
    return writableArray
}

/**
 * 将 Map 转换为 Bundle (用于传递给 React Native 作为 initialProps)
 */
fun Map<String, Any?>.toBundle(): Bundle {
    val bundle = Bundle()
    forEach { (key, value) ->
        when (value) {
            null -> bundle.putString(key, null)
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
            is Bundle -> bundle.putBundle(key, value)
            is ArrayList<*> -> bundle.putSerializable(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }
    return bundle
}

/**
 * 创建 WritableMap 的便捷方法
 */
fun writableMapOf(vararg pairs: Pair<String, Any?>): WritableMap {
    return mapOf(*pairs).toWritableMap()
}

/**
 * 创建 Bundle 的便捷方法
 */
fun bundleOf(vararg pairs: Pair<String, Any?>): Bundle {
    return mapOf(*pairs).toBundle()
}
