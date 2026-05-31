package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 底部面板 Tab 栏
 */
@Composable
fun BottomPanelTabRow(
    selectedTab: BottomPanelTab,
    onTabSelected: (BottomPanelTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<BottomPanelTab> = BottomPanelTab.entries,
    isNearFullScreen: Boolean = false,
    onToggleFullScreen: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    val selectedTabIndex = tabs.indexOf(selectedTab).let { if (it >= 0) it else 0 }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BottomPanelTabRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.weight(1f),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            edgePadding = 8.dp,
            divider = {} // 移除默认分隔线
        ) {
            tabs.forEach { tab ->
                // 使用空的 interactionSource 禁用 ripple 效果
                val interactionSource = remember { MutableInteractionSource() }
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    interactionSource = interactionSource,
                    text = {
                        Text(
                            text = stringResource(tab.titleRes),
                            fontSize = 13.sp,
                            color = if (selectedTab == tab) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
            }
        }

        if (onToggleFullScreen != null || onClose != null) {
            Spacer(modifier = Modifier.width(6.dp))
        }

        if (onToggleFullScreen != null) {
            TinaPanelSegmentButton(
                onClick = onToggleFullScreen,
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (isNearFullScreen) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (isNearFullScreen) stringResource(Strings.content_desc_restore_panel) else stringResource(Strings.content_desc_expand_fullscreen),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (onClose != null) {
            TinaPanelSegmentButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Strings.content_desc_close_panel),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
