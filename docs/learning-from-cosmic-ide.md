# 从 Cosmic IDE 源码中可以学到什么

> 以你现在看到的这几个核心类为例：`BaseBindingFragment`, `Prefs`, `FileUtil`, `PermissionUtils`, `ZipUtil`, `Project`, `Language`, `Templates`。
>
> 这里不讲“它们是干嘛的”，而是总结**可以直接借到自己项目里用的设计思路和编码习惯**。

---

## 1. 用基类统一 UI 模板（`BaseBindingFragment`）

### 思想：用一个“好用的基类”换掉一堆复制粘贴

- `BaseBindingFragment<T : ViewBinding>` 把 Fragment 里常见的样板逻辑统一封装：
  - 统一的 ViewBinding 初始化：重写 `getViewBinding()` 即可；
  - 统一的 `onCreateView()`：内部自动设置 `binding` 和 `binding.root`；
  - 统一的返回键处理：默认处理 Fragment 回退栈和返回桌面；
  - 统一的 Material 转场动画：通过 `applyTransitions()` 设置。
- 业务 Fragment 只需要：
  - 继承 `BaseBindingFragment<XXXBinding>`；
  - 实现 `getViewBinding()`；
  - 专心写页面逻辑。

### 可以借鉴的点

- **KISS**：子类只关心当前页面逻辑，不再到处写 Binding 模板代码。
- **DRY**：所有 Fragment 的通用逻辑（Binding、动画、返回键）在一个地方统一维护。
- **OCP**：通过 `open var isBackHandled` 和 `protected open fun applyTransitions()` 提供扩展点：
  - 普通页面用默认行为；
  - 特殊页面可以关掉默认返回逻辑 / 自定义动画。

> 实战建议：
> - 在自己的项目里也做一个 `BaseActivity` / `BaseFragment`；
> - 把统一的标题栏、状态栏、转场动画等都集中放在这里；
> - 提供简单的开关/钩子方法，让特殊页面能覆盖默认行为。

---

## 2. 用单例集中管理配置（`Prefs`）

### 思想：用一个“轻量配置中心”替代到处 `SharedPreferences`

- `object Prefs` 是一个典型的“配置读取门面”：
  - 在 `init(context)` 里初始化一次 `SharedPreferences`；
  - 对外暴露只读属性：`appTheme`, `useLigatures`, `repositories`, `geminiApiKey` 等；
  - 部分属性带有默认值和范围约束，例如：
    - `editorFontSize` 使用 `runCatching + toFloatOrNull + coerceIn` 做健壮性处理；
    - `maxTokens` 对整数做 `coerceIn(60, 2048)` 限制。

### 可以借鉴的点

- **单一职责（SRP）**：
  - 所有“用户设置 / 偏好读取”都集中在 `Prefs`；
  - UI 层不需要再自己管 key、默认值、解析逻辑。
- **KISS + DRY**：
  - 避免在各个界面里写 `getSharedPreferences().getBoolean("xxx", true)` 这类重复代码；
  - 只记一个入口：`Prefs.xxx`。
- **防御式编程**：
  - 对用户可编辑的配置，统一做容错和数值范围限制，避免因为配置脏数据导致崩溃。

> 实战建议：
> - 在自己的项目里，照这个思路做一个 `Prefs` 或 `Settings` 单例/类；
> - 所有配置 key 与默认值统一写在这里；
> - 一旦有配置要变更，只改这个地方，不需要满项目搜字符串。

---

## 3. 用工具类管理运行时目录（`FileUtil`）

### 思想：把“文件夹结构约定”收口到一个对象里

- `FileUtil` 提供一组项目数据目录约定：
  - `dataDir` 表示数据根目录；
  - `projectDir`, `classpathDir`, `pluginDir` 等派生目录统一从 `dataDir` 计算出来；
  - `init(dir)` 中统一 `mkdirs()`。

### 可以借鉴的点

