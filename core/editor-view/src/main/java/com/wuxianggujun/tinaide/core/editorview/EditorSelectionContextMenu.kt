package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.wuxianggujun.tinaide.core.editorview.R

internal const val SELECTION_CONTEXT_MENU_TAG = "editor_selection_context_menu"
internal const val SELECTION_CONTEXT_MENU_TEXT_GROUP_TAG = "editor_selection_context_menu_group_text"
internal const val SELECTION_CONTEXT_MENU_CODE_GROUP_TAG = "editor_selection_context_menu_group_code"
internal const val SELECTION_CONTEXT_MENU_COPY_ACTION_TAG = "editor_selection_context_menu_action_copy"
internal const val SELECTION_CONTEXT_MENU_CUT_ACTION_TAG = "editor_selection_context_menu_action_cut"
internal const val SELECTION_CONTEXT_MENU_PASTE_ACTION_TAG = "editor_selection_context_menu_action_paste"
internal const val SELECTION_CONTEXT_MENU_SELECT_ALL_ACTION_TAG = "editor_selection_context_menu_action_select_all"
internal const val SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG = "editor_selection_context_menu_action_goto_definition"
internal const val SELECTION_CONTEXT_MENU_PEEK_DEFINITION_ACTION_TAG = "editor_selection_context_menu_action_peek_definition"
internal const val SELECTION_CONTEXT_MENU_FIND_REFERENCES_ACTION_TAG = "editor_selection_context_menu_action_find_references"
internal const val SELECTION_CONTEXT_MENU_GOTO_TYPE_DEFINITION_ACTION_TAG =
    "editor_selection_context_menu_action_goto_type_definition"
internal const val SELECTION_CONTEXT_MENU_GOTO_IMPLEMENTATION_ACTION_TAG =
    "editor_selection_context_menu_action_goto_implementation"
internal const val SELECTION_CONTEXT_MENU_CODE_ACTIONS_ACTION_TAG = "editor_selection_context_menu_action_code_actions"
internal const val SELECTION_CONTEXT_MENU_RENAME_SYMBOL_ACTION_TAG = "editor_selection_context_menu_action_rename_symbol"
internal const val SELECTION_CONTEXT_MENU_SWITCH_HEADER_SOURCE_ACTION_TAG =
    "editor_selection_context_menu_action_switch_header_source"
internal const val SELECTION_CONTEXT_MENU_HOVER_ACTION_TAG = "editor_selection_context_menu_action_hover"

