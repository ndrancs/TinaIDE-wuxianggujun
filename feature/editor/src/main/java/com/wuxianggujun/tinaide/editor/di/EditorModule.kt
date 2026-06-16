package com.wuxianggujun.tinaide.editor.di

import com.wuxianggujun.tinaide.core.editor.IBookmarkRepository
import com.wuxianggujun.tinaide.core.symbol.IProjectSymbolIndexService
import com.wuxianggujun.tinaide.editor.EditorManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.bookmark.BookmarkRepository
import com.wuxianggujun.tinaide.editor.bookmark.BookmarkRepositoryAdapter
import com.wuxianggujun.tinaide.editor.bookmark.BookmarkService
import com.wuxianggujun.tinaide.editor.symbol.CxxSymbolProvider
import com.wuxianggujun.tinaide.editor.symbol.JavaSymbolProvider
import com.wuxianggujun.tinaide.editor.symbol.KotlinSymbolProvider
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.editor.symbol.PythonSymbolProvider
import com.wuxianggujun.tinaide.editor.symbol.RustSymbolProvider
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.plugin.EditorThemeIndex
import org.koin.dsl.module

val editorModule = module {
    single { CxxSymbolProvider() }
    single { JavaSymbolProvider() }
    single { KotlinSymbolProvider() }
    single { PythonSymbolProvider() }
    single { RustSymbolProvider() }

    single<IProjectSymbolIndexService> {
        ProjectSymbolIndexService(
            context = get(),
            providers = listOf(
                get<CxxSymbolProvider>(),
                get<JavaSymbolProvider>(),
                get<KotlinSymbolProvider>(),
                get<PythonSymbolProvider>(),
                get<RustSymbolProvider>(),
            ),
        )
    }

    // BookmarkRepository（feature:editor 层接口）
    single<BookmarkRepository> { BookmarkService(get()) }

    // IBookmarkRepository（core:common 层接口）
    single<IBookmarkRepository> { BookmarkRepositoryAdapter(get()) }

    single { PluginEditorThemeRegistry(get(), get()).also { it.onCreate() } }
    single<EditorThemeIndex> { get<PluginEditorThemeRegistry>() }
    single<IEditorManager> {
        EditorManager(
            context = get(),
            configManager = get(),
            projectContextProvider = { getKoin().getOrNull<IProjectContext>() },
            projectSymbolIndexServiceProvider = { getKoin().getOrNull() },
        ).also { it.onCreate() }
    }
}
