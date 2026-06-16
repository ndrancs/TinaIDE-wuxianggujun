package com.wuxianggujun.tinaide.core.commands

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

enum class HostCommandCategory(
    @param:StringRes @get:StringRes val titleRes: Int,
    val order: Int
) {
    FILE(Strings.menu_section_file, 10),
    CODE(Strings.menu_section_code, 20),
    BUILD(Strings.menu_section_build, 30),
    VIEW(Strings.menu_section_view, 40),
    TERMINAL(Strings.menu_terminal, 50)
}

enum class HostCommandAvailability {
    ALWAYS,
    ACTIVE_FILE,
    DIRTY_ACTIVE_FILE,
    ACTIVE_EDITOR,
    BASIC_LSP_NAVIGATION,
    ADVANCED_LSP_NAVIGATION,
    LSP_REFACTOR,
    HEADER_SOURCE_SWITCH,
    NAVIGATE_BACK,
    NAVIGATE_FORWARD,
    NOT_COMPILING
}

enum class HostCommandSurface {
    COMMAND_PALETTE,
    KEYBOARD_SHORTCUT,
    PLUGIN_CONTRIBUTION,
    FILE_TREE_CONTEXT,
    EDITOR_CONTEXT,
    EDITOR_TOOLBAR,
    TOP_BAR
}

data class HostCommandShortcut(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false
)

data class HostCommandDescriptor(
    val id: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    val category: HostCommandCategory,
    val keywords: List<String> = emptyList(),
    val availability: HostCommandAvailability = HostCommandAvailability.ALWAYS,
    val defaultShortcut: HostCommandShortcut? = null,
    val surfaces: Set<HostCommandSurface> = defaultSurfaces
) {
    companion object {
        val defaultSurfaces: Set<HostCommandSurface> = setOf(
            HostCommandSurface.COMMAND_PALETTE,
            HostCommandSurface.PLUGIN_CONTRIBUTION
        )
    }
}

object HostCommandCatalog {
    private val contextMenuSurfaces: Set<HostCommandSurface> = setOf(
        HostCommandSurface.PLUGIN_CONTRIBUTION,
        HostCommandSurface.FILE_TREE_CONTEXT
    )

