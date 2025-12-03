# FlatBuffers Host Binaries

此目录用于缓存宿主机可执行的 `flatc` 编译器。`app/src/main/cpp` 的 CMake 构建会优先在这里查找 `flatc`，避免在交叉编译流程中尝试为 Android 目标构建/运行 `flatc`。

> **不要**手动把下载得到的二进制加入版本控制，它们默认被 `.gitignore` 忽略。

## 如何准备 `flatc`

Windows / macOS / Linux (x86_64) 主机可直接运行：

```powershell
pwsh ./tools/setup-flatc.ps1
```

脚本会从 GitHub Release (`v24.3.25`) 下载官方编译好的 `flatc`，并放置到：

```
external/flatbuffers-prebuilt/<platform>/flatc(.exe)
```

如果你已经在系统路径中安装了 `flatc`，或者需要自定义安装位置，可设置环境变量 `FLATC_HOST_PATH` 覆盖脚本/默认搜索路径。
