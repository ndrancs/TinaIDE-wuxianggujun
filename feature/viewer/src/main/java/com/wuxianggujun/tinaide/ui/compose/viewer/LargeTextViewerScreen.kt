package com.wuxianggujun.tinaide.ui.compose.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private class LargeTextPager(
    private val file: File
) {
    private var reader: BufferedReader? = null
    private var isEof: Boolean = false

    fun reset() {
        close()
        reader = BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8))
        isEof = false
    }

    fun close() {
        runCatching { reader?.close() }
        reader = null
        isEof = false
    }

    suspend fun readNextLines(maxLines: Int): Result<Pair<List<String>, Boolean>> {
        if (isEof) return Result.success(emptyList<String>() to true)
        val currentReader = reader ?: return Result.failure(IllegalStateException("Reader not initialized"))
        return withContext(Dispatchers.IO) {
            runCatching {
                val lines = ArrayList<String>(maxLines)
                repeat(maxLines) {
                    val line = currentReader.readLine()
                    if (line == null) {
                        isEof = true
                        return@repeat
                    }
                    lines.add(line)
                }
                lines to isEof
            }
        }
    }
}

@Composable
fun LargeTextViewerScreen(
    filePath: String,
    modifier: Modifier = Modifier,
    pageSizeLines: Int = 300,
    maxLinesInMemory: Int = 50_000,
    onOpenAsEditor: (() -> Unit)? = null,
    onOpenAsHex: (() -> Unit)? = null
) {
    val file = remember(filePath) { File(filePath) }
    val pager = remember(filePath) { LargeTextPager(file) }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val lines = remember(filePath) { mutableStateListOf<String>() }
    var isLoading by remember(filePath) { mutableStateOf(false) }
    var isEof by remember(filePath) { mutableStateOf(false) }
    var error by remember(filePath) { mutableStateOf<String?>(null) }
    var reachedLimit by remember(filePath) { mutableStateOf(false) }

    fun loadMore() {
        if (isLoading || isEof || reachedLimit) return
        isLoading = true
        error = null
        scope.launch {
            mutex.withLock {
                val result = pager.readNextLines(pageSizeLines)
                result.onSuccess { (newLines, eof) ->
                    val remainingCapacity = (maxLinesInMemory - lines.size).coerceAtLeast(0)
                    val accepted = if (newLines.size <= remainingCapacity) newLines else newLines.take(remainingCapacity)
                    lines.addAll(accepted)
                    isEof = eof
                    reachedLimit = lines.size >= maxLinesInMemory
                }.onFailure { e ->
                    error = e.message
                }
                isLoading = false
            }
        }
    }

    fun resetAndLoad() {
        isLoading = false
        isEof = false
        error = null
        reachedLimit = false
        lines.clear()
        runCatching { pager.reset() }
            .onFailure { e -> error = e.message }
        if (error == null) {
            loadMore()
        }
    }

    LaunchedEffect(filePath) {
        resetAndLoad()
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val lastIndex = lastVisibleIndex ?: return@collect
                if (lastIndex >= lines.size - 30) {
                    loadMore()
                }
            }
    }

    DisposableEffect(filePath) {
        onDispose { pager.close() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fileSize = remember(filePath) { runCatching { file.length() }.getOrDefault(0L) }
                Text(
                    text = stringResource(Strings.large_file_viewer_info, file.name, fileSize / 1024),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (onOpenAsEditor != null) {
                    TextButton(onClick = onOpenAsEditor) { Text(stringResource(Strings.large_file_open_editor)) }
                }
                if (onOpenAsHex != null) {
                    TextButton(onClick = onOpenAsHex) { Text(stringResource(Strings.large_file_hex)) }
                }
            }
        }

        when {
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = error ?: stringResource(Strings.large_file_load_failed), color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.padding(4.dp))
                        TextButton(onClick = { resetAndLoad() }) { Text(stringResource(Strings.large_file_retry)) }
                    }
                }
            }

            lines.isEmpty() && isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    itemsIndexed(lines) { index, line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = (index + 1).toString().padStart(6),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    item {
                        when {
                            reachedLimit -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(Strings.large_file_reached_limit, maxLinesInMemory),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            isEof -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(Strings.large_file_end_of_file),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            isLoading -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                                    Text(
                                        text = stringResource(Strings.large_file_loading),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
