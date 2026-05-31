package com.wuxianggujun.tinaide.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.wuxianggujun.tinaide.MainActivity
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.startup.StartupFlowManager
import com.wuxianggujun.tinaide.storage.ProjectLocationManager
import com.wuxianggujun.tinaide.storage.StorageManager
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 主入口 Activity
 *
 * 管理底部导航和四个主要模块的切换：
 * - 项目：项目管理、项目列表
 * - 市场：插件市场、包管理、代码片段
 * - 教程：教程列表、学习进度
 * - 我的：用户信息、设置、我的内容
 *
 * 当用户打开项目后，跳转到 MainActivity 进行编辑
 */
class MainPortalActivity :
    ComponentActivity(),
    KoinComponent {

    override fun onStart() {
        super.onStart()
        // 用户一旦回到主页即归零项目会话内存态（FileWatcher 同步移除），
        // 保证从主页进入的设置页/插件/AI 工具等拿到 null，而不是被上次会话污染。
        // 偏好键 ConfigKeys.CurrentProject 保留，进入 MainActivity 时再通过
        // projectSession.restoreLastSession() 恢复。
        val projectSession: IProjectSession = get()
        projectSession.clearInMemorySession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        Prefs.applyTheme()
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        enableEdgeToEdge()

        // === 启动流程检查 ===
        // 检查是否需要引导（工具链安装/工作空间配置），如需要则跳转到对应页面并结束当前 Activity
        val configManager: IConfigManager = get()
        val startupFlowManager = StartupFlowManager(this, configManager)
        val redirectIntent = startupFlowManager.checkStartupFlow()
        if (redirectIntent != null) {
            startActivity(redirectIntent)
            finish()
            return
        }

        setContent {
            TinaIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainPortalScreen(
                        onOpenProject = { projectPath ->
                            openProjectEditor(projectPath)
                        },
                        onNavigateToSettings = {
                            SettingsActivity.start(this)
                        },
                        onNavigateToFeedback = {
                            SettingsActivity.start(this, SettingsRoute.Feedback)
                        },
                        onNavigateToAbout = {
                            SettingsActivity.start(this, SettingsRoute.About)
                        },
                        onNavigateToPlugins = {
                            SettingsActivity.start(this, SettingsRoute.Plugins)
                        },
                        onNavigateToPackages = {
                            SettingsActivity.start(this, SettingsRoute.Packages)
                        },
                        onNavigateToFavorites = {
                            startActivity(
                                Intent(this, UserContentActivity::class.java).apply {
                                    putExtra(UserContentActivity.EXTRA_CONTENT_TYPE, UserContentActivity.TYPE_FAVORITES)
                                }
                            )
                        },
                        onNavigateToDownloadHistory = {
                            startActivity(
                                Intent(this, UserContentActivity::class.java).apply {
                                    putExtra(UserContentActivity.EXTRA_CONTENT_TYPE, UserContentActivity.TYPE_DOWNLOAD_HISTORY)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    private fun openProjectEditor(projectPath: String) {
        val projectSession: IProjectSession = get()
        val projectLocationManager: ProjectLocationManager = get()
        val storageManager: StorageManager = get()

        try {
            val projectDir = if (projectPath.isNotEmpty()) File(projectPath) else null
            if (projectDir != null && projectDir.exists()) {
                val access = storageManager.checkProjectDirAccess(projectDir)
                if (!access.canAccess) {
                    toastError((access.failureMessageResId ?: Strings.toast_open_failed).strOr(this))
                    return
                }
                runCatching { projectLocationManager.registerProject(projectDir) }
                // 打开项目
                projectSession.openProject(projectPath)
                // 跳转到编辑器
                startActivity(Intent(this, MainActivity::class.java))
            }
            // 如果没有指定项目路径，留在当前界面（底部导航项目页）
        } catch (e: Exception) {
            // 打开失败，留在当前界面
            toastError(e.message ?: Strings.toast_open_failed.strOr(this))
        }
    }
}

/**
 * 主入口屏幕
 *
 * 包含底部导航和模块切换
 */
@Composable
fun MainPortalScreen(
    onOpenProject: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFeedback: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToPackages: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToDownloadHistory: () -> Unit = {},
) {
    MainScreen(
        onOpenProject = onOpenProject,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToFeedback = onNavigateToFeedback,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToPlugins = onNavigateToPlugins,
        onNavigateToPackages = onNavigateToPackages,
        onNavigateToFavorites = onNavigateToFavorites,
        onNavigateToDownloadHistory = onNavigateToDownloadHistory,
    )
}
