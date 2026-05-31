package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings

@Composable
fun DebugBar(
    debugStatus: DebugStatus,
    onContinue: () -> Unit,
    onStepOver: () -> Unit,
    onStepInto: () -> Unit,
    onStepOut: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPaused = debugStatus == DebugStatus.PAUSED
    val isRunning = debugStatus == DebugStatus.RUNNING
    val canStop = debugStatus != DebugStatus.IDLE && debugStatus != DebugStatus.TERMINATED
    val canStep = isPaused
    val scrollState = rememberScrollState()

    TinaOverlayPanelSurface(
        modifier = modifier.height(44.dp),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DebugIconButton(
                onClick = when {
                    isPaused -> onContinue
                    isRunning -> onPause
                    else -> ({})
                },
                enabled = isPaused || isRunning,
                imageVector = when {
                    isPaused -> Icons.Default.PlayArrow
                    isRunning -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                },
                contentDescription = stringResource(
                    when {
                        isPaused -> Strings.content_desc_continue
                        isRunning -> Strings.content_desc_pause
                        else -> Strings.content_desc_continue
                    }
                ),
                tint = when {
                    isPaused -> TinaSemanticColors.Debug.paused
                    isRunning -> TinaSemanticColors.Debug.running
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )

            DebugIconButton(
                onClick = onStepOver,
                enabled = canStep,
                imageVector = Icons.Default.SkipNext,
                contentDescription = stringResource(Strings.content_desc_step_over)
            )

            DebugIconButton(
                onClick = onStepInto,
                enabled = canStep,
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = stringResource(Strings.content_desc_step_into)
            )

            DebugIconButton(
                onClick = onStepOut,
                enabled = canStep,
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = stringResource(Strings.content_desc_step_out)
            )

            DebugIconButton(
                onClick = onStop,
                enabled = canStop,
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(Strings.content_desc_stop),
                tint = if (canStop) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.5f
                    )
                }
            )
        }
    }
}

@Composable
private fun DebugIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(32.dp),
        minHeight = 32.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}
