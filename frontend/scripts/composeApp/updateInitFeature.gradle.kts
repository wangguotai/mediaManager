/**
 * 更新 InitFeature.kt 文件的 Gradle 脚本
 *
 * 功能：
 * - 扫描所有 feature-* 模块的 KSP 生成目录
 * - 提取 FeatureExtensions.kt 中的 initXXXFeature 函数
 * - 更新 composeApp 模块中的 InitFeature.kt 文件
 * - 添加必要的导入语句和函数调用
 */

import java.io.File

// 数据类
data class InitFunctionInfo(
    val packageName: String,
    val functionName: String
)

// 创建更新 InitFeature.kt 的任务
tasks.register("updateInitFeature") {
    group = "ksp"
    description = "更新 InitFeature.kt 文件，添加所有 Feature 的初始化调用"

    // 禁用配置缓存，因为任务需要在执行阶段访问文件系统
    notCompatibleWithConfigurationCache("需要在执行阶段动态扫描 feature 模块目录")

    // 在配置阶段收集所有可用的 KSP 任务
    val frontendDir = project.rootProject.projectDir
    val featureModules = frontendDir.listFiles { file -> file.isDirectory && file.name.startsWith("feature-") } ?: emptyArray()

    // 收集所有存在的 KSP 任务
    val kspTasks = mutableListOf<org.gradle.api.Task>()

    featureModules.toList().forEach { moduleDir ->
        val moduleName = moduleDir.name

        // 尝试添加所有可能的 KSP 任务
        val possibleKspTasks = listOf(
            ":$moduleName:kspCommonMainKotlinMetadata",
            ":$moduleName:kspAndroid",
            ":$moduleName:kspIosArm64",
            ":$moduleName:kspIosSimulatorArm64"
        )

        possibleKspTasks.forEach { taskPath ->
            try {
                val task = project.rootProject.tasks.findByPath(taskPath)
                if (task != null) {
                    kspTasks.add(task)
                    logger.lifecycle("添加 KSP 任务依赖: $taskPath")
                }
            } catch (e: Exception) {
                logger.debug("任务不存在: $taskPath")
            }
        }
    }

    // 设置任务依赖
    dependsOn(kspTasks)

    doLast {
        println("开始执行 updateInitFeature 任务...")

        val initFeatureFile = file("src/commonMain/kotlin/com/wgt/architecture/di/generated/InitFeature.kt")

        if (!initFeatureFile.exists()) {
            println("InitFeature.kt 文件不存在: ${initFeatureFile.absolutePath}")
            return@doLast
        }

        println("InitFeature.kt 文件路径: ${initFeatureFile.absolutePath}")

        // 扫描所有模块的 KSP 生成目录
        val initFunctions = mutableListOf<InitFunctionInfo>()

        // 扫描所有 feature-* 模块
        featureModules.toList().forEach { moduleDir ->
            println("检查模块: ${moduleDir.name}")
            val kspDir = moduleDir.resolve("build/generated/ksp/metadata/commonMain/kotlin")
            println("  kspDir: $kspDir")
            println("  kspDir exists: ${kspDir.exists()}")
            println("  kspDir isDirectory: ${kspDir.isDirectory}")

            // 使用 walk() 遍历目录，即使目录不存在也会返回空序列
            val files = kspDir.walk().filter { file -> file.name == "FeatureExtensions.kt" }.toList()
            println("  找到 ${files.size} 个 FeatureExtensions.kt 文件")
            files.forEach { file ->
                println("    文件路径: $file")
                println("    文件存在: ${file.exists()}")
                extractInitFunctions(file)?.let { functions ->
                    println("    提取到 ${functions.size} 个函数")
                    initFunctions.addAll(functions)
                }
            }
        }

        if (initFunctions.isEmpty()) {
            println("没有发现任何 initXXXFeature 函数")
            return@doLast
        }

        println("发现 ${initFunctions.size} 个 initXXXFeature 函数:")
        initFunctions.forEach { println("  - ${it.packageName}.${it.functionName}") }

        // 更新 InitFeature.kt 文件
        updateInitFeatureFile(initFeatureFile, initFunctions)
    }
}

