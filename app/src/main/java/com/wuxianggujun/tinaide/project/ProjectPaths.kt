package com.wuxianggujun.tinaide.project

import android.content.Context
import java.io.File

object ProjectPaths {
    const val LEGACY_EXTERNAL_DEFAULT = "/storage/emulated/0/TinaIDE/Projects"

    fun defaultInternalProjectsDir(context: Context): File {
        return File(context.filesDir, "projects")
    }

    fun defaultInternalProjectsPath(context: Context): String {
        return defaultInternalProjectsDir(context).absolutePath
    }
}
