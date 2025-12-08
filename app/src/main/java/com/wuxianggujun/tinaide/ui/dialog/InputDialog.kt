package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.databinding.DialogInputV2Binding

/**
 * Material Design 3 风格的输入对话框
 * 使用 DialogFragment 实现，支持配置变化和生命周期管理
 */
class InputDialog : DialogFragment() {

    private var _binding: DialogInputV2Binding? = null
    private val binding get() = _binding!!

    private var title: String = ""
    private var hint: String = ""
    private var defaultValue: String = ""
    private var positiveText: String = "确定"
    private var negativeText: String = "取消"
    private var validator: ((String) -> String?)? = null
    private var onConfirm: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogInputV2Binding.inflate(layoutInflater)

        // 恢复状态
        savedInstanceState?.let {
            title = it.getString(KEY_TITLE, title)
            hint = it.getString(KEY_HINT, hint)
            defaultValue = it.getString(KEY_DEFAULT_VALUE, defaultValue)
            positiveText = it.getString(KEY_POSITIVE_TEXT, positiveText)
            negativeText = it.getString(KEY_NEGATIVE_TEXT, negativeText)
        }

        setupViews()

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(positiveText, null) // 先设为 null，后面手动处理
            .setNegativeButton(negativeText, null)
            .create()

        // 对话框显示后设置按钮点击事件
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(Dialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val value = binding.editText.text?.toString() ?: ""
                
                // 验证输入
                val error = validator?.invoke(value)
                if (error != null) {
                    binding.textInputLayout.error = error
                } else {
                    binding.textInputLayout.error = null
                    onConfirm?.invoke(value)
                    dismiss()
                }
            }
        }

        return dialog
    }

    override fun onStart() {
        super.onStart()
        // 自动弹出键盘
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        binding.editText.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TITLE, title)
        outState.putString(KEY_HINT, hint)
        outState.putString(KEY_DEFAULT_VALUE, binding.editText.text?.toString() ?: "")
        outState.putString(KEY_POSITIVE_TEXT, positiveText)
        outState.putString(KEY_NEGATIVE_TEXT, negativeText)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupViews() {
        binding.textInputLayout.hint = hint
        binding.editText.setText(defaultValue)
        binding.editText.setSelection(defaultValue.length)

        // 实时验证
        binding.editText.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            val error = validator?.invoke(value)
            binding.textInputLayout.error = error
        }
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_HINT = "hint"
        private const val KEY_DEFAULT_VALUE = "defaultValue"
        private const val KEY_POSITIVE_TEXT = "positiveText"
        private const val KEY_NEGATIVE_TEXT = "negativeText"

        /**
         * 创建输入对话框
         */
        fun newInstance(
            title: String,
            hint: String = "",
            defaultValue: String = "",
            positiveText: String = "确定",
            negativeText: String = "取消",
            validator: ((String) -> String?)? = null,
            onConfirm: (String) -> Unit
        ): InputDialog {
            return InputDialog().apply {
                this.title = title
                this.hint = hint
                this.defaultValue = defaultValue
                this.positiveText = positiveText
                this.negativeText = negativeText
                this.validator = validator
                this.onConfirm = onConfirm
            }
        }
    }
}
