import java.net.URI

/**
 * 仓库镜像配置 - Kotlin DSL 版本，兼容 Settings
 * 在 settings.gradle.kts 中通过 apply(from = "scripts/build/init.gradle.kts") 使用
 */

// 定义 Maven 配置数据类
data class MavenConfig(
    val url: String,
    val user: String? = null,
    val pwd: String? = null
)

// 仓库镜像配置类
class RepositoryImp {
    var useGlobal: Boolean = true
    var useMap: Boolean = false
    var useLog: Boolean = true
    val pluginMapMirrors: MutableList<Map<String, String>> = mutableListOf()
    val repoMapMirrors: MutableList<Map<String, String>> = mutableListOf()
    val pluginMirrors: MutableList<MavenConfig> = mutableListOf()
    val repoMirrors: MutableList<MavenConfig> = mutableListOf()

    fun applyPluginMirrors(hdl: RepositoryHandler) {
        if (!useGlobal) return
        hdl.clear()
        pluginMirrors.forEach { mir ->
            if (useLog) println("[Init] PLUGIN添加全局: ${mir.url}")
            hdl.maven {
                url = URI(mir.url)
                isAllowInsecureProtocol = mir.url.startsWith("http://")
                if (!mir.user.isNullOrEmpty() && !mir.pwd.isNullOrEmpty()) {
                    credentials {
                        username = mir.user
                        password = mir.pwd
                    }
                }
            }
        }
    }

    fun applyRepoMirrors(hdl: RepositoryHandler) {
        if (!useGlobal) return
        hdl.clear()
        repoMirrors.forEach { mir ->
            if (useLog) println("[Init] 添加全局: ${mir.url}")
            hdl.maven {
                url = URI(mir.url)
                isAllowInsecureProtocol = mir.url.startsWith("http://")
                if (!mir.user.isNullOrEmpty() && !mir.pwd.isNullOrEmpty()) {
                    credentials {
                        username = mir.user
                        password = mir.pwd
                    }
                }
            }
        }
    }
}

// 创建 Repository 实例
val repository = RepositoryImp()

// 配置插件镜像
repository.pluginMirrors.addAll(
    listOf(
        MavenConfig("https://nexus.51y5.net/nexus/repository/jitpack-maven", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/MavenProxy-Aliyun", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/MavenProxy-GoogleAndroid/", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/aliyun-maven-google", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/aliyun-maven-public", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/aliyun-maven-gradle-plugin", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/tutu-snapshots/", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/tutu-releases/", "maven_publisher", "maven_publisher")
    )
)

// 配置项目仓库镜像
repository.repoMirrors.addAll(
    listOf(
        MavenConfig("https://nexus.51y5.net/nexus/repository/MavenGroup-public"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/MavenProxy-GoogleAndroid/", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/aliyun-maven-google", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/aliyun-maven-central", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/aliyun-maven-public", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/jitpack-maven", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/tutu-snapshots/", "maven_publisher", "maven_publisher"),
        MavenConfig("https://nexus.51y5.net/nexus/repository/tutu-releases/", "maven_publisher", "maven_publisher")
    )
)

// 应用到 pluginManagement 的仓库
repository.applyPluginMirrors(settings.pluginManagement.repositories)

// 注册全局生命周期钩子
gradle.allprojects {
    beforeEvaluate {
        repository.applyPluginMirrors(buildscript.repositories)
    }
    afterEvaluate {
        repository.applyRepoMirrors(repositories)
    }
}

// 保存到 ext 以便其他地方使用
extensions.extraProperties["Repository"] = repository
