package com.wgt.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Manager 注解处理器
 * 扫描 @ManagerProvider 注解并自动生成：
 * 1. expect/actual Provider（多平台）
 * 2. 注册函数 initXxxManager()
 * 3. Application 扩展函数（如果需要）
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

        managers.forEach { manager ->
            try {
                generateExpectProvider(manager)
                generateActualProvider(manager)
                generateRegistrationFunction(manager)
                logger.info("生成完成: ${manager.simpleName}")
            } catch (e: Exception) {
                logger.error("生成 ${manager.simpleName} 代码时出错: ${e.message}")
            }
        }
    }

    private fun processManagerClass(classDecl: KSClassDeclaration) {
        // 验证类是否实现 IManager 接口
        val isValidManager = classDecl.superTypes.any { superType ->
            val resolvedType = superType.resolve()
            val qualifiedName = resolvedType.declaration.qualifiedName?.asString()
            qualifiedName?.contains("IManager") == true ||
            qualifiedName == "com.wgt.architecture.manager.IManager"
        }

        if (!isValidManager) {
            logger.warn(
                "${classDecl.simpleName.asString()} 可能未直接实现 IManager，请确保它继承自 IManager 或其子接口",
                classDecl
            )
        }

        // 获取注解参数
        val annotation = classDecl.annotations.first {
            it.shortName.asString() == "ManagerProvider"
        }

        val interfaceClass = annotation.arguments
            .find { it.name?.asString() == "interfaceClass" }
            ?.value as? String
            ?: inferInterfaceClass(classDecl)

        val initFunctionName = annotation.arguments
            .find { it.name?.asString() == "initFunctionName" }
            ?.value as? String
            ?: "init${classDecl.simpleName.asString()}"

        val providerName = annotation.arguments
            .find { it.name?.asString() == "providerName" }
            ?.value as? String
            ?: "${classDecl.simpleName.asString()}Provider"

        val lifecycleArg = annotation.arguments
            .find { it.name?.asString() == "lifecycle" }
            ?.value

        val lifecycle = when (lifecycleArg) {
            is KSClassDeclaration -> lifecycleArg.simpleName.asString()
            is String -> lifecycleArg
            else -> "SINGLETON"
        }

        val requiresApplication = annotation.arguments
            .find { it.name?.asString() == "requiresApplication" }
            ?.value as? Boolean
            ?: true

        val managerInfo = ManagerInfo(
            className = classDecl.qualifiedName?.asString() ?: return,
            simpleName = classDecl.simpleName.asString(),
            interfaceClass = interfaceClass,
            initFunctionName = initFunctionName,
            providerName = providerName,
            lifecycle = lifecycle,
            packageName = classDecl.packageName.asString(),
            requiresApplication = requiresApplication
        )

        managers.add(managerInfo)

        logger.info("处理 Manager: ${managerInfo.simpleName} -> ${managerInfo.interfaceClass}")
    }

    private fun inferInterfaceClass(classDecl: KSClassDeclaration): String {
        // 从 superTypes 中找到包含 IManager 的接口
        val superInterface = classDecl.superTypes
            .map { it.resolve().declaration.qualifiedName?.asString() ?: "" }
            .firstOrNull { it.startsWith("I") && it != "com.wgt.architecture.manager.IManager" }
        
        return superInterface ?: run {
            // 默认推断：类名去掉 Manager，加 I 前缀
            val simpleName = classDecl.simpleName.asString()
            if (simpleName.endsWith("Manager")) {
                "I${simpleName}"
            } else {
                "I${simpleName}"
            }
        }
    }

    /**
     * 生成 expect Provider（commonMain）
     */
    private fun generateExpectProvider(manager: ManagerInfo) {
        val fileSpec = FileSpec.builder(
            packageName = manager.packageName,
            fileName = "${manager.providerName}.expect"
        ).apply {
            addFileComment("Auto-generated by KSP. Do not modify.")
            addFileComment("Generated at: ${System.currentTimeMillis()}")
            
            val interfaceType = ClassName.bestGuess(manager.interfaceClass)
            
            // expect object Provider
            val expectObject = TypeSpec.objectBuilder(manager.providerName)
                .addModifiers(KModifier.EXPECT)
                .addKdoc(
                    """
                    Auto-generated expect Provider for ${manager.simpleName}
                    
                    Platform-specific implementation will be in:
                    - androidMain: ${manager.providerName}.android.kt
                    - iosMain: ${manager.providerName}.ios.kt
                    """.trimIndent()
                )
                .apply {
                    if (manager.requiresApplication) {
                        addFunction(
                            FunSpec.builder("initialize")
                                .addModifiers(KModifier.EXPECT)
                                .addParameter("app", ClassName("android.app", "Application"))
                                .build()
                        )
                    }
                    addFunction(
                        FunSpec.builder("getManager")
                            .addModifiers(KModifier.EXPECT)
                            .returns(interfaceType)
                            .build()
                    )
                }
                .build()
            
            addType(expectObject)
            
        }.build()

        // 写入 commonMain
        fileSpec.writeTo(codeGenerator, Dependencies(false))
    }

    /**
     * 生成 actual Provider（androidMain）
     */
    private fun generateActualProvider(manager: ManagerInfo) {
        val fileSpec = FileSpec.builder(
            packageName = manager.packageName,
            fileName = "${manager.providerName}.actual"
        ).apply {
            addFileComment("Auto-generated by KSP. Do not modify.")
            addFileComment("Generated at: ${System.currentTimeMillis()}")
            
            val interfaceType = ClassName.bestGuess(manager.interfaceClass)
            val managerClass = ClassName.bestGuess(manager.className)
            val applicationType = ClassName("android.app", "Application")
            
            // actual object Provider
            val actualObject = TypeSpec.objectBuilder(manager.providerName)
                .addModifiers(KModifier.ACTUAL)
                .addKdoc(
                    """
                    Auto-generated actual Provider for ${manager.simpleName}
                    
                    Android platform implementation
                    """.trimIndent()
                )
                .apply {
                    if (manager.requiresApplication) {
                        addProperty(
                            PropertySpec.builder(
                                "application",
                                applicationType.copy(nullable = true),
                                KModifier.PRIVATE
                            )
                                .mutable()
                                .initializer("null")
                                .build()
                        )
                        
                        addFunction(
                            FunSpec.builder("initialize")
                                .addModifiers(KModifier.ACTUAL)
                                .addParameter("app", applicationType)
                                .addStatement("this.application = app")
                                .build()
                        )
                        
                        addFunction(
                            FunSpec.builder("getManager")
                                .addModifiers(KModifier.ACTUAL)
                                .returns(interfaceType)
                                .addStatement(
                                    "return application?.let { %T.getInstance(it) } ?: throw IllegalStateException(\"%L not initialized\")",
                                    managerClass,
                                    manager.providerName
                                )
                                .build()
                        )
                    } else {
                        addFunction(
                            FunSpec.builder("getManager")
                                .addModifiers(KModifier.ACTUAL)
                                .returns(interfaceType)
                                .addStatement("return %T.getInstance()", managerClass)
                                .build()
                        )
                    }
                }
                .build()
            
            addType(actualObject)
            addImport(manager.packageName, manager.simpleName)
            
        }.build()

        // 写入 generated 目录
        fileSpec.writeTo(codeGenerator, Dependencies(false))
    }

    /**
     * 生成 iOS actual Provider（iosMain - 占位实现）
     */
    private fun generateIosActualProvider(manager: ManagerInfo) {
        val fileSpec = FileSpec.builder(
            packageName = manager.packageName,
            fileName = "${manager.providerName}.ios"
        ).apply {
            addFileComment("Auto-generated by KSP. Do not modify.")
            addFileComment("iOS placeholder implementation")
            
            val interfaceType = ClassName.bestGuess(manager.interfaceClass)
            
            // actual object Provider (placeholder)
            val actualObject = TypeSpec.objectBuilder(manager.providerName)
                .addModifiers(KModifier.ACTUAL)
                .addKdoc(
                    """
                    Auto-generated iOS placeholder for ${manager.simpleName}
                    
                    TODO: Implement iOS-specific Manager
                    """.trimIndent()
                )
                .apply {
                    if (manager.requiresApplication) {
                        addFunction(
                            FunSpec.builder("initialize")
                                .addModifiers(KModifier.ACTUAL)
                                .addParameter("app", ClassName("platform.UIKit", "UIApplication"))
                                .addStatement("// TODO: Implement iOS initialization")
                                .build()
                        )
                    }
                    addFunction(
                        FunSpec.builder("getManager")
                            .addModifiers(KModifier.ACTUAL)
                            .returns(interfaceType)
                            .addStatement(
                                "throw NotImplementedError(\"iOS implementation of %L not yet available\")",
                                manager.simpleName
                            )
                            .build()
                    )
                }
                .build()
            
            addType(actualObject)
            
        }.build()

        // 写入 generated 目录
        fileSpec.writeTo(codeGenerator, Dependencies(false))
    }

    /**
     * 生成注册函数
     */
    private fun generateRegistrationFunction(manager: ManagerInfo) {
        val fileSpec = FileSpec.builder(
            packageName = manager.packageName,
            fileName = "${manager.initFunctionName}.generated"
        ).apply {
            addFileComment("Auto-generated by KSP. Do not modify.")
            addFileComment("Generated at: ${System.currentTimeMillis()}")
            
            val interfaceType = ClassName.bestGuess(manager.interfaceClass)
            val providerClass = ClassName(manager.packageName, manager.providerName)
            
            val funSpec = FunSpec.builder(manager.initFunctionName)
                .addKdoc(
                    """
                    Auto-generated initialization function for ${manager.simpleName}.
                    
                    Register ${manager.simpleName} to the global Manager system.
                    
                    Usage:
                    ```kotlin
                    fun InitManager() {
                        ${manager.initFunctionName}()
                    }
                    ```
                    """.trimIndent()
                )
                .addStatement(
                    "registerManager<%T>(Lifecycle.%L) { %T.getManager() }",
                    interfaceType,
                    manager.lifecycle,
                    providerClass
                )
                .build()
            
            addFunction(funSpec)
            addImport(manager.packageName, manager.providerName)
            addImport("com.wgt.architecture.di", "Lifecycle")
            addImport("com.wgt.architecture.manager", "registerManager")
            
        }.build()

        fileSpec.writeTo(codeGenerator, Dependencies(false))
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
    val providerName: String,
    val lifecycle: String,
    val packageName: String,
    val requiresApplication: Boolean
)
