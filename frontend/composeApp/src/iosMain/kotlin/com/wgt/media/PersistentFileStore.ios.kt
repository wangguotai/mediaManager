package com.wgt.media

import platform.Foundation.NSUserDefaults

actual object PersistentFileStore {
    private val defaults = NSUserDefaults.standardUserDefaults()

    actual fun read(name: String): String? {
        return defaults.stringForKey("pfs_$name")
    }

    actual fun write(name: String, content: String) {
        defaults.setObject(content, forKey = "pfs_$name")
    }
}
