# ============================================================================
# TinaIDE ProGuard Rules
# ============================================================================
# 优化目标：减小 APK 体积，同时保护关键代码不被混淆破坏

# ============================================================================
# 基础配置
# ============================================================================

# 保留行号信息，方便崩溃日志分析
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留注解（很多库依赖注解）
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================================================
# JNI / Native 相关 - 必须保留
# ============================================================================

# 保留所有 native 方法及其所在类
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 NativeCompiler 类（JNI 调用）
-keep class com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler { *; }
-keep class com.wuxianggujun.tinaide.core.nativebridge.NativeLoader { *; }
-keep class com.wuxianggujun.tinaide.core.nativebridge.AbiResolver { *; }
-keep class com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller { *; }
-keep class com.wuxianggujun.tinaide.core.nativebridge.SysrootLibraryLoader { *; }

# ============================================================================
# Android 组件 - 必须保留
# ============================================================================

# Application 类
-keep class com.wuxianggujun.tinaide.TinaApplication { *; }

# Activity、Fragment、Service 等组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.fragment.app.Fragment

# View 构造函数（XML 布局引用）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ============================================================================
# ViewBinding - 必须保留
# ============================================================================

-keep class * implements androidx.viewbinding.ViewBinding {
    public static ** inflate(android.view.LayoutInflater);
    public static ** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static ** bind(android.view.View);
}

# ============================================================================
# Kotlin 相关
# ============================================================================

# Kotlin Metadata
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations

# Kotlin 接口默认实现（修复 R8 混淆问题）
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.**
-dontwarn kotlin.Cloneable$DefaultImpls

# 保留所有 Kotlin 接口的 $DefaultImpls 类（防止 R8 删除默认实现）
-keep class **$DefaultImpls { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin Serialization（如果使用）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ============================================================================
# Gson - JSON 序列化
# ============================================================================

# Gson 使用反射，需要保留数据类
-keepattributes Signature
-keepattributes *Annotation*

# 保留 Gson 相关类
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# 保留你的数据模型类（Gson 反射需要）
-keep class com.wuxianggujun.tinaide.model.** { *; }
-keep class com.wuxianggujun.tinaide.lsp.model.** { *; }
-keep class com.wuxianggujun.tinaide.core.config.** { *; }

# ============================================================================
# Sora Editor - 代码编辑器
# ============================================================================

# 保留 Sora Editor 核心类
-keep class io.github.rosemoe.sora.** { *; }
-keep class io.github.rosemoe.sora.widget.** { *; }
-keep class io.github.rosemoe.sora.lang.** { *; }
-keep class io.github.rosemoe.sora.text.** { *; }

# TextMate 语法支持
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.eclipse.tm4e.** { *; }

# Tree-sitter 支持
-keep class io.github.rosemoe.sora.langs.treesitter.** { *; }

# ============================================================================
# TreeView 组件
# ============================================================================

-keep class com.unnamed.b.atv.** { *; }
-keep class * extends com.unnamed.b.atv.model.TreeNode { *; }

# ============================================================================
# XXPermissions 权限库
# ============================================================================

-keep class com.hjq.permissions.** { *; }

# ============================================================================
# ImmersionBar 沉浸式状态栏
# ============================================================================

-keep class com.gyf.immersionbar.** { *; }

# ============================================================================
# AndroidX Preference
# ============================================================================

-keep class * extends androidx.preference.Preference { *; }
-keep class * extends androidx.preference.PreferenceFragmentCompat { *; }

# ============================================================================
# Crash Handler - 保留崩溃处理相关
# ============================================================================

-keep class com.wuxianggujun.tinaide.core.crash.** { *; }
-keep class com.wuxianggujun.tinaide.ui.activity.CrashActivity { *; }

# ============================================================================
# 枚举类保留
# ============================================================================

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# Parcelable / Serializable
# ============================================================================

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================================
# R8 优化配置
# ============================================================================

# 允许访问修改优化
-allowaccessmodification

# 重新打包到单一包名（减小体积）
-repackageclasses ''

# 优化次数
-optimizationpasses 5

# 不混淆泛型
-keepattributes Signature

# ============================================================================
# 调试用：取消注释以下行可查看混淆映射
# ============================================================================

# -printmapping mapping.txt
# -printseeds seeds.txt
# -printusage unused.txt

# ============================================================================
# 警告抑制
# ============================================================================

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
