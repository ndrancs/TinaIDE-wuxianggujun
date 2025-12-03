# 共享内存 POC 实施指南

> **阶段**: Stage 1 - 基础设施 POC  
> **创建日期**: 2025-12-03  
> **状态**: ✅ 代码已就绪，待编译测试

---

## 🎯 POC 目标

验证共享内存传输相比传统 JNI 的性能提升，为大重构提供数据支持。

**预期结果**：
- ✅ 共享内存读写功能正常
- ✅ 性能提升 **50%+**（4KB 以上数据）
- ✅ 性能提升 **5-10倍**（100KB 以上数据）

---

## 📦 已创建的文件

### C++ 层（Native 代码）

| 文件 | 位置 | 作用 |
|------|------|------|
| `shared_memory_helper.h` | `app/src/main/cpp/lsp/native_client/transport/` | 共享内存助手类（兼容 API 29 前后）|
| `shared_memory_helper.cpp` | 同上 | 实现（ashmem + ASharedMemory） |
| `shared_memory_transport.h` | 同上 | 传输层接口（支持自动阈值切换）|
| `shared_memory_transport.cpp` | 同上 | 传输层实现（POC 简化版）|
| `shared_memory_test_jni.cpp` | `app/src/main/cpp/` | JNI 测试接口（性能对比）|

### Kotlin 层（Android 代码）

| 文件 | 位置 | 作用 |
|------|------|------|
| `SharedMemoryTest.kt` | `app/src/main/java/.../core/lsp/` | 测试工具类 |
| `SharedMemoryBenchmarkActivity.kt` | `app/src/main/java/.../ui/activity/` | 测试界面 |

### 构建配置

| 文件 | 修改内容 |
|------|---------|
| `CMakeLists.txt` | 添加共享内存模块编译配置 |

---

## 🔨 编译步骤

### 1. 同步项目

```bash
# 在 Android Studio 中
File → Sync Project with Gradle Files
```

或命令行：
```powershell
cd TinaIDE
.\gradlew build
```

### 2. 编译 Native 代码

```powershell
# 编译所有 ABI
.\gradlew :app:externalNativeBuild

# 或仅编译特定 ABI
.\gradlew :app:externalNativeBuildDebug -PabiFilters=arm64-v8a
```

### 3. 检查编译结果

查看生成的 so 文件：
```powershell
ls app\build\intermediates\cmake\debug\obj\arm64-v8a\libnative-compiler.so
```

应该看到文件大小增加（新增了共享内存代码）。

---

## 🧪 运行测试

### 方式一：在现有 Activity 中集成

在你的主 Activity（如 `MainActivity`）中添加测试按钮：

```kotlin
import com.wuxianggujun.tinaide.ui.activity.SharedMemoryBenchmarkActivity

// 在某个按钮的点击事件中
button.setOnClickListener {
    startActivity(Intent(this, SharedMemoryBenchmarkActivity::class.java))
}
```

### 方式二：直接调用测试类

在任何地方调用：

```kotlin
import com.wuxianggujun.tinaide.core.lsp.SharedMemoryTest

// 简单测试
lifecycleScope.launch(Dispatchers.IO) {
    val success = SharedMemoryTest.runSimpleTest()
    Log.i("Test", "Result: $success")
}

// 完整性能测试
lifecycleScope.launch(Dispatchers.IO) {
    val result = SharedMemoryTest.runFullBenchmark()
    result.printSummary()
}
```

### 方式三：adb shell 测试

```powershell
# 安装应用
adb install app/build/outputs/apk/debug/app-debug.apk

# 启动测试 Activity
adb shell am start -n com.wuxianggujun.tinaide/.ui.activity.SharedMemoryBenchmarkActivity

# 查看日志
adb logcat | findstr "SharedMemoryTest"
```

---

## 📊 预期测试输出

### 简单测试输出

```
I/SharedMemoryTest: 运行简单读写测试: 64 KB
I/SharedMemoryTest: 创建共享内存成功: fd=47
I/SharedMemoryTest: 写入 64 KB 耗时: 245 us
I/SharedMemoryTest: 读取 64 KB 数据成功
I/SharedMemoryTest: 数据验证成功！
```

### 性能测试输出

