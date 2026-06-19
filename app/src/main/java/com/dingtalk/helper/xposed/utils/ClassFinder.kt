package com.dingtalk.helper.xposed.utils

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList

/**
 * 数据类：方法签名描述
 * 用于在混淆后通过方法特征匹配目标类
 */
data class MethodSignature(
    val name: String,
    val returnType: Class<*>? = null,
    val parameterTypes: List<Class<*>> = emptyList()
) {
    constructor(name: String, vararg params: Class<*>) : this(
        name = name,
        returnType = null,
        parameterTypes = params.toList()
    )

    override fun toString(): String {
        val paramStr = parameterTypes.joinToString(", ") { it.simpleName }
        return "$name($paramStr)"
    }
}

/**
 * 数据类：类查找模式
 * 支持多种匹配条件的组合
 */
data class ClassPattern(
    val packageNamePattern: String? = null,
    val methodSignatures: List<MethodSignature> = emptyList(),
    val parentClassName: String? = null,
    val requireNativeMethods: Boolean = false,
    val stringConstants: List<String> = emptyList()
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        packageNamePattern?.let { parts.add("pkg=$it") }
        if (methodSignatures.isNotEmpty()) parts.add("methods=${methodSignatures.joinToString(";")}")
        parentClassName?.let { parts.add("parent=$it") }
        if (requireNativeMethods) parts.add("native=true")
        if (stringConstants.isNotEmpty()) parts.add("strings=${stringConstants.joinToString(",")}")
        return "ClassPattern(${parts.joinToString(", ")})"
    }
}

/**
 * 动态类查找器
 * 在钉钉 R8 混淆后，通过特征方法签名、包名模式、类继承关系等策略动态定位目标类
 *
 * 策略优先级：
 * 1. 方法签名匹配（最可靠，混淆不改变方法签名）
 * 2. 特征字符串匹配（搜索常量池中的特征关键词）
 * 3. 包名模式匹配（部分混淆会保留包名结构）
 * 4. JNI 方法特征匹配
 */
object ClassFinder {

    private const val TAG = "DingTalkHelper:ClassFinder"

    /** 已扫描的类列表缓存（按 ClassLoader 隔离） */
    private val scannedClassesCache = ConcurrentHashMap<ClassLoader, List<Class<*>>>()

    /** 模式查找结果缓存 */
    private val patternCache = ConcurrentHashMap<String, List<Class<*>>>()

    /**
     * 通过模式查找匹配的类（主要入口）
     * 按优先级依次尝试多种策略，直到找到结果
     */
    fun findClassesByPatterns(
        classLoader: ClassLoader,
        patterns: List<ClassPattern>
    ): List<Class<*>> {
        val allResults = mutableListOf<Class<*>>()

        for (pattern in patterns) {
            val cacheKey = "${classLoader.hashCode()}:${pattern}"
            val cached = patternCache[cacheKey]
            if (cached != null && cached.isNotEmpty()) {
                allResults.addAll(cached)
                HookUtils.logDebug("$TAG: 缓存命中 pattern=$pattern -> ${cached.size} 个类")
                continue
            }

            val found = findClassesByPattern(classLoader, pattern)
            patternCache[cacheKey] = found
            allResults.addAll(found)

            if (found.isNotEmpty()) {
                HookUtils.log("$TAG: pattern=$pattern -> 找到 ${found.size} 个类: ${found.joinToString { it.name }}")
            } else {
                HookUtils.logDebug("$TAG: pattern=$pattern -> 未找到匹配类")
            }
        }

        return allResults.distinctBy { it.name }
    }

    /**
     * 通过单个模式查找匹配的类
     */
    private fun findClassesByPattern(
        classLoader: ClassLoader,
        pattern: ClassPattern
    ): List<Class<*>> {
        val strategies = mutableListOf<Pair<String, () -> List<Class<*>>>>()

        if (pattern.methodSignatures.isNotEmpty()) {
            strategies.add("方法签名匹配" to {
                findByMethodSignatures(classLoader, pattern.packageNamePattern, pattern.methodSignatures)
            })
        }

        if (pattern.stringConstants.isNotEmpty()) {
            strategies.add("特征字符串匹配" to {
                findByStringConstants(classLoader, pattern.packageNamePattern, pattern.stringConstants)
            })
        }

        if (pattern.packageNamePattern != null) {
            strategies.add("包名模式匹配" to {
                findByPackageName(classLoader, pattern.packageNamePattern)
            })
        }

        if (pattern.requireNativeMethods) {
            strategies.add("JNI 方法特征匹配" to {
                findByNativeMethods(classLoader, pattern.packageNamePattern)
            })
        }

        for ((name, strategy) in strategies) {
            try {
                val results = strategy()
                if (results.isNotEmpty()) {
                    HookUtils.logDebug("$TAG: 策略[$name] 成功，找到 ${results.size} 个类")
                    return results
                }
                HookUtils.logDebug("$TAG: 策略[$name] 未找到匹配类")
            } catch (e: Exception) {
                HookUtils.logDebug("$TAG: 策略[$name] 异常: ${e.message}")
            }
        }

        return emptyList()
    }