@Composable
internal fun EditorSelectionContextMenu(
    visible: Boolean,
    positionProvider: PopupPositionProvider,
    selectedText: String?,
    keyboardSelectedAction: EditorContextMenuActionId? = null,
    colorScheme: EditorColorScheme,
    hoverEnabled: Boolean,
    peekDefinitionEnabled: Boolean = false,
    gotoDefinitionEnabled: Boolean,
    findReferencesEnabled: Boolean,
    gotoTypeDefinitionEnabled: Boolean,
    gotoImplementationEnabled: Boolean,
    codeActionsEnabled: Boolean,
    renameSymbolEnabled: Boolean,
    switchHeaderSourceEnabled: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onPeekDefinition: () -> Unit = {},
    onGotoDefinition: () -> Unit,
    onFindReferences: () -> Unit,
    onGotoTypeDefinition: () -> Unit,
    onGotoImplementation: () -> Unit,
    onCodeActions: () -> Unit,
    onRenameSymbol: () -> Unit,
    onSwitchHeaderSource: () -> Unit,
    onHover: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var textMenuExpanded by remember { mutableStateOf(false) }
    var codeMenuExpanded by remember { mutableStateOf(false) }
    val popupColors = rememberEditorPopupColors(colorScheme)
    val hasCodeGroup = peekDefinitionEnabled ||
        gotoDefinitionEnabled ||
        findReferencesEnabled ||
        gotoTypeDefinitionEnabled ||
        gotoImplementationEnabled ||
        codeActionsEnabled ||
        renameSymbolEnabled ||
        switchHeaderSourceEnabled

    LaunchedEffect(keyboardSelectedAction) {
        when (keyboardSelectedAction) {
            EditorContextMenuActionId.Copy,
            EditorContextMenuActionId.Cut,
            EditorContextMenuActionId.Paste,
            EditorContextMenuActionId.SelectAll -> textMenuExpanded = true
            EditorContextMenuActionId.PeekDefinition,
            EditorContextMenuActionId.GotoDefinition,
            EditorContextMenuActionId.FindReferences,
            EditorContextMenuActionId.GotoTypeDefinition,
            EditorContextMenuActionId.GotoImplementation,
            EditorContextMenuActionId.CodeActions,
            EditorContextMenuActionId.RenameSymbol,
            EditorContextMenuActionId.SwitchHeaderSource -> codeMenuExpanded = true
            else -> Unit
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        // focusable=false 确保菜单弹出时不抢夺编辑器焦点，
        // 避免：菜单弹出 → 编辑器失焦 → FocusCoordinator 关闭菜单 的循环。
        // 同时避免用户点击 IME 切换按钮等区域时触发 onDismissRequest 关闭菜单。
        properties = PopupProperties(focusable = false)
    ) {
        EditorPopupScaffold(
            colors = popupColors,
            modifier = Modifier
                .testTag(SELECTION_CONTEXT_MENU_TAG)
                .width(176.dp),
            contentModifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(vertical = 2.dp)
        ) {
            EditorContextMenuGroup(
                title = stringResource(R.string.editor_context_menu_text_group),
                tag = SELECTION_CONTEXT_MENU_TEXT_GROUP_TAG,
                expanded = textMenuExpanded,
                popupColors = popupColors,
                onExpandedChange = { textMenuExpanded = !textMenuExpanded }
            ) {
                EditorContextMenuAction(
                    title = stringResource(R.string.editor_context_menu_copy),
                    tag = SELECTION_CONTEXT_MENU_COPY_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Copy,
                    enabled = selectedText != null,
                    popupColors = popupColors,
                    onClick = onCopy
                )
                EditorContextMenuAction(
                    title = stringResource(R.string.editor_context_menu_cut),
                    tag = SELECTION_CONTEXT_MENU_CUT_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Cut,
                    enabled = selectedText != null,
                    popupColors = popupColors,
                    onClick = onCut
                )
                EditorContextMenuAction(
                    title = stringResource(R.string.editor_context_menu_paste),
                    tag = SELECTION_CONTEXT_MENU_PASTE_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Paste,
                    popupColors = popupColors,
                    onClick = onPaste
                )
                EditorContextMenuAction(
                    title = stringResource(R.string.editor_context_menu_select_all),
                    tag = SELECTION_CONTEXT_MENU_SELECT_ALL_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.SelectAll,
                    popupColors = popupColors,
                    onClick = onSelectAll
                )
            }
            if (hasCodeGroup) {
                EditorPopupDivider(colors = popupColors)
                EditorContextMenuGroup(
                    title = stringResource(R.string.editor_context_menu_code_group),
                    tag = SELECTION_CONTEXT_MENU_CODE_GROUP_TAG,
                    expanded = codeMenuExpanded,
                    popupColors = popupColors,
                    onExpandedChange = { codeMenuExpanded = !codeMenuExpanded }
                ) {
                    if (peekDefinitionEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_peek_definition),
                            tag = SELECTION_CONTEXT_MENU_PEEK_DEFINITION_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.PeekDefinition,
                            popupColors = popupColors,
                            onClick = onPeekDefinition
                        )
                    }
                    if (gotoDefinitionEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_goto_definition),
                            tag = SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.GotoDefinition,
                            popupColors = popupColors,
                            onClick = onGotoDefinition
                        )
                    }
                    if (findReferencesEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_find_references),
                            tag = SELECTION_CONTEXT_MENU_FIND_REFERENCES_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.FindReferences,
                            popupColors = popupColors,
                            onClick = onFindReferences
                        )
                    }
                    if (gotoTypeDefinitionEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_goto_type_definition),
                            tag = SELECTION_CONTEXT_MENU_GOTO_TYPE_DEFINITION_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.GotoTypeDefinition,
                            popupColors = popupColors,
                            onClick = onGotoTypeDefinition
                        )
                    }
                    if (gotoImplementationEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_goto_implementation),
                            tag = SELECTION_CONTEXT_MENU_GOTO_IMPLEMENTATION_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.GotoImplementation,
                            popupColors = popupColors,
                            onClick = onGotoImplementation
                        )
                    }
                    if (codeActionsEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_code_actions),
                            tag = SELECTION_CONTEXT_MENU_CODE_ACTIONS_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.CodeActions,
                            popupColors = popupColors,
                            onClick = onCodeActions
                        )
                    }
                    if (renameSymbolEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_rename_symbol),
                            tag = SELECTION_CONTEXT_MENU_RENAME_SYMBOL_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.RenameSymbol,
                            popupColors = popupColors,
                            onClick = onRenameSymbol
                        )
                    }
                    if (switchHeaderSourceEnabled) {
                        EditorContextMenuAction(
                            title = stringResource(R.string.editor_context_menu_switch_header_source),
                            tag = SELECTION_CONTEXT_MENU_SWITCH_HEADER_SOURCE_ACTION_TAG,
                            keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.SwitchHeaderSource,
                            popupColors = popupColors,
                            onClick = onSwitchHeaderSource
                        )
                    }
                }
            }
            EditorPopupDivider(colors = popupColors)
            EditorContextMenuAction(
                title = stringResource(R.string.editor_context_menu_hover),
                tag = SELECTION_CONTEXT_MENU_HOVER_ACTION_TAG,
                keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Hover,
                enabled = hoverEnabled,
                popupColors = popupColors,
                onClick = onHover
            )
        }
    }
}

@Composable
private fun EditorContextMenuGroup(
    title: String,
    tag: String,
    expanded: Boolean,
    popupColors: EditorPopupColors,
    onExpandedChange: () -> Unit,
    content: @Composable () -> Unit
) {
    EditorPopupActionButton(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        onClick = onExpandedChange,
        colors = popupColors,
        contentPadding = editorPopupCompactActionPadding
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = popupColors.secondaryTextColor
            )
        }
    }
    if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun EditorContextMenuAction(
    title: String,
    tag: String,
    popupColors: EditorPopupColors,
    keyboardSelected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    EditorPopupActionButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(
                if (keyboardSelected && enabled) {
                    popupColors.selectedSurfaceColor
                } else {
                    popupColors.containerColor
                }
            )
            .testTag(tag),
        onClick = onClick,
        enabled = enabled,
        colors = popupColors,
        contentPadding = editorPopupCompactActionPadding
    ) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
