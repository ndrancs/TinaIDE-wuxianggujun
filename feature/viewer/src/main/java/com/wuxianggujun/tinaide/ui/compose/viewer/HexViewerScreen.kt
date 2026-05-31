package com.wuxianggujun.tinaide.ui.compose.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 十六进制查看器状态
 */
data class HexViewerState(
    val filePath: String,
    val fileSize: Long = 0L,
    val bytesPerRow: Int = 16,
    val currentOffset: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null
)

/**
 * 十六进制行数据
 */
data class HexLine(
    val offset: Long,
    val bytes: ByteArray,
    val hexString: String,
    val asciiString: String
) {
    companion object {
        fun fromBytes(offset: Long, bytes: ByteArray): HexLine {
            val hexParts = bytes.map { "%02X".format(it) }
            val hexString = buildString {
                hexParts.forEachIndexed { index, hex ->
                    append(hex)
                    if (index == 7) append("  ")
                    else if (index < hexParts.lastIndex) append(" ")
                }
                repeat(16 - bytes.size) { i ->
                    val pos = bytes.size + i
                    append("   ")
                    if (pos == 7) append(" ")
                }
            }

            val asciiString = bytes.map { byte ->
                val c = byte.toInt() and 0xFF
                if (c in 0x20..0x7E) c.toChar() else '.'
            }.joinToString("")

            return HexLine(offset, bytes, hexString, asciiString)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexLine) return false
        return offset == other.offset && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * 十六进制查看器屏幕
 * @param filePath 文件路径
 * @param onRegisterSearch 注册搜索回调
 * @param onUnregisterSearch 注销搜索回调
 */
@Composable
fun HexViewerScreen(
    filePath: String,
    onRegisterSearch: ((search: (String) -> List<Long>, goToOffset: (Long) -> Unit) -> Unit)? = null,
    onUnregisterSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val file = remember { File(filePath) }
    val scope = rememberCoroutineScope()

    var state by remember {
        mutableStateOf(HexViewerState(filePath = filePath))
    }

    val listState = rememberLazyListState()

    // 初始化
    LaunchedEffect(filePath) {
        state = state.copy(
            fileSize = file.length(),
            isLoading = false
        )
    }
    
    // 注册搜索回调
    LaunchedEffect(state.fileSize) {
        if (state.fileSize > 0 && onRegisterSearch != null) {
            onRegisterSearch(
                // 搜索函数
                { query ->
                    searchInHexFile(file, query)
                },
                // 跳转函数
                { offset ->
                    state = state.copy(currentOffset = offset)
                    val lineIndex = (offset / state.bytesPerRow).toInt()
                    scope.launch {
                        listState.animateScrollToItem(lineIndex)
                    }
                }
            )
        }
    }
    
    // 注销搜索回调
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            onUnregisterSearch?.invoke()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 表头
        HexHeader()

        // 内容区域
        HexContent(
            file = file,
            state = state,
            listState = listState,
            modifier = Modifier.weight(1f)
        )

        // 底部工具栏
        HexFooter(
            state = state,
            onGotoOffset = { offset ->
                state = state.copy(currentOffset = offset)
                val lineIndex = (offset / state.bytesPerRow).toInt()
                scope.launch {
                    listState.animateScrollToItem(lineIndex)
                }
            }
        )
    }
}

@Composable
private fun HexHeader() {
    val headerStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(Strings.hex_header_offset),
                style = headerStyle,
                modifier = Modifier.width(80.dp)
            )

            Text(
                text = "00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F",
                style = headerStyle,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = stringResource(Strings.hex_header_ascii),
                style = headerStyle,
                modifier = Modifier.width(140.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HexContent(
    file: File,
    state: HexViewerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    val bytesPerRow = state.bytesPerRow
    val totalLines = ((state.fileSize + bytesPerRow - 1) / bytesPerRow).toInt()

    val lineCache = remember { mutableStateMapOf<Int, HexLine>() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth()
    ) {
        items(
            count = totalLines,
            key = { it }
        ) { lineIndex ->
            val hexLine = lineCache.getOrPut(lineIndex) {
                loadHexLine(file, lineIndex, bytesPerRow)
            }

            HexRow(
                line = hexLine,
                isEven = lineIndex % 2 == 0
            )
        }
    }
}

@Composable
private fun HexRow(
    line: HexLine,
    isEven: Boolean
) {
    val monoStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )

    val backgroundColor = if (isEven) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "%08X".format(line.offset),
            style = monoStyle.copy(
                color = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.width(80.dp)
        )

        Text(
            text = line.hexString,
            style = monoStyle,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = line.asciiString,
            style = monoStyle.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.width(140.dp)
        )
    }
}

@Composable
private fun HexFooter(
    state: HexViewerState,
    onGotoOffset: (Long) -> Unit
) {
    var showGotoDialog by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(Strings.hex_footer_size, formatFileSize(state.fileSize)),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = stringResource(Strings.hex_footer_offset).format(state.currentOffset),
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = { showGotoDialog = true }) {
                Text(stringResource(Strings.btn_goto))
            }
        }
    }

    if (showGotoDialog) {
        GotoOffsetDialog(
            maxOffset = state.fileSize,
            onDismiss = { showGotoDialog = false },
            onConfirm = { offset ->
                onGotoOffset(offset)
                showGotoDialog = false
            }
        )
    }
}

