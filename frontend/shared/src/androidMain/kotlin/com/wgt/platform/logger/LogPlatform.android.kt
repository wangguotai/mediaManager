package com.wgt.platform.logger
import android.util.Log

class AndroidPlatformLogger : PlatformLogger {
    override fun verbose(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.v(tag, message, throwable)
        } else {
            Log.v(tag, message)
        }
    }

    override fun debug(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.d(tag, message, throwable)
        } else {
            Log.d(tag, message)
        }
    }

    override fun info(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
    }

    override fun warning(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}

actual val logger: PlatformLogger = AndroidPlatformLogger()
