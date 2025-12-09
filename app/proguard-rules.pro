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

# 保留 NativeCompiler 类及其所有 native 方法（JNI 调用）
-keep class com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler {
    # 编译相关
    public static native java.lang.String getClangVersion();
    public static native java.lang.String syntaxCheck(java.lang.String, java.lang.String, java.lang.String, boolean);
    public static native java.lang.String emitObj(java.lang.String, java.lang.String, java.lang.String, java.lang.String, boolean, java.lang.String[], java.lang.String[]);
    # 链接相关
    public static native java.lang.String linkExe(java.lang.String, java.lang.String, java.lang.String, java.lang.String, boolean);
    public static native java.lang.String linkExeMany(java.lang.String, java.lang.String[], java.lang.String, java.lang.String, boolean, java.lang.String[], java.lang.String[]);
    public static native java.lang.String linkSo(java.lang.String, java.lang.String, java.lang.String, java.lang.String, boolean);
    public static native java.lang.String linkSoMany(java.lang.String, java.lang.String[], java.lang.String, java.lang.String, boolean, java.lang.String[], java.lang.String[]);
    # 运行相关
    public static native int runShared(java.lang.String, java.lang.String);
    public static native com.wuxianggujun.tinaide.core.nativebridge.RunExecutionResult runSharedIsolated(java.lang.String, java.lang.String, int);
    # Clangd LSP 相关
    public static native java.lang.String startClangd(java.lang.String, java.lang.String[]);
    public static native void stopClangd();
    public static native boolean isClangdRunning();
    public static native int writeToClangd(byte[]);
    public static native byte[] readFromClangd(int);
    public static native byte[] readFromClangdWithTimeout(int, int);
    # 通配保留
    native <methods>;
    *;
}

# NativeLoader - 链接服务器相关 JNI 方法
-keep class com.wuxianggujun.tinaide.core.nativebridge.NativeLoader {
    public static native int forkLinkServer(java.lang.String, java.lang.String);
    public static native boolean isLinkServerRunning();
    public static native void killLinkServer();
    public static native int getLinkServerPid();
    native <methods>;
    *;
}

# 其他 nativebridge 工具类
-keep class com.wuxianggujun.tinaide.core.nativebridge.AbiResolver { *; }
-keep class com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller { *; }
-keep class com.wuxianggujun.tinaide.core.nativebridge.SysrootLibraryLoader { *; }

# RunExecutionResult - Native 代码通过 FindClass 和构造函数创建实例
-keep class com.wuxianggujun.tinaide.core.nativebridge.RunExecutionResult {
    <init>(int, java.lang.String);
    *;
}

# LSP 服务 - Native 方法和回调方法（从 C++ JNI 调用）
-keep class com.wuxianggujun.tinaide.lsp.LspService {
    # 被 native 代码回调的普通方法
    public static void handleNativeHealthEvent(java.lang.String, java.lang.String);
    public static void handleNativeDiagnostics(java.lang.String, java.util.List);
    # 所有 native 方法
    native <methods>;
    # 所有 @JvmStatic 标记的方法（包括 private native）
    private static native int nativeOnLoad();
    private static native boolean nativeInitialize(java.lang.String, java.lang.String, int);
    private static native void nativeShutdown();
    public static native boolean nativeIsInitialized();
    private static native long nativeRequestHover(java.lang.String, int, int);
    private static native long nativeRequestCompletion(java.lang.String, int, int, java.lang.String);
    private static native long nativeRequestDefinition(java.lang.String, int, int);
    private static native long nativeRequestReferences(java.lang.String, int, int, boolean);
    private static native java.lang.String nativeGetResult(long);
    private static native void nativeDidOpen(java.lang.String, java.lang.String, java.lang.String);
    private static native void nativeDidChange(java.lang.String, java.lang.String, int);
    private static native void nativeDidClose(java.lang.String);
    private static native void nativeCancelRequestInternal(long);
    private static native void nativeNotifyRequestTimeout(long);
}

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

# Tree-sitter JNI 绑定类 - 必须保留（native 代码通过字段名访问）
# 注意：treesitter 包及其子包（包括 languages）都包含 native 方法
-keep class com.wuxianggujun.tinaide.treesitter.** { *; }
-keep class com.wuxianggujun.tinaide.treesitter.languages.** { *; }

# TSNode - JNI 直接访问私有字段（context、id、treePointer）
-keepclassmembers class com.wuxianggujun.tinaide.treesitter.TSNode {
    private int[] context;
    private long id;
    private long treePointer;
}

# TSParser - JNI 访问 pointer 字段和 native 方法
-keepclassmembers class com.wuxianggujun.tinaide.treesitter.TSParser {
    private long pointer;
    native <methods>;
}

# TSTree - JNI 访问 pointer 字段和 native 方法
-keepclassmembers class com.wuxianggujun.tinaide.treesitter.TSTree {
    private long pointer;
    native <methods>;
}

# TSQuery - JNI 访问 pointer 字段和 native 方法
-keepclassmembers class com.wuxianggujun.tinaide.treesitter.TSQuery {
    private long pointer;
    native <methods>;
}

# TSQueryCursor - JNI 访问 pointer 字段和 native 方法
-keepclassmembers class com.wuxianggujun.tinaide.treesitter.TSQueryCursor {
    private long pointer;
    native <methods>;
}

# TSLanguage - JNI 访问 pointer 字段
-keepclassmembers class com.wuxianggujun.tinaide.treesitter.TSLanguage {
    private long pointer;
    native <methods>;
}

# TSLanguageCpp / TSLanguageCMake - native 方法获取语言指针
-keep class com.wuxianggujun.tinaide.treesitter.languages.TSLanguageCpp {
    public static *** getInstance();
    private static native long nativeLanguage();
}
-keep class com.wuxianggujun.tinaide.treesitter.languages.TSLanguageCMake {
    public static *** getInstance();
    private static native long nativeLanguage();
}

# TSNativeObject - 基类，防止被内联优化
-keep class com.wuxianggujun.tinaide.treesitter.TSNativeObject { *; }

# Tree-sitter 数据类 - 可能被 JNI 构造或访问
-keep class com.wuxianggujun.tinaide.treesitter.TSPoint { *; }
-keep class com.wuxianggujun.tinaide.treesitter.TSInputEdit { *; }
-keep class com.wuxianggujun.tinaide.treesitter.TSQueryCapture { *; }
-keep class com.wuxianggujun.tinaide.treesitter.TSQueryMatch { *; }
-keep class com.wuxianggujun.tinaide.treesitter.TSQueryError { *; }
-keep class com.wuxianggujun.tinaide.treesitter.TSQueryPredicateStep { *; }
-keep class com.wuxianggujun.tinaide.treesitter.TSQueryPredicateStep$Type { *; }
-keep class com.wuxianggujun.tinaide.treesitter.UTF16String { *; }

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
