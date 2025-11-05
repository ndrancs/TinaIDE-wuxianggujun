package com.wuxianggujun.tinaide;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Ensure prefix adaptation for custom package name Termux installs.
 *
 * Responsibilities (SRP):
 * - Drop an idempotent prefix-repair script into $PREFIX/bin
 * - Install apt Post-Invoke hook to run the script after installs/upgrades
 * - Install a profile.d snippet to enable termux-exec via LD_PRELOAD when available
 * - Optionally copy libtermux-exec.so from bundled assets to $PREFIX/lib
 *
 * This class avoids heavy logic and only manages text artifacts (KISS/YAGNI).
 * 
 * Note: This is the PRIMARY and STABLE method for path adaptation.
 * It works by running a shell script after each apt operation to fix:
 * - Shebang lines in scripts
 * - Text references to paths
 * - Symbolic links
 * - pkgconfig files
 * 
 * An optional EXPERIMENTAL binary-level hook (PrefixHook) can complement this
 * by patching ELF files at write time, but the script-based approach remains
 * the primary and most reliable method.
 */
public final class PrefixAdaptationManager {

    private PrefixAdaptationManager() {}

    public static void ensure(Context context) {
        if (context == null) return;

        final String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        final File prefixDir = new File(prefix);
        if (!prefixDir.exists()) return; // Bootstrap may not be installed yet

        // 1) Ensure $PREFIX/bin/prefix-repair
        final File binDir = new File(prefixDir, "bin");
        //noinspection ResultOfMethodCallIgnored
        binDir.mkdirs();
        final File repairScript = new File(binDir, "prefix-repair");
        copyAsset(context, "bin/prefix-repair", repairScript, true);

        // 2) Ensure apt Post-Invoke hook
        final File aptConfDir = new File(prefixDir, "etc/apt/apt.conf.d");
        //noinspection ResultOfMethodCallIgnored
        aptConfDir.mkdirs();
        final File aptHook = new File(aptConfDir, "99-prefix-repair");
        String hook = "DPkg::Post-Invoke-Success { \"sh -c '$PREFIX/bin/prefix-repair || true'\"; };\n";
        writeTextIfDifferent(aptHook, hook, false);
        
        // 2.1) 设置 dpkg 选项，强制使用 PREFIX 作为根目录
        final File aptDpkgOpts = new File(aptConfDir, "97-dpkg-options");
        String dpkgOpts = "# Force dpkg to extract relative to PREFIX\n" +
                "DPkg::Options { \"--force-not-root\"; };\n" +
                "DPkg::Options { \"--force-bad-path\"; };\n" +
                "DPkg::Options { \"--log=$PREFIX/var/log/dpkg.log\"; };\n";
        writeTextIfDifferent(aptDpkgOpts, dpkgOpts, false);
        
        // 2.1) 创建符号链接来处理 dpkg 解压时的路径问题
        // dpkg 会尝试创建 ./data/data/com.termux，我们让它指向正确的位置
        final File dataDir = new File(prefixDir, "data");
        final File dataDataDir = new File(dataDir, "data");
        final File comTermuxLink = new File(dataDataDir, "com.termux");
        
        try {
            if (!dataDir.exists()) dataDir.mkdirs();
            if (!dataDataDir.exists()) dataDataDir.mkdirs();
            
            // 如果 com.termux 是目录，删除它
            if (comTermuxLink.exists() && comTermuxLink.isDirectory() && !isSymlink(comTermuxLink)) {
                deleteRecursive(comTermuxLink);
            }
            
            // 创建符号链接：$PREFIX/data/data/com.termux -> $PREFIX
            // 这样 dpkg 解压到 ./data/data/com.termux/files/usr/bin/xxx 时
            // 实际会解压到 $PREFIX/files/usr/bin/xxx
            if (!comTermuxLink.exists()) {
                try {
                    // 使用相对路径创建符号链接
                    java.nio.file.Files.createSymbolicLink(
                        comTermuxLink.toPath(),
                        java.nio.file.Paths.get("../../..")  // 从 data/data/com.termux 回到 PREFIX
                    );
                } catch (Exception e) {
                    // 如果符号链接失败，尝试使用 ln 命令
                    try {
                        Runtime.getRuntime().exec(new String[]{
                            "ln", "-sf", "../../..", comTermuxLink.getAbsolutePath()
                        }).waitFor();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
            // 符号链接创建失败不是致命错误
        }

        // 3) Create wrappers for dpkg and dpkg-deb to load path-rewrite library
        createDpkgWrapper(context, binDir, "dpkg");
        createDpkgWrapper(context, binDir, "dpkg-deb");
        
        // 3.1) Ensure profile.d snippet for termux-exec
        final File profileDir = new File(prefixDir, "etc/profile.d");
        // Cleanup any legacy path-rewrite injections that may break interactive shells
        cleanupLegacyProfileSnippets(prefixDir);
        //noinspection ResultOfMethodCallIgnored
        profileDir.mkdirs();
        final File termuxExecSh = new File(profileDir, "termux-exec.sh");
        copyAsset(context, "profile.d/termux-exec.sh", termuxExecSh, false);

        // 4) Copy path-rewrite library from app's native libs
        final File libDir = new File(prefixDir, "lib");
        //noinspection ResultOfMethodCallIgnored
        libDir.mkdirs();
        
        // Copy termux-path-rewrite.so
        maybeCopyNativeLib(context, "termux-path-rewrite", new File(libDir, "libtermux-path-rewrite.so"));
        
        // Optionally copy libtermux-exec.so from assets if present
        maybeCopyTermuxExecFromAssets(context, new File(libDir, "libtermux-exec.so"));
    }

    private static void maybeCopyNativeLib(Context context, String libName, File out) {
        try {
            // 从 app 的 native 库目录复制
            String libPath = context.getApplicationInfo().nativeLibraryDir + "/lib" + libName + ".so";
            File srcFile = new File(libPath);
            if (srcFile.exists()) {
                java.nio.file.Files.copy(srcFile.toPath(), out.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                out.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                out.setExecutable(true, false);
            }
        } catch (Exception ignored) {
            // Library not available
        }
    }
    
    private static void maybeCopyTermuxExecFromAssets(Context context, File out) {
        AssetManager am = context.getAssets();
        String arch = detectArch();
        String assetPath = "termux-exec/" + arch + "/libtermux-exec.so";
        try (InputStream in = am.open(assetPath)) {
            // Only copy if missing or sizes differ
            byte[] data = readAll(in);
            if (!out.exists() || out.length() != data.length) {
                writeBytes(out, data);
                // Make sure it is readable by shell/ld
                //noinspection ResultOfMethodCallIgnored
                out.setReadable(true, false);
            }
        } catch (IOException ignored) {
            // Asset not present; skip (YAGNI)
        }
    }

    private static String detectArch() {
        String[] abis;
        try { abis = Build.SUPPORTED_ABIS; } catch (Throwable t) { abis = new String[]{Build.CPU_ABI}; }
        for (String abi : abis) {
            if (abi == null) continue;
            String a = abi.toLowerCase();
            if (a.contains("x86_64")) return "x86_64";
            if (a.equals("x86")) return "i686";
            if (a.contains("arm64") || a.contains("aarch64")) return "aarch64";
            if (a.contains("armeabi-v7a") || a.equals("arm")) return "arm";
        }
        return "aarch64";
    }

    

    private static void writeExecutableIfDifferent(File file, String content) {
        writeTextIfDifferent(file, content, true);
    }

    private static void writeTextIfDifferent(File file, String content, boolean executable) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (file.exists() && file.length() == bytes.length) return;
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        writeBytes(file, bytes);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, false);
        if (executable) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
        }
    }

    private static void writeBytes(File file, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        } catch (IOException ignored) {
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int r;
        try (in) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }
    
    private static boolean isSymlink(File file) {
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        } catch (Exception e) {
            return false;
        }
    }
    
    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
    
