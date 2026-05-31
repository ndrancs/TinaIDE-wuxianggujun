package com.wuxianggujun.tinaide.ui.workspace.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.ui.compose.components.TinaPanelSegmentButton

object SetupTopBarDefaults {
    val Height = 56.dp
    val IconSize = 40.dp
    val HorizontalPadding = 4.dp
    val VerticalPadding = 8.dp
}

@Composable
fun SetupActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier,
        minHeight = SetupTopBarDefaults.IconSize,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = contentPadding,
        contentAlignment = Alignment.Center,
        content = content
    )
}
