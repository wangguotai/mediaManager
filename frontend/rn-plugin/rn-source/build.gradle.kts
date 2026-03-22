/**
 * React Native SO 库准备模块
 * 
 * 这个模块负责：
 * 1. 从 Maven 仓库下载 React Native AAR
 * 2. 解压 AAR 提取 SO 库
 * 3. 将 SO 库输出到 jniLibs 目录供 rn-host 使用
 */

plugins {
    base
}

import java.net.URI
import java.util.zip.ZipFile

// RN 版本
val rnVersion = "0.82.1"
val cacheDir = file("build/cache")
val jniLibsDir = file("jniLibs")

// 需要先配置仓库
defaultTasks("prepareReactNative")

// 创建文件下载任务
tasks.register<Exec>("downloadReactAndroid") {
    group = "react-native"
    cacheDir.mkdirs()
    
    val aarFile = cacheDir.resolve("react-android-${rnVersion}.aar")
    outputs.file(aarFile)
    
    onlyIf { !aarFile.exists() }
    
    commandLine(
        "curl", "-L", "-o", aarFile.absolutePath,
        "https://repo1.maven.org/maven2/com/facebook/react/react-android/${rnVersion}/react-android-${rnVersion}.aar"
    )
    
    // 如果下载失败，尝试其他镜像
    isIgnoreExitValue = true
    
    doLast {
        if (executionResult.get().exitValue != 0) {
            println("⚠️  react-android 下载失败，将在构建时从 Gradle 依赖获取")
        }
    }
}

tasks.register<Exec>("downloadHermes") {
    group = "react-native"
    cacheDir.mkdirs()
    
    val aarFile = cacheDir.resolve("hermes-android-${rnVersion}.aar")
    outputs.file(aarFile)
    
    onlyIf { !aarFile.exists() }
    
    commandLine(
        "curl", "-L", "-o", aarFile.absolutePath,
        "https://repo1.maven.org/maven2/com/facebook/react/hermes-android/${rnVersion}/hermes-android-${rnVersion}.aar"
    )
    
    isIgnoreExitValue = true
}

tasks.register<Exec>("downloadYoga") {
    group = "react-native"
    cacheDir.mkdirs()
    
    val aarFile = cacheDir.resolve("yoga-${rnVersion}.aar")
    outputs.file(aarFile)
    
    onlyIf { !aarFile.exists() }
    
    commandLine(
        "curl", "-L", "-o", aarFile.absolutePath,
        "https://repo1.maven.org/maven2/com/facebook/yoga/yoga/${rnVersion}/yoga-${rnVersion}.aar"
    )
    
    isIgnoreExitValue = true
}

// 从 Gradle 缓存中提取 AAR
tasks.register("extractFromGradleCache") {
    group = "react-native"
    description = "从 Gradle 缓存中提取 RN AAR"
    
    doLast {
        val gradleCacheDir = file("${System.getProperty("user.home")}/.gradle/caches/modules-2/files-2.1")
        
        // 查找 RN 相关的 AAR
        val searchPaths = listOf(
            "com.facebook.react/react-android",
            "com.facebook.react/hermes-android", 
            "com.facebook.react/react-native-featureflags",
            "com.facebook.soloader/soloader"
        )
        
        cacheDir.mkdirs()
        
        searchPaths.forEach { path ->
            val dir = gradleCacheDir.resolve(path)
            if (dir.exists()) {
                println("🔍 搜索: $path")
                
                // 查找所有版本的 AAR
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "aar" }
                    .forEach { aarFile ->
                        val targetFile = cacheDir.resolve(aarFile.name)
                        if (!targetFile.exists()) {
                            aarFile.copyTo(targetFile)
                            println("  ✅ ${aarFile.name}")
                        }
                    }
            }
        }
    }
}

// 解压 SO 库
tasks.register("extractSoFiles") {
    group = "react-native"
    description = "提取 SO 库文件"
    
    dependsOn("downloadReactAndroid", "downloadHermes", "downloadYoga", "extractFromGradleCache")
    
    outputs.dir(jniLibsDir)
    
    doLast {
        jniLibsDir.deleteRecursively()
        jniLibsDir.mkdirs()
        
        var extractedCount = 0
        
        cacheDir.listFiles { file -> file.extension == "aar" }?.forEach { aarFile ->
            println("📦 解压: ${aarFile.name}")
            
            try {
                ZipFile(aarFile).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.endsWith(".so") }
                        .forEach { entry ->
                            val destFile = jniLibsDir.resolve(entry.name)
                            destFile.parentFile?.mkdirs()
                            
                            zip.getInputStream(entry).use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            extractedCount++
                        }
                }
            } catch (e: Exception) {
                println("   ❌ 错误: ${e.message}")
            }
        }
        
        println("\n✅ SO 库提取完成: $extractedCount 个文件")
        
        // 列出提取的文件
        jniLibsDir.walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .groupBy { it.parentFile.name }
            .forEach { (arch, files) ->
                println("\n  📁 $arch/")
                files.forEach { println("    ${it.name}") }
            }
    }
}

// 复制 SO 库到 rn-host
tasks.register<Copy>("copyJniLibsToHost") {
    group = "react-native"
    description = "复制 SO 库到 rn-host 模块"
    
    dependsOn("extractSoFiles")
    
    from(jniLibsDir)
    into(project("../rn-host").file("src/main/jniLibs"))
    
    doFirst {
        println("📋 复制 SO 库到 rn-host/src/main/jniLibs")
    }
    
    doLast {
        val destDir = project("../rn-host").file("src/main/jniLibs")
        val soCount = destDir.walkTopDown().filter { it.isFile && it.extension == "so" }.count()
        println("✅ 已复制 $soCount 个 SO 文件")
    }
}

// 主任务
tasks.register("prepareReactNative") {
    group = "react-native"
    description = "准备 React Native SO 库"
    dependsOn("copyJniLibsToHost")
    
    doLast {
        println("\n🎉 React Native SO 库准备完成!")
        println("📍 位置: rn-host/src/main/jniLibs")
    }
}

// 清理任务
tasks.register<Delete>("cleanCache") {
    group = "react-native"
    delete(cacheDir)
    delete(jniLibsDir)
    delete(project("../rn-host").file("src/main/jniLibs"))
}