    private static void createDpkgWrapper(Context context, File binDir, String progName) {
        File wrapper = new File(binDir, progName);
        File orig = new File(binDir, progName);
        File real = new File(binDir, progName + "-real");

        // If a stale wrapper exists but no real or orig exists, remove it (will retry later)
        if (wrapper.exists() && !orig.exists() && !real.exists()) {
            //noinspection ResultOfMethodCallIgnored
            wrapper.delete();
            return;
        }

        // Nothing to wrap yet
        if (!orig.exists() && !real.exists()) return;

        // First time: rename original to -real
        if (orig.exists() && !real.exists()) {
            //noinspection ResultOfMethodCallIgnored
            orig.renameTo(real);
        }

        // Now drop the wrapper script from assets
        copyAsset(context, "wrappers/" + progName, wrapper, true);
    }
    private static void cleanupLegacyProfileSnippets(File prefixDir) {
        try {
            File profileDir = new File(prefixDir, "etc/profile.d");
            File[] files = profileDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                try {
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    String s = new String(data, StandardCharsets.UTF_8);
                    if (s.contains("libtermux-path-rewrite.so")) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static void copyAsset(Context context, String assetPath, File out, boolean executable) {
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(out)) {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            //noinspection ResultOfMethodCallIgnored
            out.setReadable(true, false);
            if (executable) {
                //noinspection ResultOfMethodCallIgnored
                out.setExecutable(true, false);
            }
        } catch (IOException ignored) {}
    }
}
