# Add project specific ProGuard rules here.

# 保留注解（Xposed 需要）
-keepattributes *Annotation*

# 保留 Xposed 模块入口和所有 Hook 类
-keep class com.dingtalk.helper.xposed.HookEntry { *; }
-keep class com.dingtalk.helper.xposed.hooks.** { *; }
-keep class com.dingtalk.helper.xposed.utils.** { *; }

# 保留 Xposed API
-keep class de.robv.android.xposed.** { *; }

# 保留数据类（ConfigManager 内部类）
-keep class com.dingtalk.helper.utils.ConfigManager$* { *; }

# 保留 ConfigEncryption（反射使用）
-keep class com.dingtalk.helper.utils.ConfigEncryption { *; }
-keep class com.dingtalk.helper.utils.ConfigEncryption$* { *; }

# 保留 Activity（反射实例化）
-keep class com.dingtalk.helper.ui.MainActivity { *; }

# 保留加密相关类
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# 移除日志（release 构建）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
