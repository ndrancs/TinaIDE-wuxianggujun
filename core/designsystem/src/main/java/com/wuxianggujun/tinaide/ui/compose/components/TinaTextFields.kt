package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * TinaIDE 输入框组件库
 */

/**
 * 统一的输入框组件
 *
 * 支持静态提示和动态提示两种模式：
 * - 静态提示：使用 `hint` 参数传入固定字符串
 * - 动态提示：使用 `dynamicHint` 参数传入 @Composable 函数，可根据当前输入值动态生成提示
 *
 * 优先级（从高到低）：errorText > supportingText > dynamicHint > hint
 *
 * @param value 输入值
 * @param onValueChange 值变化回调
 * @param modifier 修饰符
 * @param label 标签文本
 * @param placeholder 占位符文本
 * @param hint 静态提示文本（在没有错误时显示）
 * @param dynamicHint 动态提示函数，根据当前输入值返回提示文本，返回 null 则不显示（优先级高于 hint）
 * @param supportingText 支持文本 @Composable 函数（优先级高于 hint 和 dynamicHint）
 * @param isError 是否显示错误状态
 * @param errorText 错误文本（优先级最高）
 * @param singleLine 是否单行
 * @param maxLines 最大行数（仅在 singleLine = false 时生效）
 * @param minLines 最小行数（仅在 singleLine = false 时生效）
 * @param readOnly 是否只读
 * @param enabled 是否启用
 * @param visualTransformation 视觉转换（如密码）
 * @param keyboardOptions 键盘选项
 * @param keyboardActions 键盘动作
 * @param leadingIcon 前置图标
 * @param trailingIcon 后置图标
 * @param textStyle 文本样式
 */
@Composable
fun TinaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    dynamicHint: @Composable ((String) -> String?)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current
) {
    // 在 Composable 上下文中计算动态提示
    val dynamicHintText = if (!isError) dynamicHint?.invoke(value) else null

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = when {
            isError && errorText != null -> {
                { Text(errorText, color = MaterialTheme.colorScheme.error) }
            }
            supportingText != null -> supportingText
            dynamicHintText != null -> {
                {
                    Text(
                        text = dynamicHintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            hint != null -> {
                {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> null
        },
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        readOnly = readOnly,
        enabled = enabled,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        textStyle = textStyle,
        shape = RoundedCornerShape(TinaShapes.TextFieldCorner)
    )
}

/**
 * 搜索输入框
 *
 * @param value 输入值
 * @param onValueChange 值变化回调
 * @param modifier 修饰符
 * @param placeholder 占位符文本
 * @param leadingIcon 前置图标
 * @param trailingIcon 后置图标
 * @param onClearClick 清除按钮点击回调（当有值时自动显示清除按钮）
 * @param enabled 是否启用
 */
@Composable
fun TinaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(Strings.tina_search_placeholder),
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onClearClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = if (value.isNotEmpty() && onClearClick != null) {
            {
                IconButton(onClick = onClearClick) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(Strings.content_desc_clear_input)
                    )
                }
            }
        } else {
            trailingIcon
        },
        shape = RoundedCornerShape(TinaShapes.ButtonCorner)
    )
}
