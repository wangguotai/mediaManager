package com.wgt.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Manager 注解处理器
 * 扫描 @ManagerProvider 注解并自动生成注册函数
 * 
 * 使用方式：
 * 1. 在 commonMain 的 expect class 上添加 @ManagerProvider 注解（无需参数）
 * 2. expect class 必须实现 IManager 的子接口（如 IRnManager）
 * 3. 在 expect/actual 类中提供 companion object { fun getInstance() }
 * 4. KSP 自动生成 initXxxManager() 注册函数
 */
class ManagerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val managers = mutableListOf<ManagerInfo>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(
            "com.wgt.architecture.di.annotations.ManagerProvider"
        )

        val unableToProcess = mutableListOf<KSAnnotated>()

        symbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            try {
                processManagerClass(classDecl)
            } catch (e: Exception) {
                logger.error("处理 ${classDecl.qualifiedName?.asString()} 时出错: ${e.message}", classDecl)
                unableToProcess.add(classDecl)
            }
        }

        return unableToProcess
    }

    override fun finish() {
        if (managers.isEmpty()) {
            logger.info("没有发现任何 @ManagerProvider 注解")
            return
        }

        logger.info("发现 ${managers.size} 个 Manager，开始生成代码...")

        generateRegistrationFunctions()

        logger.info("代码生成完成")
    }

    private fun processManagerClass(classDecl: KSClassDeclaration) {
        // 只处理 expect 类（在 commonMain 中声明）
        val isExpect = classDecl.modifiers.any { it.name == "EXPECT" }
        if (!isExpect) {
            logger.info("跳过非 expect 类: ${classDecl.simpleName.asString()}")
            return
        }

        // 获取注解参数（只有 lifecycle）
        val annotation = classDecl.annotations.first {
            it.shortName.asString() == "ManagerProvider"
        }

        val lifecycleArg = annotation.arguments
            .find { it.name?.asString() == "lifecycle" }
            ?.value

        val lifecycle = when (lifecycleArg) {
            is KSClassDeclaration -> lifecycleArg.simpleName.asString()
            is String -> lifecycleArg
            else -> "SINGLETON"
        }

        // 自动推断接口类
        val interfaceClass = inferInterfaceClass(classDecl)
            ?: run {
                logger.warn(
                    "无法推断 ${classDecl.simpleName.asString()} 的接口类，将使用默认规则 I${classDecl.simpleName.asString()}",
                    classDecl
                )
                "${classDecl.packageName.asString()}.I${classDecl.simpleName.asString()}"
            }

        val managerInfo = ManagerInfo(
            className = classDecl.qualifiedName?.asString() ?: return,
            simpleName = classDecl.simpleName.asString(),
            interfaceClass = interfaceClass,
            initFunctionName = generateInitFunctionName(classDecl.simpleName.asString()),
            lifecycle = lifecycle,
            packageName = classDecl.packageName.asString()
        )

        managers.add(managerInfo)

        logger.info("处理 Manager: ${managerInfo.simpleName} -> ${managerInfo.interfaceClass}")
    }

    /**
     * 从类的超类型中推断接口类
     * 寻找直接继承自 IManager 的子接口（如 IRnManager）
     */
    private fun inferInterfaceClass(classDecl: KSClassDeclaration): String? {
        return try {
            classDecl.superTypes
                .mapNotNull { superType ->
                    try {
                        val resolved = superType.resolve()
                        val declaration = resolved.declaration
                        val qualifiedName = declaration.qualifiedName?.asString()
                        
                        // 跳过 IManager 本身，找它的子接口
                        if (qualifiedName != null && 
                            qualifiedName != "com.wgt.architecture.manager.IManager" &&
                            declaration is KSClassDeclaration &&
                            declaration.classKind == com.google.devtools.ksp.symbol.ClassKind.INTERFACE) {
                            
                            // 检查这个接口是否继承自 IManager
                            val extendsIManager = declaration.superTypes.any { parentType ->
                                try {
                                    parentType.resolve().declaration.qualifiedName?.asString() == 
                                        "com.wgt.architecture.manager.IManager"
                                } catch (e: Exception) {
                                    false
                                }
                            }
                            
                            if (extendsIManager) qualifiedName else null
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
                .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun generateInitFunctionName(className: String): String {
        // 生成 initXxxManager 格式的函数名
        return "init${className}"
    }

    private fun generateRegistrationFunctions() {
        // 按包名分组聚合所有 Manager
        val managersByPackage = managers.groupBy { it.packageName }
        
        // 为每个包生成一个文件，包含所有 init 函数
        managersByPackage.forEach { (packageName, packageManagers) ->
            val fileSpec = FileSpec.builder(
                packageName = packageName,
                fileName = "ManagerRegistrations"
            ).apply {
                addFileComment("Auto-generated by KSP. Do not modify.")
                addFileComment("Generated at: ${System.currentTimeMillis()}")
                
                // 为该包下的所有 Manager 生成初始化函数
                packageManagers.forEach { manager ->
                    val interfaceType = ClassName.bestGuess(manager.interfaceClass)
                    val managerClass = ClassName.bestGuess(manager.className)
                    
                    val funSpec = FunSpec.builder(manager.initFunctionName)
                        .addKdoc(
                            """
                            Auto-generated initialization function for ${manager.simpleName}.
                            
                            Register ${manager.simpleName} to the global Manager system.
                            """.trimIndent()
                        )
                        .addStatement(
                            "registerManager<%T>(Lifecycle.%L) { %T.getInstance() }",
                            interfaceType,
                            manager.lifecycle,
                            managerClass
                        )
                        .build()
                    
                    addFunction(funSpec)
                }
                
                addImport("com.wgt.architecture.di", "Lifecycle")
                addImport("com.wgt.architecture.manager", "registerManager")
            }.build()
            
            fileSpec.writeTo(codeGenerator, Dependencies(false))
        }
    }
}

/**
 * Manager 元数据
 */
data class ManagerInfo(
    val className: String,
    val simpleName: String,
    val interfaceClass: String,
    val initFunctionName: String,
    val lifecycle: String,
    val packageName: String
)
