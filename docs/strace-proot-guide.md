# 使用 strace 诊断 proot 问题的正确方法

## 问题

当前的 trace (`tina.1762484137.trace`) 是 attach 到主进程 (PID 2656) 的，捕获的是应用正常运行时的系统调用，**没有捕获到 proot 启动失败的瞬间**。

## 正确的诊断方法

### 方法 1: 直接手动测试 proot（推荐）

在你的 root shell 中执行：

```bash
# 1. 进入应用的 native 库目录
cd /data/data/com.wuxianggujun.tinaide/lib/x86_64

# 2. 设置环境变量
export PROOT_TMP_DIR="/data/data/com.wuxianggujun.tinaide/cache"

# 3. 使用 strace 直接跟踪 proot 启动
strace -f -tt -s 256 -o /sdcard/proot_direct.trace \
  ./libproot.so \
  --rootfs=/ \
  --bind=/data/data/com.wuxianggujun.tinaide:/data/data/com.termux \
  --bind=/data/data/com.wuxianggujun.tinaide/cache:/linkerconfig \
  /system/bin/sh -c "echo test"

# 4. 查看 trace 中的 ptrace 调用
grep ptrace /sdcard/proot_direct.trace

# 5. 查看所有错误
grep " = -1 " /sdcard/proot_direct.trace | head -50
```

### 方法 2: 修改代码，在 Java 层启动 strace

在 `AppShell.java` 的 `execute()` 方法中，在执行 proot 命令前插入 strace：

```java
// 检查是否有 strace
File straceFile = new File("/system/bin/strace");
if (straceFile.exists() && straceFile.canExecute()) {
    // 插入 strace 到命令最前面
    String traceFile = "/sdcard/proot_" + System.currentTimeMillis() + ".trace";

    List<String> straceCmd = new ArrayList<>();
    straceCmd.add("/system/bin/strace");
    straceCmd.add("-f");
    straceCmd.add("-tt");
    straceCmd.add("-s");
    straceCmd.add("256");
    straceCmd.add("-o");
    straceCmd.add(traceFile);

    // 添加原始命令
    straceCmd.addAll(Arrays.asList(commandArray));

    commandArray = straceCmd.toArray(new String[0]);

    Logger.logInfo(LOG_TAG, "Running with strace, output: " + traceFile);
}
```

### 方法 3: 使用 TermuxCommand 日志

从你的错误日志可以看到，proot 的 stderr 已经被捕获了：

```
proot error: ptrace(TRACEME): Permission denied
proot error: execve("/data/data/com.termux/files/usr/etc/termux/bootstrap/termux-bootstrap-second-stage.sh"): Permission denied
```

这已经告诉我们：
1. **ptrace(TRACEME) 失败** - 系统拒绝 proot 使用 ptrace
2. **execve 失败** - 路径映射没有生效（因为 ptrace 失败，proot 没有启动成功）

## 关键发现

从 logcat 已经可以确认：

```
proot error: ptrace(TRACEME): Permission denied
```

这说明：
- proot 尝试调用 `ptrace(PTRACE_TRACEME, 0, 0, 0)`
- 系统返回 **EPERM (Operation not permitted)**
- 这是 x86_64 Android 9 模拟器的安全策略限制

## 下一步

不需要再用 strace 了，问题已经明确：

**x86_64 Android 9 模拟器禁止应用使用 ptrace，proot 无法工作。**

解决方案：
1. 使用 ARM64 真机测试
2. 更换允许 ptrace 的模拟器镜像
3. 在代码中检测 proot 是否可用，如果不可用则提示用户
