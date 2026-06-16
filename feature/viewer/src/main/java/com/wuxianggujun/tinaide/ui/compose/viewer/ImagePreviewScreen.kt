package com.wuxianggujun.tinaide.ui.compose.viewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片信息数据类
 */
data class ImageInfo(
    val width: Int,
    val height: Int,
    val format: String,
    val fileSize: Long
)

/**
 * 图片预览屏幕
 */
@Composable
fun ImagePreviewScreen(
    filePath: String,
    modifier: Modifier = Modifier
) {
    val file = remember { File(filePath) }

    // 图片信息状态
    var imageInfo by remember { mutableStateOf<ImageInfo?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    // 加载图片信息
    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            imageInfo = loadImageInfo(file)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 图片显示区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (hasError) {
                // 错误状态
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(Strings.error_load_image_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                ZoomableImage(
                    filePath = filePath,
                    scale = scale,
                    offset = offset,
                    rotation = rotation,
                    onScaleChange = { scale = it },
                    onOffsetChange = { offset = it },
                    onLoadingChange = { isLoading = it },
                    onErrorChange = { hasError = it }
                )

                // 加载指示器
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        // 底部信息栏
        ImageInfoBar(
            info = imageInfo,
            scale = scale,
            onRotateClick = { rotation = (rotation + 90f) % 360f },
            onResetClick = {
                scale = 1f
                offset = Offset.Zero
                rotation = 0f
            }
        )
    }
}

@Composable
private fun ZoomableImage(
    filePath: String,
    scale: Float,
    offset: Offset,
    rotation: Float,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onErrorChange: (Boolean) -> Unit
) {
    val context = LocalContext.current

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        onScaleChange((scale * zoomChange).coerceIn(0.5f, 5f))
        onOffsetChange(offset + panChange)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformableState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // 双击切换缩放
                        if (scale > 1f) {
                            onScaleChange(1f)
                            onOffsetChange(Offset.Zero)
                        } else {
                            onScaleChange(2f)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(filePath))
                .crossfade(true)
                .build(),
            contentDescription = "Image preview",
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotation
                },
            contentScale = ContentScale.Fit,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> {
                        onLoadingChange(true)
                        onErrorChange(false)
                    }
                    is AsyncImagePainter.State.Success -> {
                        onLoadingChange(false)
                        onErrorChange(false)
                    }
                    is AsyncImagePainter.State.Error -> {
                        onLoadingChange(false)
                        onErrorChange(true)
                    }
                    else -> {}
                }
            }
        )
    }
}

@Composable
private fun ImageInfoBar(
    info: ImageInfo?,
    scale: Float,
    onRotateClick: () -> Unit,
    onResetClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 尺寸
            info?.let {
                Text(
                    text = "${it.width}×${it.height}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = it.format.uppercase(),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = formatFileSize(it.fileSize),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 缩放比例
            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )

            // 旋转按钮
            IconButton(onClick = onRotateClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = stringResource(Strings.content_desc_rotate)
                )
            }

            // 重置按钮
            IconButton(onClick = onResetClick) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Strings.content_desc_reset)
                )
            }
        }
    }
}

/**
 * 加载图片信息
 */
private fun loadImageInfo(file: File): ImageInfo? {
    if (!file.exists()) return null

    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val format = when (file.extension.lowercase()) {
            "png" -> "PNG"
            "jpg", "jpeg" -> "JPEG"
            "gif" -> "GIF"
            "webp" -> "WebP"
            "bmp" -> "BMP"
            "svg" -> "SVG"
            "ico" -> "ICO"
            else -> file.extension.uppercase()
        }

        ImageInfo(
            width = options.outWidth,
            height = options.outHeight,
            format = format,
            fileSize = file.length()
        )
    } catch (e: Exception) {
        ImageInfo(
            width = 0,
            height = 0,
            format = file.extension.uppercase(),
            fileSize = file.length()
        )
    }
}

/**
 * 格式化文件大小
 */
fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    else -> "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
}