```
I/SharedMemoryTest: ======== 开始共享内存性能测试 ========
I/SharedMemoryTest: --- 测试数据大小: 1 KB ---
I/SharedMemoryTest: 传统 JNI: 523 us
I/SharedMemoryTest: 共享内存: 487 us
I/SharedMemoryTest: 性能提升: 6.9%

I/SharedMemoryTest: --- 测试数据大小: 4 KB ---
I/SharedMemoryTest: 传统 JNI: 1245 us
I/SharedMemoryTest: 共享内存: 612 us
I/SharedMemoryTest: 性能提升: 50.8%

I/SharedMemoryTest: --- 测试数据大小: 50 KB ---
I/SharedMemoryTest: 传统 JNI: 8523 us
I/SharedMemoryTest: 共享内存: 1234 us
I/SharedMemoryTest: 性能提升: 85.5%

I/SharedMemoryTest: ======== 测试完成 ========
I/SharedMemoryTest: 平均性能提升: 65.2%
```

---

## ✅ 验收标准

| 指标 | 目标值 | 如何验证 |
|------|--------|---------|
| **简单测试通过** | ✅ 数据验证成功 | 运行 `runSimpleTest()` |
| **4KB 数据提升** | > 40% | 查看性能测试输出 |
| **50KB 数据提升** | > 70% | 查看性能测试输出 |
| **100KB 数据提升** | > 80% | 查看性能测试输出 |
| **平均提升** | > 50% | 查看测试摘要 |

---

## 🐛 常见问题

### 1. 编译错误：找不到 `ashmem.h`

**原因**：Android API < 29 需要手动包含 ashmem 头文件

**解决**：
```cpp
// 在 shared_memory_helper.cpp 中已处理
#include <linux/ashmem.h>  // API < 29
```

### 2. 运行时崩溃：`UnsatisfiedLinkError`

**原因**：Native 库未正确加载

**解决**：
```kotlin
// 确保在 SharedMemoryTest 中正确加载
init {
    System.loadLibrary("native-compiler")
}
```

### 3. 测试结果：性能提升不明显

**可能原因**：
- 数据量太小（< 4KB）
- 测试迭代次数不足
- 设备性能过高（优化空间小）

**调试方法**：
```kotlin
// 增加数据大小和迭代次数
val data = ByteArray(100 * 1024)  // 100 KB
val times = nativeBenchmark(data, 1000)  // 1000 次迭代
```

### 4. logcat 看不到输出

**解决**：
```powershell
# 过滤特定 tag
adb logcat -s SharedMemoryTest

# 或清空后重新查看
adb logcat -c
adb logcat | findstr "SharedMemory"
```

---

## 📈 ���一步

### 如果测试成功（性能提升 > 50%）

✅ **继续推进 Stage 2**：
1. 实现 FlatBuffers 二进制协议
2. 搭建 Native LSP 客户端框架
3. 实现控制通道（Unix Domain Socket）

### 如果测试失败（性能提升 < 30%）

⚠️ **分析原因**：
1. 检查测试方法是否合理
2. 分析瓶颈在哪里（用 Profiler）
3. 考虑优化实现或调整策略

---

## 📝 性能测试报告模板

运行测试后，请填写以下报告：

```
# 共享内存 POC 测试报告

## 测试环境
- 设备: ___________
- Android 版本: ___________
- CPU: ___________
- 测试时间: ___________

## 测试结果

### 简单测试
- [ ] 通过 / [ ] 失败
- 错误信息（如有）: ___________

### 性能测试
| 数据大小 | 传统 JNI | 共享内存 | 提升 |
|---------|---------|---------|-----|
| 1 KB    | ___ us  | ___ us  | ___% |
| 4 KB    | ___ us  | ___ us  | ___% |
| 10 KB   | ___ us  | ___ us  | ___% |
| 50 KB   | ___ us  | ___ us  | ___% |
| 100 KB  | ___ us  | ___ us  | ___% |

平均提升: ___%

## 结论
- [ ] 达到预期（> 50%）
- [ ] 部分达到（30-50%）
- [ ] 未达到（< 30%）

## 建议
___________
```

---

## 🎓 技术要点说明

### 为什么使用 ashmem/ASharedMemory？

1. **零拷贝**：数据在物理内存中只存在一份
2. **跨进程**：未来可用于 clangd 独立进程通信
3. **高效**：避免 JNI 的 GetByteArrayElements 拷贝

### 阈值选择（4KB）

- 小数据（< 4KB）：共享内存创建开销 > 传输开销
- 大数据（>= 4KB）：共享内存优势明显

### 性能提升来源

```
传统 JNI:
Java byte[] → GetByteArrayElements (拷贝1)
→ JNI 临时缓冲区
→ C++ std::vector (拷贝2)
→ 处理

共享内存:
Java → mmap 映射
→ C++ 直接访问映射内存 (零拷贝)
→ 处理
```

---

**准备好了吗？运行测试，见证性能飞跃！** 🚀
