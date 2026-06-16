package com.wuxianggujun.tinaide.plugin

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

object ZipUtils {

    fun unzipToDirectory(zipFile: File, destDir: File) {
        require(zipFile.exists()) { "Zip file not found: ${zipFile.path}" }
        destDir.mkdirs()

        val destCanonical = destDir.canonicalFile
        FileInputStream(zipFile).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    require(entryName.isNotBlank()) { "Zip entry name is blank" }
                    require(!entryName.startsWith("/")) { "Zip entry must be relative: $entryName" }

                    val outFile = File(destDir, entryName).canonicalFile
                    require(outFile.path.startsWith(destCanonical.path + File.separator)) {
                        "Zip entry escapes destination directory: $entryName"
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output ->
                            zis.copyTo(output)
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
}
