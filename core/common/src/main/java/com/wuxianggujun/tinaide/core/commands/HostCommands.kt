package com.wuxianggujun.tinaide.core.commands

import androidx.annotation.StringRes

object HostCommands {
    const val FILE_NEW: String = "file.new"
    const val FILE_NEW_FOLDER: String = "file.newFolder"
    const val FILE_RENAME: String = "file.rename"
    const val FILE_DELETE: String = "file.delete"
    const val FILE_COPY_PATH: String = "file.copyPath"
    const val FILE_COPY_NAME: String = "file.copyName"
    const val FILE_COPY_RELATIVE_PATH: String = "file.copyRelativePath"
    const val FILE_DUPLICATE: String = "file.duplicate"
    const val FILE_OPEN_WITH: String = "file.openWith"
    const val FILE_SHARE: String = "file.share"
    const val FILE_REVEAL_IN_FILE_MANAGER: String = "file.revealInFileManager"

    const val EDITOR_SAVE: String = "editor.save"
    const val EDITOR_SAVE_ALL: String = "editor.saveAll"
    const val EDITOR_CLOSE: String = "editor.close"
    const val EDITOR_CLOSE_ALL: String = "editor.closeAll"
    const val EDITOR_CLOSE_OTHERS: String = "editor.closeOthers"
    const val EDITOR_NEXT_TAB: String = "editor.nextTab"
    const val EDITOR_PREVIOUS_TAB: String = "editor.previousTab"
    const val EDITOR_UNDO: String = "editor.undo"
    const val EDITOR_REDO: String = "editor.redo"
    const val EDITOR_SELECT_ALL: String = "editor.selectAll"
    const val EDITOR_COPY: String = "editor.copy"
    const val EDITOR_CUT: String = "editor.cut"
    const val EDITOR_PASTE: String = "editor.paste"
    const val EDITOR_FIND: String = "editor.find"
    const val EDITOR_REPLACE: String = "editor.replace"
    const val EDITOR_GOTO_LINE: String = "editor.gotoLine"
    const val EDITOR_NAVIGATE_BACK: String = "editor.navigateBack"
    const val EDITOR_NAVIGATE_FORWARD: String = "editor.navigateForward"
    const val EDITOR_TOGGLE_WORD_WRAP: String = "editor.toggleWordWrap"
    const val EDITOR_FORMAT: String = "editor.format"
    const val EDITOR_TOGGLE_COMMENT: String = "editor.toggleComment"
    const val EDITOR_PEEK_DEFINITION: String = "editor.peekDefinition"
    const val EDITOR_GOTO_DEFINITION: String = "editor.gotoDefinition"
    const val EDITOR_FIND_REFERENCES: String = "editor.findReferences"
    const val EDITOR_GOTO_TYPE_DEFINITION: String = "editor.gotoTypeDefinition"
    const val EDITOR_GOTO_IMPLEMENTATION: String = "editor.gotoImplementation"
    const val EDITOR_CODE_ACTIONS: String = "editor.codeActions"
    const val EDITOR_RENAME_SYMBOL: String = "editor.renameSymbol"
    const val EDITOR_SWITCH_HEADER_SOURCE: String = "editor.switchHeaderSource"
    const val EDITOR_TOGGLE_BOOKMARK: String = "editor.toggleBookmark"
    const val EDITOR_NEXT_BOOKMARK: String = "editor.nextBookmark"
    const val EDITOR_PREVIOUS_BOOKMARK: String = "editor.previousBookmark"

    const val TERMINAL_TOGGLE: String = "terminal.toggle"
    const val TERMINAL_NEW: String = "terminal.new"
    const val TERMINAL_OPEN_HERE: String = "terminal.openHere"
    const val TERMINAL_CLEAR: String = "terminal.clear"

    const val PROJECT_REFRESH: String = "project.refresh"
    const val PROJECT_BUILD: String = "project.build"
    const val PROJECT_RUN: String = "project.run"
    const val PROJECT_SETTINGS: String = "project.settings"
    const val PROJECT_CLOSE: String = "project.close"

    const val VIEW_TOGGLE_FILE_TREE: String = "view.toggleFileTree"
    const val VIEW_TOGGLE_SYMBOLS: String = "view.toggleSymbols"
    const val VIEW_TOGGLE_TERMINAL: String = "view.toggleTerminal"
    const val VIEW_COMMAND_PALETTE: String = "view.commandPalette"
    const val VIEW_BOOKMARKS: String = "view.bookmarks"
    const val VIEW_SETTINGS: String = "view.settings"

    fun isSupported(commandId: String): Boolean = HostCommandCatalog.isSupported(commandId)

    @StringRes
    fun titleResOrNull(commandId: String): Int? = HostCommandCatalog.titleResOrNull(commandId)

    fun getAllCommandIds(): List<String> = HostCommandCatalog.getAllCommandIds()

    fun getCommandsByCategory(category: String): List<String> = HostCommandCatalog.getCommandsByCategory(category)
}