@Composable
private fun GotoOffsetDialog(
    maxOffset: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var offsetText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    
    val errorInvalidOffset = stringResource(Strings.error_invalid_offset)
    val errorOutOfRange = stringResource(Strings.error_offset_out_of_range, maxOffset - 1)

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.dialog_title_goto_offset)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = {
                            offsetText = it
                            error = null
                        },
                        label = { Text(stringResource(Strings.hint_offset_address)) },
                        placeholder = { Text(stringResource(Strings.hint_offset_example)) },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_goto),
                enabled = offsetText.isNotBlank(),
                onClick = {
                    val offset = parseOffset(offsetText)
                    if (offset == null) {
                        error = errorInvalidOffset
                    } else if (offset < 0 || offset >= maxOffset) {
                        error = errorOutOfRange
                    } else {
                        onConfirm(offset)
                    }
                }
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

/**
 * 解析偏移地址（支持十六进制和十进制）
 */
private fun parseOffset(text: String): Long? {
    val trimmed = text.trim()
    return try {
        when {
            trimmed.startsWith("0x", ignoreCase = true) -> 
                trimmed.substring(2).toLong(16)
            trimmed.startsWith("0X") -> 
                trimmed.substring(2).toLong(16)
            else -> trimmed.toLongOrNull()
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 从文件读取一行十六进制数据
 */
private fun loadHexLine(file: File, lineIndex: Int, bytesPerRow: Int): HexLine {
    val offset = lineIndex.toLong() * bytesPerRow
    val bytes = ByteArray(bytesPerRow)

    return try {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val bytesRead = raf.read(bytes)
            if (bytesRead <= 0) {
                HexLine.fromBytes(offset, ByteArray(0))
            } else {
                HexLine.fromBytes(offset, bytes.copyOf(bytesRead))
            }
        }
    } catch (e: Exception) {
        HexLine.fromBytes(offset, ByteArray(0))
    }
}

/**
 * 在十六进制文件中搜索
 * 支持搜索十六进制字符串（如 "7F 45 4C 46"）或 ASCII 文本
 * @return 匹配的偏移量列表
 */
private fun searchInHexFile(file: File, query: String): List<Long> {
    if (query.isEmpty() || !file.exists()) return emptyList()
    
    val offsets = mutableListOf<Long>()
    val searchBytes: ByteArray
    
    // 判断是否为十六进制搜索
    val hexPattern = query.replace(" ", "").replace("-", "")
    val isHexSearch = hexPattern.matches(Regex("^[0-9A-Fa-f]+$")) && hexPattern.length % 2 == 0
    
    searchBytes = if (isHexSearch) {
        // 解析十六进制字符串
        try {
            hexPattern.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            query.toByteArray(Charsets.UTF_8)
        }
    } else {
        // ASCII 文本搜索
        query.toByteArray(Charsets.UTF_8)
    }
    
    if (searchBytes.isEmpty()) return emptyList()
    
    // 读取文件并搜索
    try {
        RandomAccessFile(file, "r").use { raf ->
            val bufferSize = 64 * 1024  // 64KB 缓冲区
            val buffer = ByteArray(bufferSize + searchBytes.size - 1)
            var fileOffset = 0L
            
            while (fileOffset < file.length()) {
                raf.seek(fileOffset)
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 0) break
                
                // 在缓冲区中搜索
                var i = 0
                while (i <= bytesRead - searchBytes.size) {
                    var found = true
                    for (j in searchBytes.indices) {
                        if (buffer[i + j] != searchBytes[j]) {
                            found = false
                            break
                        }
                    }
                    if (found) {
                        offsets.add(fileOffset + i)
                        // 限制最大匹配数量
                        if (offsets.size >= 1000) {
                            return offsets
                        }
                    }
                    i++
                }
                
                // 移动到下一个缓冲区，保留重叠部分
                fileOffset += bufferSize
            }
        }
    } catch (e: Exception) {
        // 搜索失败，返回空列表
    }
    
    return offsets
}