    val descriptors: List<HostCommandDescriptor> = listOf(
        command(
            id = HostCommands.FILE_NEW,
            titleRes = Strings.action_new_file,
            category = HostCommandCategory.FILE,
            keywords = listOf("new file"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_NEW_FOLDER,
            titleRes = Strings.action_new_folder,
            category = HostCommandCategory.FILE,
            keywords = listOf("new folder"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_RENAME,
            titleRes = Strings.action_rename,
            category = HostCommandCategory.FILE,
            keywords = listOf("rename"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_DELETE,
            titleRes = Strings.btn_delete,
            category = HostCommandCategory.FILE,
            keywords = listOf("delete"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_COPY_PATH,
            titleRes = Strings.action_copy_path,
            category = HostCommandCategory.FILE,
            keywords = listOf("copy path"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_COPY_NAME,
            titleRes = Strings.action_copy_name,
            category = HostCommandCategory.FILE,
            keywords = listOf("copy name"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_COPY_RELATIVE_PATH,
            titleRes = Strings.action_copy_relative_path,
            category = HostCommandCategory.FILE,
            keywords = listOf("copy relative path"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_DUPLICATE,
            titleRes = Strings.cmd_file_duplicate,
            category = HostCommandCategory.FILE,
            keywords = listOf("duplicate"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_OPEN_WITH,
            titleRes = Strings.cmd_file_open_with,
            category = HostCommandCategory.FILE,
            keywords = listOf("open with"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_SHARE,
            titleRes = Strings.cmd_file_share,
            category = HostCommandCategory.FILE,
            keywords = listOf("share"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),
        command(
            id = HostCommands.FILE_REVEAL_IN_FILE_MANAGER,
            titleRes = Strings.cmd_file_reveal_in_file_manager,
            category = HostCommandCategory.FILE,
            keywords = listOf("reveal file manager"),
            availability = HostCommandAvailability.ACTIVE_FILE,
            surfaces = contextMenuSurfaces
        ),

        command(
            id = HostCommands.EDITOR_SAVE,
            titleRes = Strings.cmd_editor_save,
            category = HostCommandCategory.FILE,
            keywords = listOf("save"),
            availability = HostCommandAvailability.DIRTY_ACTIVE_FILE,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_S, ctrl = true)
        ),
        command(
            id = HostCommands.EDITOR_SAVE_ALL,
            titleRes = Strings.cmd_editor_save_all,
            category = HostCommandCategory.FILE,
            keywords = listOf("save all"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_S, ctrl = true, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_CLOSE,
            titleRes = Strings.action_close_current_tab,
            category = HostCommandCategory.FILE,
            keywords = listOf("close tab"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_W, ctrl = true)
        ),
        command(
            id = HostCommands.EDITOR_CLOSE_ALL,
            titleRes = Strings.action_close_all_tabs,
            category = HostCommandCategory.FILE,
            keywords = listOf("close all tabs"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_W, ctrl = true, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_CLOSE_OTHERS,
            titleRes = Strings.action_close_other_tabs,
            category = HostCommandCategory.FILE,
            keywords = listOf("close other tabs"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_NEXT_TAB,
            titleRes = Strings.shortcut_action_next_tab,
            category = HostCommandCategory.VIEW,
            keywords = listOf("next tab"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_TAB, ctrl = true)
        ),
        command(
            id = HostCommands.EDITOR_PREVIOUS_TAB,
            titleRes = Strings.shortcut_action_prev_tab,
            category = HostCommandCategory.VIEW,
            keywords = listOf("previous tab"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_UNDO,
            titleRes = Strings.cmd_editor_undo,
            category = HostCommandCategory.CODE,
            keywords = listOf("undo"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_Z, ctrl = true)
        ),
        command(
            id = HostCommands.EDITOR_REDO,
            titleRes = Strings.cmd_editor_redo,
            category = HostCommandCategory.CODE,
            keywords = listOf("redo"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_SELECT_ALL,
            titleRes = Strings.action_select_all,
            category = HostCommandCategory.CODE,
            keywords = listOf("select all"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_COPY,
            titleRes = Strings.action_copy,
            category = HostCommandCategory.CODE,
            keywords = listOf("copy"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_CUT,
            titleRes = Strings.cmd_editor_cut,
            category = HostCommandCategory.CODE,
            keywords = listOf("cut"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_PASTE,
            titleRes = Strings.cmd_editor_paste,
            category = HostCommandCategory.CODE,
            keywords = listOf("paste"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_FIND,
            titleRes = Strings.cmd_editor_find,
            category = HostCommandCategory.CODE,
            keywords = listOf("find"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_REPLACE,
            titleRes = Strings.cmd_editor_replace,
            category = HostCommandCategory.CODE,
            keywords = listOf("replace"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_GOTO_LINE,
            titleRes = Strings.cmd_editor_goto_line,
            category = HostCommandCategory.CODE,
            keywords = listOf("goto line", "line"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_NAVIGATE_BACK,
            titleRes = Strings.cmd_editor_navigate_back,
            category = HostCommandCategory.CODE,
            keywords = listOf("back", "history"),
            availability = HostCommandAvailability.NAVIGATE_BACK,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_DPAD_LEFT, alt = true)
        ),
        command(
            id = HostCommands.EDITOR_NAVIGATE_FORWARD,
            titleRes = Strings.cmd_editor_navigate_forward,
            category = HostCommandCategory.CODE,
            keywords = listOf("forward", "history"),
            availability = HostCommandAvailability.NAVIGATE_FORWARD,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true)
        ),
        command(
            id = HostCommands.EDITOR_TOGGLE_WORD_WRAP,
            titleRes = Strings.cmd_editor_toggle_word_wrap,
            category = HostCommandCategory.CODE,
            keywords = listOf("word wrap"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_FORMAT,
            titleRes = Strings.cmd_editor_format,
            category = HostCommandCategory.CODE,
            keywords = listOf("format", "clang-format"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_TOGGLE_COMMENT,
            titleRes = Strings.cmd_editor_toggle_comment,
            category = HostCommandCategory.CODE,
            keywords = listOf("comment"),
            availability = HostCommandAvailability.ACTIVE_EDITOR
        ),
        command(
            id = HostCommands.EDITOR_PEEK_DEFINITION,
            titleRes = Strings.lsp_peek_definition,
            category = HostCommandCategory.CODE,
            keywords = listOf("definition", "peek"),
            availability = HostCommandAvailability.BASIC_LSP_NAVIGATION,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F12, alt = true)
        ),
        command(
            id = HostCommands.EDITOR_GOTO_DEFINITION,
            titleRes = Strings.lsp_goto_definition,
            category = HostCommandCategory.CODE,
            keywords = listOf("definition", "goto"),
            availability = HostCommandAvailability.BASIC_LSP_NAVIGATION,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F12)
        ),
        command(
            id = HostCommands.EDITOR_FIND_REFERENCES,
            titleRes = Strings.lsp_find_references,
            category = HostCommandCategory.CODE,
            keywords = listOf("references", "find"),
            availability = HostCommandAvailability.BASIC_LSP_NAVIGATION,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F12, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_GOTO_TYPE_DEFINITION,
            titleRes = Strings.lsp_goto_type_definition,
            category = HostCommandCategory.CODE,
            keywords = listOf("type definition"),
            availability = HostCommandAvailability.ADVANCED_LSP_NAVIGATION,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F12, ctrl = true, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_GOTO_IMPLEMENTATION,
            titleRes = Strings.lsp_goto_implementation,
            category = HostCommandCategory.CODE,
            keywords = listOf("implementation"),
            availability = HostCommandAvailability.ADVANCED_LSP_NAVIGATION,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F12, ctrl = true)
        ),
        command(
            id = HostCommands.EDITOR_CODE_ACTIONS,
            titleRes = Strings.code_actions_title,
            category = HostCommandCategory.CODE,
            keywords = listOf("code action", "quick fix"),
            availability = HostCommandAvailability.LSP_REFACTOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_ENTER, alt = true)
        ),
        command(
            id = HostCommands.EDITOR_RENAME_SYMBOL,
            titleRes = Strings.lsp_template_rename,
            category = HostCommandCategory.CODE,
            keywords = listOf("rename", "symbol"),
            availability = HostCommandAvailability.LSP_REFACTOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F6, shift = true)
        ),
        command(
            id = HostCommands.EDITOR_SWITCH_HEADER_SOURCE,
            titleRes = Strings.cmd_editor_switch_header_source,
            category = HostCommandCategory.CODE,
            keywords = listOf("header", "source"),
            availability = HostCommandAvailability.HEADER_SOURCE_SWITCH,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_O, alt = true)
        ),
        command(
            id = HostCommands.EDITOR_TOGGLE_BOOKMARK,
            titleRes = Strings.menu_bookmark_toggle,
            category = HostCommandCategory.VIEW,
            keywords = listOf("bookmark"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F2, ctrl = true)
        ),
        command(
            id = HostCommands.EDITOR_NEXT_BOOKMARK,
            titleRes = Strings.menu_bookmark_next,
            category = HostCommandCategory.VIEW,
            keywords = listOf("bookmark", "next"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F2)
        ),
        command(
            id = HostCommands.EDITOR_PREVIOUS_BOOKMARK,
            titleRes = Strings.menu_bookmark_prev,
            category = HostCommandCategory.VIEW,
            keywords = listOf("bookmark", "previous"),
            availability = HostCommandAvailability.ACTIVE_EDITOR,
            defaultShortcut = shortcut(KeyEvent.KEYCODE_F2, shift = true)
        ),

        command(
            id = HostCommands.TERMINAL_TOGGLE,
            titleRes = Strings.cmd_terminal_toggle,
            category = HostCommandCategory.TERMINAL,
            keywords = listOf("terminal")
        ),
        command(
            id = HostCommands.TERMINAL_NEW,
            titleRes = Strings.cmd_terminal_new,
            category = HostCommandCategory.TERMINAL,
            keywords = listOf("terminal", "new")
        ),
        command(
            id = HostCommands.TERMINAL_OPEN_HERE,
            titleRes = Strings.cmd_terminal_open_here,
            category = HostCommandCategory.TERMINAL,
            keywords = listOf("terminal", "open here"),
            availability = HostCommandAvailability.ACTIVE_FILE
        ),
        command(
            id = HostCommands.TERMINAL_CLEAR,
            titleRes = Strings.action_clear,
            category = HostCommandCategory.TERMINAL,
            keywords = listOf("terminal", "clear")
        ),

        command(
            id = HostCommands.PROJECT_REFRESH,
            titleRes = Strings.menu_refresh,
            category = HostCommandCategory.BUILD,
            keywords = listOf("refresh")
        ),
        command(
            id = HostCommands.PROJECT_BUILD,
            titleRes = Strings.cmd_project_build,
            category = HostCommandCategory.BUILD,
            keywords = listOf("build"),
            availability = HostCommandAvailability.NOT_COMPILING
        ),
        command(
            id = HostCommands.PROJECT_RUN,
            titleRes = Strings.action_run,
            category = HostCommandCategory.BUILD,
            keywords = listOf("run"),
            availability = HostCommandAvailability.NOT_COMPILING
        ),
        command(
            id = HostCommands.PROJECT_SETTINGS,
            titleRes = Strings.cmd_project_settings,
            category = HostCommandCategory.BUILD,
            keywords = listOf("project settings")
        ),
        command(
            id = HostCommands.PROJECT_CLOSE,
            titleRes = Strings.menu_exit_workspace,
            category = HostCommandCategory.FILE,
            keywords = listOf("exit", "close workspace")
        ),

        command(
            id = HostCommands.VIEW_TOGGLE_FILE_TREE,
            titleRes = Strings.menu_explorer,
            category = HostCommandCategory.VIEW,
            keywords = listOf("explorer", "file tree"),
            surfaces = HostCommandDescriptor.defaultSurfaces + HostCommandSurface.TOP_BAR
        ),
        command(
            id = HostCommands.VIEW_TOGGLE_SYMBOLS,
            titleRes = Strings.cmd_view_toggle_symbols,
            category = HostCommandCategory.VIEW,
            keywords = listOf("symbols")
        ),
        command(
            id = HostCommands.VIEW_TOGGLE_TERMINAL,
            titleRes = Strings.menu_terminal,
            category = HostCommandCategory.VIEW,
            keywords = listOf("terminal"),
            surfaces = HostCommandDescriptor.defaultSurfaces + HostCommandSurface.TOP_BAR
        ),
        command(
            id = HostCommands.VIEW_COMMAND_PALETTE,
            titleRes = Strings.command_palette_title,
            category = HostCommandCategory.VIEW,
            keywords = listOf("command palette"),
            defaultShortcut = shortcut(KeyEvent.KEYCODE_P, ctrl = true, shift = true),
            surfaces = HostCommandDescriptor.defaultSurfaces + HostCommandSurface.KEYBOARD_SHORTCUT
        ),
        command(
            id = HostCommands.VIEW_BOOKMARKS,
            titleRes = Strings.menu_bookmark_list,
            category = HostCommandCategory.VIEW,
            keywords = listOf("bookmark", "list")
        ),
        command(
            id = HostCommands.VIEW_SETTINGS,
            titleRes = Strings.menu_settings,
            category = HostCommandCategory.VIEW,
            keywords = listOf("settings", "preferences"),
            surfaces = HostCommandDescriptor.defaultSurfaces + HostCommandSurface.TOP_BAR
        )
    )

    private val descriptorsById: Map<String, HostCommandDescriptor> =
        descriptors.associateBy { it.id }

    fun descriptorOrNull(commandId: String): HostCommandDescriptor? = descriptorsById[commandId.trim()]

    fun requireDescriptor(commandId: String): HostCommandDescriptor = descriptorOrNull(commandId)
        ?: error("Unknown host command: ${commandId.trim()}")

    fun isSupported(commandId: String): Boolean = descriptorOrNull(commandId) != null

    @StringRes
    fun titleResOrNull(commandId: String): Int? = descriptorOrNull(commandId)?.titleRes

    fun getAllCommandIds(): List<String> = descriptors.map(HostCommandDescriptor::id)

    fun getCommandsByCategory(category: String): List<String> {
        val prefix = "$category."
        return descriptors.map(HostCommandDescriptor::id).filter { it.startsWith(prefix) }
    }

    private fun command(
        id: String,
        @StringRes titleRes: Int,
        category: HostCommandCategory,
        keywords: List<String> = emptyList(),
        availability: HostCommandAvailability = HostCommandAvailability.ALWAYS,
        defaultShortcut: HostCommandShortcut? = null,
        surfaces: Set<HostCommandSurface> = HostCommandDescriptor.defaultSurfaces
    ): HostCommandDescriptor = HostCommandDescriptor(
        id = id,
        titleRes = titleRes,
        category = category,
        keywords = keywords,
        availability = availability,
        defaultShortcut = defaultShortcut,
        surfaces = if (defaultShortcut == null) {
            surfaces
        } else {
            surfaces + HostCommandSurface.KEYBOARD_SHORTCUT
        }
    )

    private fun shortcut(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false
    ): HostCommandShortcut = HostCommandShortcut(
        keyCode = keyCode,
        ctrl = ctrl,
        shift = shift,
        alt = alt
    )
}
