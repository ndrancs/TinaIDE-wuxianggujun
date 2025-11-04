package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal, streaming-based bootstrap installer to avoid OOM.
 * This replaces loading the whole bootstrap zip into memory.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        final File prefix = new File(activity.getFilesDir(), "usr");
        final File binSh = new File(prefix, "bin/sh");
        if (binSh.exists()) {
            if (whenDone != null) activity.runOnUiThread(whenDone);
            return;
        }

        new Thread(() -> {
            Exception error = null;
            try (ZipInputStream zipInput = openBootstrapZipStream(activity)) {
                // Ensure base dirs
                ensureDir(prefix);
                final byte[] buffer = new byte[64 * 1024];
                final List<String[]> symlinks = new ArrayList<>();
                ZipEntry entry;
                while ((entry = zipInput.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.equals("SYMLINKS.txt")) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(zipInput));
                        String line;
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            int idx = -1; boolean leftArrow = false; String arrowStr = null;
                            int tmp;
                            if ((tmp = line.indexOf("←")) >= 0) { idx = tmp; leftArrow = true; arrowStr = "←"; }
                            else if ((tmp = line.indexOf("->")) >= 0) { idx = tmp; arrowStr = "->"; }
                            else if ((tmp = line.indexOf("→")) >= 0) { idx = tmp; arrowStr = "→"; }
                            if (idx < 0) continue;
                            String left = line.substring(0, idx).trim();
                            String right = line.substring(idx + arrowStr.length()).trim();
                            String target; String link;
                            if (leftArrow) { target = left; link = right; }
                            else { link = left; target = right; }
                            if (!target.isEmpty() && !link.isEmpty()) symlinks.add(new String[]{target, link});
                        }
                        continue;
                    }

                    File out = new File(prefix, name);
                    if (entry.isDirectory()) {
                        ensureDir(out);
                    } else {
                        ensureDir(out.getParentFile());
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            int r;
                            while ((r = zipInput.read(buffer)) != -1) fos.write(buffer, 0, r);
                        }
                        // Exec bits for common locations
                        if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")) {
                            try { Os.chmod(out.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                        }
                    }
                }
                // Create symlinks
                for (String[] pair : symlinks) {
                    String targetSpec = pair[0];
                    String linkSpec = pair[1];
                    if (linkSpec.startsWith("./")) linkSpec = linkSpec.substring(2);
                    File link = linkSpec.startsWith("/") ? new File(linkSpec) : new File(prefix, linkSpec);
                    ensureDir(link.getParentFile());
                    File target;
                    if (targetSpec.startsWith("/")) {
                        target = new File(targetSpec);
                    } else {
                        target = new File(link.getParentFile(), targetSpec);
                    }
                    try {
                        if (link.exists()) link.delete();
                        Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Bootstrap install failed", e);
                error = e;
            } finally {
                if (whenDone != null) activity.runOnUiThread(whenDone);
                if (error != null) Log.e(LOG_TAG, "Bootstrap finished with error: " + error.getMessage());
            }
        }).start();
    }

    static void setupStorageSymlinks(final Context context) {
        // no-op here; original Termux app provides a richer implementation.
    }

    private static void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();
    }

    /**
     * Open bootstrap zip as a streaming ZipInputStream to avoid loading whole archive in memory.
     * Preference order: assets → native fallback.
     */
    private static ZipInputStream openBootstrapZipStream(Context context) {
        if (context != null) {
            try {
                String arch = detectArch();
                String[] candidates = new String[] {
                    "bootstrap/" + arch + "/bootstrap-" + arch + ".zip",
                    "bootstrap/" + arch + ".zip",
                    "bootstrap-" + arch + ".zip"
                };
                for (String path : candidates) {
                    try {
                        InputStream ins = context.getAssets().open(path);
                        return new ZipInputStream(ins);
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }
        try {
            System.loadLibrary("termux-bootstrap");
            byte[] data = getZip();
            if (data == null || data.length == 0)
                throw new RuntimeException("Empty bootstrap from native library");
            return new ZipInputStream(new ByteArrayInputStream(data));
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("No bootstrap zip available in assets or native library", e);
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

    public static native byte[] getZip();
}
