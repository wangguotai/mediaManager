/**
 * React Native AAR 打包脚本
 * 
 * 这个脚本配置 rn-host 模块的 AAR 输出
 * 最终的应用模块（如 composeApp）可以通过此 AAR 引入完整的 RN 功能
 */

import java.io.File

// 配置 rn-host 模块的 AAR 输出任务
project(":rn-plugin:rn-host") {
    afterEvaluate {
        // 创建复制 AAR 的任务
        tasks.register<Copy>("copyAarToOutput") {
            group = "publishing"
            description = "将生成的 AAR 复制到输出目录"
            
            // 明确声明依赖：必须在 bundleReleaseAar 之后运行
            dependsOn("bundleReleaseAar")
            
            val aarFile = layout.buildDirectory.file("outputs/aar/rn-host-release.aar").get().asFile
            val outputDir = rootProject.file("rn-plugin/output")
            
            from(aarFile)
            into(outputDir)
            
            rename { "rn-host.aar" }
            
            doFirst {
                outputDir.mkdirs()
                if (!aarFile.exists()) {
                    throw GradleException("AAR file not found: ${aarFile.absolutePath}. Please run :rn-plugin:rn-host:bundleReleaseAar first.")
                }
            }
        }
        
        // 创建完整的发布任务
        tasks.register("publishAar") {
            group = "publishing"
            description = "构建并发布 rn-host AAR"
            dependsOn("copyAarToOutput")
        }
    }
}

// 注意：rn-android 是 Library 模块，不能依赖本地 AAR 文件
// 它应该使用 compileOnly(project(":rn-plugin:rn-host")) 来编译
// 最终的应用模块需要同时依赖 rn-android 和 rn-host AAR
