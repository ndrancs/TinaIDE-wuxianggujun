package com.wuxianggujun.tinaide.core.linuxdistro

import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import java.io.File

interface LinuxDistroArchiveExtractor {
    fun extract(
        archiveFile: File,
        targetDir: File,
        format: DistroArchiveFormat,
        ensureActive: () -> Unit = {},
        progress: (Float) -> Unit = {},
    )
}

class TarLinuxDistroArchiveExtractor : LinuxDistroArchiveExtractor {
    override fun extract(
        archiveFile: File,
        targetDir: File,
        format: DistroArchiveFormat,
        ensureActive: () -> Unit,
        progress: (Float) -> Unit,
    ) {
        require(archiveFile.isFile) { "Rootfs archive does not exist: ${archiveFile.absolutePath}" }
        targetDir.mkdirs()
        TarExtractor.extract(
            input = archiveFile.inputStream(),
            targetDir = targetDir,
            compressionType = format.compressionType(),
            symlinkPolicy = TarExtractor.SymlinkPolicy.PRESERVE_ARCHIVE_TARGETS,
            ensureActive = ensureActive,
            progress = progress,
        )
    }
}