- **SRP + DRY**：
  - 不在业务代码里到处出现 `File(context.filesDir, "projects")` 这类硬编码；
  - 所有“路径定义”和“目录初始化”都在 `FileUtil`；
- **集中约定**：
  - 想知道数据都放在哪，只要看一个文件，维护成本低。

> 实战建议：
> - 为你的应用数据（缓存、日志、下载、项目等）设计一个统一的目录结构；
> - 用一个 `FileManager` / `FileUtil` 集中维护这些路径和初始化逻辑。

---

## 4. 把权限判断封装成简单 API（`PermissionUtils`）

### 思想：上层只关心“有没有权限”，不必每次写整套调用

- `PermissionUtils.hasPermission(permission: String)` 封装了：
  - 从全局的 `HookManager.context` 拿 context；
  - 调用 `ContextCompat.checkSelfPermission`；
  - 返回 boolean 结果。

### 可以借鉴的点

- **KISS**：
  - 对上层暴露的是一个非常简单的函数：`hasPermission("android.permission.CAMERA")`；
- **DRY**：
  - 当项目里需要检查权限的地方很多时，封装一次、处处复用。

> 实战建议：
> - 在自己的项目里也可以抽一个 `PermissionUtils` 或者扩展函数：
>   - 如：`fun Context.hasPermission(permission: String): Boolean`；
> - 长期看会显著减少样板代码，并且便于统一埋点/日志。

---

## 5. 安全且可复用的压缩/解压工具（`ZipUtil`）

### 思想：工具函数不仅要“好用”，还要默认“安全”

- 对 `InputStream.unzip(targetDir: File)` 的实现有两个关键点：
  - 使用 `targetDir.resolve(ze.name).normalize()` 计算真实路径；
  - 检查 `resolved.startsWith(targetDir)`，防止 Zip Slip 攻击；
  - 目录和文件分别处理，自动创建父目录。
- 对 `File.compressToZip(outputStream)`：
  - 用 `walk()` 遍历文件；
  - 按相对路径创建 `ZipEntry`；
  - 封装在一个简单的扩展函数里，调用方很干净。

### 可以借鉴的点

- **默认安全**：
  - “工具函数”也要考虑安全边界（比如压缩/解压、反射、网络请求）；
  - 在一个地方写好防御逻辑，避免所有调用者都要自己实现一遍。
- **KISS + DRY**：
  - 使用扩展函数形式：`inputStream.unzip(dir)`, `file.compressToZip(out)`；
  - 让调用代码尽可能简洁、可读。

> 实战建议：
> - 自己写工具库时，把安全性考虑进去：
>   - 路径合法性校验、输入参数范围限制、异常处理等；
> - 尽量用扩展函数方式，让使用体验更贴近“自然语言”。

---

## 6. 用数据类抽象项目结构（`Project`）

### 思想：把“项目是什么”变成一个明确的领域模型

- `data class Project(val root: File, val language: Language)` 抽象了：
  - 项目的根目录；
  - 使用的语言（Java / Kotlin）。
- 派生属性：
  - `srcDir`: 根据语言返回 `src/main/java` 或 `src/main/kotlin`；
  - `buildDir`, `cacheDir`, `binDir`, `libDir` 等；
  - `args`: 通过 `cacheDir/args.txt` 读写启动参数列表。
- 动作：
  - `delete()` 里用简单规则防止误删：
    - 仅当 `root.isDirectory && root.name == name` 时才允许 `deleteRecursively()`。

### 可以借鉴的点

- **SRP + DDD（领域建模味道）**：
  - 项目不再只是一个 `File`；它有清晰的属性和行为（目录结构、删除规则、参数存储）；
- **封装实现细节**：
  - 调用者不需要关心“args.txt 放在哪”“src 目录结构怎么拼”；
  - 只需要调用 `project.srcDir`, `project.args`, `project.delete()`。

