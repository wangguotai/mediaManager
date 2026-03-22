/**
 * React Native AAR 打包脚本
 * 
 * 这个脚本配置 rn-host 模块的 AAR 输出
 * 并自动处理 rn-android 模块对 AAR 的依赖
 */

import java.io.File

// 配置 rn-host 模块的 AAR 输出任务
project(":rn-plugin:rn-host") {
    afterEvaluate {
        // 创建复制 AAR 的任务
        tasks.register<Copy>("copyAarToOutput") {
            group = "publishing"
            description = "将生成的 AAR 复制到输出目录"
            
            val aarFile = file("build/outputs/aar/rn-host-release.aar")
            val outputDir = rootProject.file("rn-plugin/output")
            
            from(aarFile)
            into(outputDir)
            
            rename { "rn-host.aar" }
            
            doFirst {
                outputDir.mkdirs()
            }
            
            onlyIf {
                aarFile.exists()
            }
        }
        
        // 在 bundleReleaseAar 任务后执行复制
        tasks.findByName("bundleReleaseAar")?.let { bundleTask ->
            tasks.named("copyAarToOutput") {
                dependsOn(bundleTask)
            }
        }
        
        // 创建完整的发布任务
        tasks.register("publishAar") {
            group = "publishing"
            description = "构建并发布 rn-host AAR"
            dependsOn("assembleRelease", "copyAarToOutput")
        }
    }
}

// 配置 rn-android 模块依赖本地 AAR
project(":rn-plugin:rn-android") {
    afterEvaluate {
        // 定义 AAR 文件路径
        val aarFile = rootProject.file("rn-plugin/output/rn-host.aar")
        
        if (aarFile.exists()) {
            // 如果 AAR 存在，添加依赖
            dependencies {
                add("implementation", files(aarFile))
            }
            
            logger.lifecycle("[RN-Plugin] 已添加本地 AAR 依赖: ${aarFile.absolutePath}")
        } else {
            logger.warn("[RN-Plugin] 警告: 未找到 AAR 文件，请先运行 :rn-plugin:rn-host:publishAar")
            logger.warn("[RN-Plugin] AAR 路径: ${aarFile.absolutePath}")
        }
    }
}
