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
        writeExecutableIfDifferent(repairScript, buildRepairScript(prefix));

        // 2) Ensure apt Post-Invoke hook
        final File aptConfDir = new File(prefixDir, "etc/apt/apt.conf.d");
        //noinspection ResultOfMethodCallIgnored
        aptConfDir.mkdirs();
        final File aptHook = new File(aptConfDir, "99-prefix-repair");
        String hook = "DPkg::Post-Invoke-Success { \"sh -c '$PREFIX/bin/prefix-repair || true'\"; };\n";
        writeTextIfDifferent(aptHook, hook, false);

        // 3) Ensure profile.d snippet for termux-exec
        final File profileDir = new File(prefixDir, "etc/profile.d");
        //noinspection ResultOfMethodCallIgnored
        profileDir.mkdirs();
        final File termuxExecSh = new File(profileDir, "termux-exec.sh");
        String profile = "# Enable termux-exec if lib is available\n" +
                "if [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n" +
                "  export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so${LD_PRELOAD:+:$LD_PRELOAD}\"\n" +
                "fi\n";
        writeTextIfDifferent(termuxExecSh, profile, false);

        // 4) Optionally copy libtermux-exec.so from assets if present
        final File libDir = new File(prefixDir, "lib");
        //noinspection ResultOfMethodCallIgnored
        libDir.mkdirs();
        maybeCopyTermuxExecFromAssets(context, new File(libDir, "libtermux-exec.so"));
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

    private static String buildRepairScript(String prefix) {
        // NOTE: Script is idempotent and only touches text files/symlinks.
        return "#!/usr/bin/env bash\n" +
                "set -Eeuo pipefail\n" +
                "\n" +
                ": \"${PREFIX:?PREFIX is not set. Run inside Termux shell.}\"\n" +
                "log() { printf '[prefix-repair] %s\\n' \"$*\" >&2; }\n" +
                "\n" +
                "log 'Fixing shebang paths...'\n" +
                "grep -RIl '^#!/data/data/com\\.termux/files/usr/bin/' \"$PREFIX\" 2>/dev/null \\\n" +
                "| while IFS= read -r f; do\n" +
                "  sed -i \"1s|^#!/data/data/com.termux/files/usr/bin/|#!$PREFIX/bin/|\" \"$f\"\n" +
                "done\n" +
                "\n" +
                "log 'Rewriting textual references to PREFIX...'\n" +
                "grep -RIl '/data/data/com\\.termux/files/usr' \"$PREFIX\" 2>/dev/null \\\n" +
                "| xargs -r sed -i \"s|/data/data/com.termux/files/usr|$PREFIX|g\"\n" +
                "\n" +
                "log 'Repointing symlinks...'\n" +
                "find \"$PREFIX\" -type l -print0 2>/dev/null \\\n" +
                "| while IFS= read -r -d '' L; do\n" +
                "  T=$(readlink \"$L\") || continue\n" +
                "  case \"$T\" in\n" +
                "    /data/data/com.termux/files/usr/*)\n" +
                "      NEW=\"$PREFIX${T#/data/data/com.termux/files/usr}\"\n" +
                "      ln -sfn \"$NEW\" \"$L\"\n" +
                "    ;;\n" +
                "  esac\n" +
                "done\n" +
                "\n" +
                "if [ -d \"$PREFIX/lib/pkgconfig\" ]; then\n" +
                "  find \"$PREFIX/lib/pkgconfig\" -type f -name '*.pc' -print0 2>/dev/null \\\n" +
                "  | while IFS= read -r -d '' pc; do\n" +
                "      if ! grep -q \"^prefix=$PREFIX$\" \"$pc\"; then\n" +
                "        sed -i \"s|^prefix=.*$|prefix=$PREFIX|\" \"$pc\"\n" +
                "      fi\n" +
                "    done\n" +
                "fi\n";
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
}