// 确保在编译前运行更新任务
tasks.findByName("compileCommonMainKotlinMetadata")?.let {
    it.dependsOn("updateInitFeature")
}

// 提取 initXXXFeature 函数信息
fun extractInitFunctions(file: File): List<InitFunctionInfo>? {
    val content = file.readText()
    val initFunctions = mutableListOf<InitFunctionInfo>()

    // 提取包名
    val packagePattern = Regex("""package\s+([\w.]+)""")
    val packageMatch = packagePattern.find(content)
    val packageName = packageMatch?.groupValues?.get(1) ?: return null

    // 提取所有 initXXXFeature 函数
    val functionPattern = Regex("""fun\s+(init\w+Feature)\s*\(""")
    functionPattern.findAll(content).forEach { match ->
        initFunctions.add(InitFunctionInfo(packageName, match.groupValues[1]))
    }

    return initFunctions
}

// 更新 InitFeature.kt 文件
fun updateInitFeatureFile(file: File, initFunctions: List<InitFunctionInfo>) {
    val content = file.readText()
    val lines = content.lines()

    // 解析现有内容
    val imports = mutableSetOf<String>()
    val functionCalls = mutableSetOf<String>()
    var inFunctionBody = false
    var braceCount = 0
    var packageLineIndex = -1
    var lastImportIndex = -1

    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()

        // 记录 package 行的位置
        if (trimmed.startsWith("package ")) {
            packageLineIndex = index
        }

        // 收集现有导入
        if (trimmed.startsWith("import ")) {
            imports.add(trimmed)
            lastImportIndex = index
        }

        // 检测函数体
        if (trimmed.startsWith("fun InitFeature()")) {
            inFunctionBody = true
            return@forEachIndexed
        }

        if (inFunctionBody) {
            // 收集现有函数调用
            if (trimmed.matches(Regex("""\w+\(\)"""))) {
                functionCalls.add(trimmed)
            }

            braceCount += line.count { it == '{' } - line.count { it == '}' }
            if (braceCount == 0 && trimmed.contains("}")) {
                inFunctionBody = false
            }
        }
    }

    // 计算需要添加的内容
    val newImports = mutableSetOf<String>()
    val newFunctionCalls = mutableListOf<String>()

    initFunctions.forEach { info ->
        val import = "import ${info.packageName}.${info.functionName}"
        if (!imports.contains(import)) {
            newImports.add(import)
        }

        val call = "    ${info.functionName}()"
        if (!functionCalls.any { it.startsWith(info.functionName) }) {
            newFunctionCalls.add(call)
        }
    }

    if (newImports.isEmpty() && newFunctionCalls.isEmpty()) {
        println("InitFeature.kt 文件已是最新，无需更新")
        return
    }

    // 构建新内容
    val newContent = buildString {
        var foundFun = false
        var addedCalls = false
        var addedImports = false

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()

            // 在 package 行后添加 import（如果没有现有 import）
            if (!addedImports && newImports.isNotEmpty() && index == packageLineIndex && lastImportIndex == -1) {
                appendLine(line)
                appendLine() // 空行
                newImports.forEach { appendLine(it) }
                addedImports = true
            }
            // 在最后一个 import 后添加新导入（如果有现有 import）
            else if (!addedImports && newImports.isNotEmpty() && index == lastImportIndex) {
                appendLine(line)
                newImports.forEach { appendLine(it) }
                addedImports = true
            }
            // 处理函数体
            else if (trimmed.startsWith("fun InitFeature()")) {
                foundFun = true
                appendLine(line)
                // 添加新的函数调用
                newFunctionCalls.forEach { appendLine(it) }
                addedCalls = true
            } else if (foundFun && !addedCalls && trimmed.startsWith("}")) {
                // 在函数结束前添加调用
                newFunctionCalls.forEach { appendLine(it) }
                appendLine(line)
                addedCalls = true
            } else {
                appendLine(line)
            }
        }
    }

    // 写入文件
    file.writeText(newContent)
    println("已更新 InitFeature.kt 文件")
    println("  添加了 ${newImports.size} 个导入")
    println("  添加了 ${newFunctionCalls.size} 个函数调用")
}
