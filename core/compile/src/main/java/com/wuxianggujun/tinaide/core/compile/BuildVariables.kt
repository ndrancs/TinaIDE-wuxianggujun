package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import java.io.File

/**
 * 构建变量系统
 *
 * 支持类似 CLion/IDE 的变量替换功能，用于运行配置中的路径和参数。
 *
 * 支持的变量：
 * - $ProjectDir$ - 项目根目录的绝对路径
 * - $ProjectName$ - 项目名称
 * - $CurrentFile$ - 当前编辑文件的绝对路径
 * - $CurrentFileName$ - 当前编辑文件的文件名（含扩展名）
 * - $CurrentFileNameWithoutExtension$ - 当前编辑文件的文件名（不含扩展名）
 * - $CurrentFileDir$ - 当前编辑文件所在目录的绝对路径
 * - $CurrentFileRelativePath$ - 当前编辑文件相对于项目根目录的路径
 * - $BuildDir$ - 构建输出目录（通常是 $ProjectDir$/build）
 * - $SourceFile$ - 指定的源文件路径（用于单文件编译）
 * - $SourceFileName$ - 指定的源文件名（含扩展名）
 * - $SourceFileNameWithoutExtension$ - 指定的源文件名（不含扩展名）
 * - $SourceFileDir$ - 指定的源文件所在目录
 */
object BuildVariables {

    // 变量名常量
    const val PROJECT_DIR = "\$ProjectDir\$"
    const val PROJECT_NAME = "\$ProjectName\$"
    const val CURRENT_FILE = "\$CurrentFile\$"
    const val CURRENT_FILE_NAME = "\$CurrentFileName\$"
    const val CURRENT_FILE_NAME_WITHOUT_EXT = "\$CurrentFileNameWithoutExtension\$"
    const val CURRENT_FILE_DIR = "\$CurrentFileDir\$"
    const val CURRENT_FILE_RELATIVE_PATH = "\$CurrentFileRelativePath\$"
    const val BUILD_DIR = "\$BuildDir\$"
    const val SOURCE_FILE = "\$SourceFile\$"
    const val SOURCE_FILE_NAME = "\$SourceFileName\$"
    const val SOURCE_FILE_NAME_WITHOUT_EXT = "\$SourceFileNameWithoutExtension\$"
    const val SOURCE_FILE_DIR = "\$SourceFileDir\$"

    /**
     * 所有支持的变量列表（用于 UI 显示）
     */
    val ALL_VARIABLES = listOf(
        VariableInfo(PROJECT_DIR, Strings.build_var_desc_project_dir),
        VariableInfo(PROJECT_NAME, Strings.build_var_desc_project_name),
        VariableInfo(CURRENT_FILE, Strings.build_var_desc_current_file),
        VariableInfo(CURRENT_FILE_NAME, Strings.build_var_desc_current_file_name),
        VariableInfo(CURRENT_FILE_NAME_WITHOUT_EXT, Strings.build_var_desc_current_file_name_no_ext),
        VariableInfo(CURRENT_FILE_DIR, Strings.build_var_desc_current_file_dir),
        VariableInfo(CURRENT_FILE_RELATIVE_PATH, Strings.build_var_desc_current_file_relative_path),
        VariableInfo(BUILD_DIR, Strings.build_var_desc_build_dir),
        VariableInfo(SOURCE_FILE, Strings.build_var_desc_source_file),
        VariableInfo(SOURCE_FILE_NAME, Strings.build_var_desc_source_file_name),
        VariableInfo(SOURCE_FILE_NAME_WITHOUT_EXT, Strings.build_var_desc_source_file_name_no_ext),
        VariableInfo(SOURCE_FILE_DIR, Strings.build_var_desc_source_file_dir)
    )

    /**
     * 变量信息
     */
    data class VariableInfo(
        val name: String,
        @param:StringRes @get:StringRes val descriptionResId: Int
    ) {
        fun getDescription(context: Context): String = context.getString(descriptionResId)
    }

