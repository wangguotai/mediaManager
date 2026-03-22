/**
 * React Native 源码模块
 * 
 * 这个模块负责：
 * 1. 从 Gradle 缓存复制 RN AAR
 * 2. 解压 AAR 提取 classes.jar 和 SO 库
 * 3. 输出解压后的文件供 rn-host 直接依赖
 */

plugins {
    base
}

// RN 版本配置
val rnVersion = "0.82.1"

// 创建输出目录
val outputsDir = file("outputs")
val extractedDir = file("extracted")

// 从 Gradle 缓存复制 RN AAR
tasks.register<Copy>("copyFromGradleCache") {
    group = "rn-source"
    description = "从 Gradle 缓存复制 RN AAR"
    
    outputs.dir(outputsDir)
    
    doFirst {
        outputsDir.mkdirs()
        logger.lifecycle("📦 从 Gradle 缓存复制 RN AAR")
    }
    
    val gradleCacheDir = file("${System.getProperty("user.home")}/.gradle/caches/modules-2/files-2.1")
    
    // 复制 react-android
    val reactAarDir = gradleCacheDir.resolve("com.facebook.react/react-android/${rnVersion}")
    val reactAar = reactAarDir.walkTopDown()
        .find { it.name.endsWith("-release.aar") }
    
    if (reactAar != null && reactAar.exists()) {
        from(reactAar) {
            rename { "react-android-${rnVersion}.aar" }
        }
        logger.lifecycle("   ✅ react-android")
    }
    
    // 复制 hermes-android
    val hermesDir = gradleCacheDir.resolve("com.facebook.react/hermes-android/${rnVersion}")
    val hermesAar = hermesDir.walkTopDown()
        .find { it.name.endsWith("-release.aar") }
    
    if (hermesAar != null && hermesAar.exists()) {
        from(hermesAar) {
            rename { "hermes-android-${rnVersion}.aar" }
        }
        logger.lifecycle("   ✅ hermes-android")
    }
    
    into(outputsDir)
    
    doLast {
        logger.lifecycle("\n✅ RN AAR 复制完成")
        outputsDir.listFiles()?.forEach { file ->
            logger.lifecycle("   📦 ${file.name} (${file.length() / 1024} KB)")
        }
    }
}

// 解压 AAR
tasks.register("extractAars") {
    group = "rn-source"
    description = "解压 RN AAR 提取 classes 和 SO"
    
    dependsOn("copyFromGradleCache")
    outputs.dir(extractedDir)
    
    doLast {
        extractedDir.mkdirs()
        
        outputsDir.listFiles { file -> file.extension == "aar" }?.forEach { aarFile ->
            logger.lifecycle("📦 解压: ${aarFile.name}")
            
            val aarName = aarFile.nameWithoutExtension
            val outDir = extractedDir.resolve(aarName)
            outDir.mkdirs()
            
            // 解压 AAR
            project.copy {
                from(zipTree(aarFile))
                into(outDir)
            }
            
            // 列出解压内容
            logger.lifecycle("   📁 输出: ${outDir}")
            val classesJar = outDir.resolve("classes.jar")
            if (classesJar.exists()) {
                logger.lifecycle("   ✅ classes.jar (${classesJar.length() / 1024} KB)")
            }
            val jniDir = outDir.resolve("jni")
            if (jniDir.exists()) {
                val soCount = jniDir.walkTopDown().filter { it.extension == "so" }.count()
                logger.lifecycle("   ✅ JNI libraries: ${soCount} 个 SO 文件")
            }
        }
    }
}

// 主构建任务
tasks.register("buildReactNative") {
    group = "rn-source"
    description = "准备 React Native (复制 + 解压 AAR)"
    dependsOn("extractAars")
    
    doLast {
        logger.lifecycle("\n🎉 React Native 准备完成!")
        logger.lifecycle("📍 AAR 位置: ${outputsDir.absolutePath}")
        logger.lifecycle("📍 解压位置: ${extractedDir.absolutePath}")
        
        extractedDir.listFiles()?.forEach { dir ->
            logger.lifecycle("\n   📁 ${dir.name}/")
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val count = file.walkTopDown().filter { it.isFile }.count()
                    logger.lifecycle("      📂 ${file.name}/ (${count} files)")
                } else {
                    logger.lifecycle("      📄 ${file.name}")
                }
            }
        }
    }
}

// 清理 RN 源码
tasks.register<Delete>("cleanReactNative") {
    group = "rn-source"
    delete(file("react-native"))
    delete(file("build"))
    delete(outputsDir)
    delete(extractedDir)
}
