# 共享内存 POC 指南

> **更新日期**: 2025-12-06  
> **状态**: ✅ 已完成并集成到主架构

---

## 概述

共享内存传输层已成功实现并集成到 Native LSP 架构中。本文档保留作为技术参考。

## 当前实现

### 文件位置

```
app/src/main/cpp/lsp/native_client/transport/
├── shared_memory_helper.h      # 共享内存助手类
├── shared_memory_helper.cpp    # 实现（ashmem + ASharedMemory）
├── shared_memory_transport.h   # 传输层接口
└── shared_memory_transport.cpp # 传输层实现
```

### 技术要点

#### 1. 共享内存创建

```cpp
// 兼容 API 29 前后
#if __ANDROID_API__ >= 29
    // 使用 ASharedMemory
    int fd = ASharedMemory_create("lsp_transport", size);
#else
    // 使用 ashmem
    int fd = ashmem_create_region("lsp_transport", size);
#endif
```

#### 2. 阈值选择

- 小数据（< 4KB）：直接通过控制通道传输
- 大数据（>= 4KB）：使用共享内存零拷贝

#### 3. 性能提升

| 数据大小 | 传统 JNI | 共享内存 | 提升 |
|---------|---------|---------|-----|
| 1 KB    | ~500 us | ~480 us | ~4% |
| 4 KB    | ~1200 us | ~600 us | ~50% |
| 50 KB   | ~8500 us | ~1200 us | ~85% |
| 100 KB  | ~15000 us | ~2000 us | ~87% |

## 使用方式

共享内存传输已集成到 `NativeLspClient`，无需手动调用。

### 内部工作流程

```
1. Kotlin 调用 NativeLspService.requestCompletion()
2. JNI 转发到 NativeLspClient
3. NativeLspClient 通过 SharedMemoryTransport 发送请求
4. clangd 响应通过共享内存返回
5. 结果通过 JNI 返回 Kotlin
```

## 测试

### 运行性能测试

```kotlin
// 如果需要单独测试共享内存性能
import com.wuxianggujun.tinaide.core.lsp.SharedMemoryTest

lifecycleScope.launch(Dispatchers.IO) {
    val result = SharedMemoryTest.runFullBenchmark()
    result.printSummary()
}
```

### 查看日志

```
adb logcat -s SharedMemoryTransport SharedMemoryHelper
```

## 相关文档

- [LSP 集成指南](LSP-Integration.md)
- [LSP 架构重构文档](LSP-Architecture-Major-Refactor.md)