    // ==================== 策略 1: 方法签名匹配 ====================
    // 最可靠的策略：R8 混淆改变类名但保留方法签名

    private fun findByMethodSignatures(
        classLoader: ClassLoader,
        packageNamePattern: String?,
        signatures: List<MethodSignature>
    ): List<Class<*>> {
        val classes = getScannedClasses(classLoader)
        val results = mutableListOf<Class<*>>()

        for (clazz in classes) {
            // 包名过滤
            if (packageNamePattern != null && !clazz.name.startsWith(packageNamePattern)) {
                continue
            }

            // 检查是否包含所有指定的方法签名
            val hasAllMethods = signatures.all { sig ->
                try {
                    val methods = clazz.declaredMethods
                    methods.any { method ->
                        method.name == sig.name &&
                        (sig.returnType == null || method.returnType == sig.returnType) &&
                        method.parameterTypes.toList() == sig.parameterTypes
                    }
                } catch (e: Exception) {
                    false
                }
            }

            if (hasAllMethods) {
                results.add(clazz)
            }
        }

        return results
    }

    // ==================== 策略 2: 特征字符串匹配 ====================
    // 搜索常量池中的特征关键词

    private fun findByStringConstants(
        classLoader: ClassLoader,
        packageNamePattern: String?,
        constants: List<String>
    ): List<Class<*>> {
        val classes = getScannedClasses(classLoader)
        val results = mutableListOf<Class<*>>()

        for (clazz in classes) {
            // 包名过滤
            if (packageNamePattern != null && !clazz.name.startsWith(packageNamePattern)) {
                continue
            }

            try {
                // 检查类中是否包含指定的字符串常量
                val fields = clazz.declaredFields
                val hasConstant = fields.any { field ->
                    field.type == String::class.java && constants.any { constant ->
                        try {
                            field.isAccessible = true
                            val value = field.get(null) as? String
                            value?.contains(constant) == true
                        } catch (e: Exception) {
                            false
                        }
                    }
                }

                if (hasConstant) {
                    results.add(clazz)
                }
            } catch (e: Exception) {
                // 忽略无法访问的类
            }
        }

        return results
    }

    // ==================== 策略 3: 包名模式匹配 ====================

    private fun findByPackageName(
        classLoader: ClassLoader,
        packageNamePattern: String
    ): List<Class<*>> {
        val classes = getScannedClasses(classLoader)
        return classes.filter { it.name.startsWith(packageNamePattern) }
    }

    // ==================== 策略 4: JNI 方法特征匹配 ====================

    private fun findByNativeMethods(
        classLoader: ClassLoader,
        packageNamePattern: String?
    ): List<Class<*>> {
        val classes = getScannedClasses(classLoader)
        val results = mutableListOf<Class<*>>()

        for (clazz in classes) {
            // 包名过滤
            if (packageNamePattern != null && !clazz.name.startsWith(packageNamePattern)) {
                continue
            }

            try {
                val hasNativeMethod = clazz.declaredMethods.any { 
                    java.lang.reflect.Modifier.isNative(it.modifiers)
                }
                if (hasNativeMethod) {
                    results.add(clazz)
                }
            } catch (e: Exception) {
                // 忽略无法访问的类
            }
        }

        return results
    }

    // ==================== 工具方法 ====================

    /**
     * 获取已扫描的类列表（带缓存）
     * M5 修复：使用 computeIfAbsent 替代 getOrPut，保证原子性
     */
    private fun getScannedClasses(classLoader: ClassLoader): List<Class<*>> {
        return scannedClassesCache.computeIfAbsent(classLoader) {
            scanClasses(classLoader)
        }
    }

    /**
     * 扫描 ClassLoader 中的所有类
     */
    private fun scanClasses(classLoader: ClassLoader): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()
        try {
            // 尝试通过 DexFile 扫描
            val dexPath = classLoader.toString()
            if (dexPath.contains("DexPathList")) {
                val pathList = XposedHelpers.getObjectField(classLoader, "pathList")
                val dexElements = XposedHelpers.getObjectField(pathList, "dexElements") as Array<*>
                
                for (element in dexElements) {
                    val dexFile = XposedHelpers.getObjectField(element, "dexFile")
                    val entries = XposedHelpers.callMethod(dexFile, "entries") as Enumeration<*>
                    
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement() as String
                        try {
                            val clazz = XposedHelpers.findClass(entry, classLoader)
                            classes.add(clazz)
                        } catch (e: ClassNotFoundException) {
                            // 忽略无法加载的类
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HookUtils.logDebug("$TAG: 扫描类失败: ${e.message}")
        }
        
        return classes
    }
}
