package com.wgt.platform.logger

import platform.Foundation.NSLog

class IOSPlatformLogger : PlatformLogger {
    override fun verbose(tag: String, message: String, throwable: Throwable?) {
        val logMessage = formatMessage("VERBOSE", tag, message, throwable)
        NSLog(logMessage)
    }

    override fun debug(tag: String, message: String, throwable: Throwable?) {
        val logMessage = formatMessage("DEBUG", tag, message, throwable)
        NSLog(logMessage)
    }

    override fun info(tag: String, message: String, throwable: Throwable?) {
        val logMessage = formatMessage("INFO", tag, message, throwable)
        NSLog(logMessage)
    }

    override fun warning(tag: String, message: String, throwable: Throwable?) {
        val logMessage = formatMessage("WARNING", tag, message, throwable)
        NSLog(logMessage)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        val logMessage = formatMessage("ERROR", tag, message, throwable)
        NSLog(logMessage)
    }

    private fun formatMessage(level: String, tag: String, message: String, throwable: Throwable?): String {
        val baseMessage = "[$level] $tag: $message"
        return if (throwable != null) {
            "$baseMessage\n${throwable.stackTraceToString()}"
        } else {
            baseMessage
        }
    }
}

actual val logger: PlatformLogger = IOSPlatformLogger()
