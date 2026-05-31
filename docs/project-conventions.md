# TinaIDE 项目开发规范

> 本文档供所有开发者和 AI 工具参考。精简版关键约束，详细设计文档见 `docs/` 目录。

---

## 1. 项目结构

```
app/                    ← 应用壳（Activity 启动、DI 装配、ProGuard 全局规则）
build-logic/            ← Gradle Convention Plugins
core/                   ← 基础模块（common, config, i18n, model, network, lsp, git, plugin, ...）
feature/                ← 功能模块（editor, terminal, settings, ai, ...）
external/               ← 第三方 submodule / 本地依赖（termux-proot, tina-android-tree-sitter, ...）
server/                 ← 公开占位说明；私有后端不随开源仓库分发
docs/                   ← 设计文档
```

**模块依赖方向**：`feature → core → common`，禁止反向依赖。

---

## 2. 编码约束

### 2.1 语言与编码
- Kotlin 为主，Java 仅限第三方兼容
- 文件编码：UTF-8（无 BOM）
- 默认输出语言：简体中文

### 2.2 Android 国际化（强制）
用户可见文本**必须**走 `values/strings.xml` + `values-en/strings.xml`，禁止硬编码。

```kotlin
// 正确
Strings.some_text.str()
Strings.export_failed.strOr(context, errorMessage)

// 错误
"导出失败：$message"
```

入口文件：`core/i18n/` 下的 `AppStrings.kt`、`ResExt.kt`、`TextResourceAliases.kt`；
app 层 drawable 别名在 `AppResourceAliases.kt`。

### 2.3 DI 模式
使用 **Koin**（编译时解析）：
- Activity/Plain 类：`KoinComponent` + `by inject()`
- Composable：`koinInject()`
- 无 KoinComponent 的普通类：`GlobalContext.getOrNull()?.getOrNull<T>()`

---

## 3. ProGuard/R8 混淆规则（关键）

Release 构建启用 `isMinifyEnabled = true`。**新增第三方库必须评估混淆风险。**

### 3.1 规则存放原则
- **哪个模块引入依赖，就在那个模块的 `consumer-rules.pro` 写规则**
- Convention Plugin 已自动注册 `consumerProguardFiles("consumer-rules.pro")`
- `app/proguard-rules.pro` 仅放全局基础规则 + 无 consumer-rules 的第三方 SDK

### 3.2 判断是否需要规则

| 库的特征 | 需要的规则 | 示例 |
|----------|------------|------|
| Gson/反射序列化 | `-keepclassmembers { <fields>; }` | lsp4j |
| `Class.forName()` 动态加载 | `-keep class` | msgpack |
| JNI native 方法 | 全局规则已覆盖，通常无需 | zstd-jni |
| 动态代理接口 | `-keep interface` | lsp4j services |
| 自带 consumer-rules | 无需额外处理 | OkHttp, Koin, Coil |

### 3.3 常见崩溃模式

| 症状 | 根因 |
|------|------|
| `ClassCastException`（无消息） | 字段名被重命名，Gson 反序列化类型错误 |
| `ClassNotFoundException` | `Class.forName()` 引用的类被 R8 删除或重命名 |
| `NoSuchFieldError` | JNI 硬编码字段名被混淆 |

**详细参考**：`docs/proguard-rules-reference.md`

---

## 4. Git 子模块规则

本项目包含 Git submodule。提交顺序：
1. **先**在子模块仓库内提交并 `git push`
2. **再**回主仓库提交子模块指针变更并 `git push`
3. 打 tag 前校验：`git submodule status --recursive`

---

## 5. 构建验证

```bash
# 单模块快速编译
./gradlew :core:xxx:assembleDebug --console=plain

# App 全量编译（arm64）
./gradlew :app:compileArm64DebugKotlin --console=plain

# Release 构建
./gradlew :app:assembleArm64Release --console=plain

# 检查混淆 mapping
grep "^com.example" app/build/outputs/mapping/arm64Release/mapping.txt
```

---

## 6. 核心设计原则

- **KISS**：能用小改动解决就不引入新层级
- **YAGNI**：只实现当前需要的功能
- **DRY**：新增前先搜索是否已有类似实现
- **SOLID**：单一职责、开放封闭、依赖倒置