    /**
     * 构建上下文 - 包含变量替换所需的所有信息
     */
    data class BuildContext(
        val projectDir: File,
        val projectName: String,
        val currentFile: File? = null,
        val sourceFile: File? = null,
        val buildDir: File? = null
    ) {
        /**
         * 获取实际的构建目录
         */
        fun getEffectiveBuildDir(): File = buildDir ?: File(projectDir, "build")

        /**
         * 获取实际的源文件（优先使用指定的源文件，否则使用当前文件）
         */
        fun getEffectiveSourceFile(): File? = sourceFile ?: currentFile
    }

    /**
     * 替换字符串中的所有变量
     *
     * @param input 包含变量的输入字符串
     * @param context 构建上下文
     * @return 替换后的字符串
     */
    fun expand(input: String, context: BuildContext): String {
        if (input.isBlank()) return input

        var result = input

        // 项目相关变量
        result = result.replace(PROJECT_DIR, context.projectDir.absolutePath)
        result = result.replace(PROJECT_NAME, context.projectName)
        result = result.replace(BUILD_DIR, context.getEffectiveBuildDir().absolutePath)

        // 当前文件相关变量
        context.currentFile?.let { file ->
            result = result.replace(CURRENT_FILE, file.absolutePath)
            result = result.replace(CURRENT_FILE_NAME, file.name)
            result = result.replace(CURRENT_FILE_NAME_WITHOUT_EXT, file.nameWithoutExtension)
            result = result.replace(CURRENT_FILE_DIR, file.parentFile?.absolutePath ?: "")

            // 计算相对路径
            val relativePath = try {
                file.relativeTo(context.projectDir).path
            } catch (e: Exception) {
                file.name
            }
            result = result.replace(CURRENT_FILE_RELATIVE_PATH, relativePath)
        }

        // 源文件相关变量
        context.sourceFile?.let { file ->
            result = result.replace(SOURCE_FILE, file.absolutePath)
            result = result.replace(SOURCE_FILE_NAME, file.name)
            result = result.replace(SOURCE_FILE_NAME_WITHOUT_EXT, file.nameWithoutExtension)
            result = result.replace(SOURCE_FILE_DIR, file.parentFile?.absolutePath ?: "")
        }

        return result
    }

    /**
     * 替换参数列表中的所有变量
     */
    fun expandArgs(args: List<String>, context: BuildContext): List<String> = args.map { expand(it, context) }

    /**
     * 检查字符串是否包含变量
     */
    fun containsVariables(input: String): Boolean = input.contains("\$") && ALL_VARIABLES.any { input.contains(it.name) }

    /**
     * 获取字符串中使用的所有变量
     */
    fun getUsedVariables(input: String): List<VariableInfo> = ALL_VARIABLES.filter { input.contains(it.name) }

    /**
     * 验证变量是否可以在给定上下文中解析
     *
     * @return 无法解析的变量列表
     */
    fun validateVariables(input: String, context: BuildContext): List<String> {
        val unresolved = mutableListOf<String>()

        if (input.contains(CURRENT_FILE) ||
            input.contains(CURRENT_FILE_NAME) ||
            input.contains(CURRENT_FILE_NAME_WITHOUT_EXT) ||
            input.contains(CURRENT_FILE_DIR) ||
            input.contains(CURRENT_FILE_RELATIVE_PATH)
        ) {
            if (context.currentFile == null) {
                unresolved.add(Strings.build_var_unresolved_current_file.str())
            }
        }

        if (input.contains(SOURCE_FILE) ||
            input.contains(SOURCE_FILE_NAME) ||
            input.contains(SOURCE_FILE_NAME_WITHOUT_EXT) ||
            input.contains(SOURCE_FILE_DIR)
        ) {
            if (context.sourceFile == null) {
                unresolved.add(Strings.build_var_unresolved_source_file.str())
            }
        }

        return unresolved
    }
}
