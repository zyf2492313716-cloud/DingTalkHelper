# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# 保留 Xposed 相关类
-keep class com.dingtalk.helper.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }

# 保留数据类
-keep class com.dingtalk.helper.utils.ConfigManager$* { *; }

# 保留 Activity
-keep class com.dingtalk.helper.ui.MainActivity { *; }