> 实战建议：
> - 在你的业务里识别类似“Project”这样的核心概念：
>   - 比如：User、Session、Document、Workspace 等；
> - 给它们定义清晰的数据类/实体类，把相关行为都聚合进去，而不是散落在各处工具函数中。

---

## 7. 用密封类表达有限集合（`Language` + `language()`）

### 思想：把“可选项是固定的一小撮”的场景用 sealed class 表达

- `sealed class Language(val extension: String)`: 抽象了“编程语言”概念；
  - `object Java : Language("java")`；
  - `object Kotlin : Language("kt")`；
  - 每种语言实现自己的 `classFileContent` 生成逻辑。
- `fun language(extension: String): Language`：
  - 把字符串扩展名映射到具体的 `Language` 实例；
  - 不支持的扩展名会抛 `IllegalArgumentException`。

### 可以借鉴的点

- **LSP + OCP**：
  - 通过 sealed class + object 子类，每种语言是一个“单例子类型”；
  - 调用者可以把 `Language` 当作统一类型使用，真正的行为由子类决定。
- **类型安全**：
  - 比“到处传字符串代表语言类型”更安全，IDE 也能做更好提示。

> 实战建议：
> - 当你遇到“状态/种类是有限并且固定”的场景时：
>   - 比如：加载状态（Loading/Success/Error）、主题（Light/Dark/System）、角色（User/Admin/Guest）；
> - 优先考虑 sealed class / enum class，而不是散落的字符串常量。

---

## 8. 用代码生成工具统一模板（`Templates`）

### 思想：把“模板字符串”升级为“安全可维护的代码生成器”

- Java 模板：
  - `MethodSpec`, `TypeSpec`, `JavaFile` 组合构造 class + main 方法；
  - 通过 `indent("\t")` 等细节控制生成代码风格。
- Kotlin 模板：
  - 使用 KotlinPoet 的 `FileSpec`, `FunSpec`, `TypeSpec`；
  - 同样提供 `body` 参数，可以灵活插入示例代码。

### 可以借鉴的点

- **KISS for 调用者**：
  - 上层只要调用 `javaClass(name, packageName, body)` 或 `kotlinClass(...)`；
  - 底层使用 javapoet/kotlinpoet 保证生成代码结构正确。
- **可维护性**：
  - 比起纯字符串拼接，更容易维护和重构。

> 实战建议：
> - 如果你的项目里有“生成代码/生成文本模板”的需求：
>   - 尝试用 builder 式 API（如 *Poet 系列*）代替纯字符串模板；
>   - 把“模板”收口到一个文件/模块里集中维护。

---

## 9. 这些代码整体体现的编程习惯

从这些类可以总结出一套你也可以模仿的习惯：

- **统一入口**：
  - 配置统一走 `Prefs`；
  - 目录统一走 `FileUtil`；
  - UI 统一走 `BaseBindingFragment` 等基类。
- **用类型表达约束**：
  - `Language` 用 sealed class 表达有限集合；
  - `Project` 用 data class 聚合项目的所有核心信息和操作。
- **默认安全和健壮**：
  - `ZipUtil` 防止 Zip Slip；
  - `Prefs` 对数值做容错和范围限制；
  - `Project.delete()` 做简单的“防误删”保护。
- **尽量减少重复**：
  - 把高频样板代码抽到基类或工具类中；
  - 上层代码更关注业务本身。

---

## 10. 下一步可以怎么继续学习

如果你想继续从这个项目里学习，可以尝试：

1. 找更多 `Base...`、`...Util`、`...Manager` 这类文件，看它们怎样抽象通用逻辑。
2. 找与 `genai`、`java-completion`、`code-navigation` 相关的模块，看它们怎么组织 feature 模块、解耦不同功能。
3. 把你自己项目中重复最多的 2～3 块逻辑抽出来，参考这里的写法重构成基类/工具类。

如果你把某个你关心的模块（比如 editor、completion）对应的文件路径发给我，我也可以继续按这种风格帮你总结“还能学些什么”，再追加进这份文档。