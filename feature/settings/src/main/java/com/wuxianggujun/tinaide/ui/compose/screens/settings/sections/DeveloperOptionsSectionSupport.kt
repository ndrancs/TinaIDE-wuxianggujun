package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.network.server.ServerConfigResponse
import com.wuxianggujun.tinaide.core.i18n.Strings

internal data class DeveloperDiagnosticsControlsState(
    val lspCompileCommandsSelectionLogControlEnabled: Boolean,
    val lspClangdStartupLogControlEnabled: Boolean,
    val editorTouchDiagnosticsControlEnabled: Boolean,
    val gestureTraceControlEnabled: Boolean,
    val editorInternalTouchLogControlEnabled: Boolean,
    val editorScaleLogControlEnabled: Boolean,
    val editorFocusLogControlEnabled: Boolean,
    val editorScrollLogControlEnabled: Boolean,
    val editorFlingLogControlEnabled: Boolean
)

internal enum class DeveloperOptionsAction {
    DisableDeveloperOptions
}

internal enum class DeveloperServerUrlFeedback {
    TestSuccess,
    TestFailure,
    RestoredDefault
}

internal data class DeveloperServerUrlDialogState(
    val urlText: String,
    val feedback: DeveloperServerUrlFeedback? = null
)

internal enum class DeveloperBundledPackagesReinstallFeedback {
    Started,
    Success,
    Failure
}

internal data class DeveloperBundledPackagesReinstallState(
    val feedback: DeveloperBundledPackagesReinstallFeedback,
    val errorMessage: String? = null
)

internal data class DeveloperServerConfigPreview(
    val version: Long,
    val updatedAt: String?,
    val configRefreshIntervalSecs: Long,
    val feedbackEnabled: Boolean,
    val pluginMarketEnabled: Boolean,
    val packageManagerEnabled: Boolean,
    val developerOptionsEnabled: Boolean,
    val minClientVersion: String,
    val recommendedClientVersion: String,
    val forceUpdate: Boolean
)

internal data class DeveloperMessageSpec(
    @param:StringRes @get:StringRes val messageRes: Int,
    val formatArgs: List<Any?> = emptyList()
)

internal enum class DeveloperToastDuration {
    Short,
    Long
}

internal data class DeveloperToastSpec(
    val message: DeveloperMessageSpec,
    val duration: DeveloperToastDuration = DeveloperToastDuration.Short
)

internal data class DeveloperOptionsActionEffect(
    val disableDeveloperOptions: Boolean = false,
    val navigateBack: Boolean = false
)

internal object DeveloperOptionsSectionSupport {
    fun resolveDiagnosticsControlsState(
        diagnosticsEnabled: Boolean,
        editorTouchDiagnosticsEnabled: Boolean
    ): DeveloperDiagnosticsControlsState {
        val diagnosticsControlsEnabled = diagnosticsEnabled
        val touchSubControlsEnabled = diagnosticsEnabled && editorTouchDiagnosticsEnabled
        return DeveloperDiagnosticsControlsState(
            lspCompileCommandsSelectionLogControlEnabled = diagnosticsControlsEnabled,
            lspClangdStartupLogControlEnabled = diagnosticsControlsEnabled,
            editorTouchDiagnosticsControlEnabled = diagnosticsControlsEnabled,
            gestureTraceControlEnabled = diagnosticsControlsEnabled,
            editorInternalTouchLogControlEnabled = touchSubControlsEnabled,
            editorScaleLogControlEnabled = touchSubControlsEnabled,
            editorFocusLogControlEnabled = touchSubControlsEnabled,
            editorScrollLogControlEnabled = touchSubControlsEnabled,
            editorFlingLogControlEnabled = touchSubControlsEnabled
        )
    }

    fun createServerUrlDialogState(currentServerUrl: String): DeveloperServerUrlDialogState = DeveloperServerUrlDialogState(
        urlText = currentServerUrl,
        feedback = null
    )

    fun normalizeServerUrlInput(input: String): String? = input.trim().ifBlank { null }?.trimEnd('/')

    fun resolveServerUrlSaveResult(
        persistedServerUrl: String,
        connectionOk: Boolean
    ): DeveloperServerUrlDialogState = DeveloperServerUrlDialogState(
        urlText = persistedServerUrl,
        feedback = if (connectionOk) {
            DeveloperServerUrlFeedback.TestSuccess
        } else {
            DeveloperServerUrlFeedback.TestFailure
        }
    )

    fun resolveServerUrlRestoreResult(persistedServerUrl: String): DeveloperServerUrlDialogState = DeveloperServerUrlDialogState(
        urlText = persistedServerUrl,
        feedback = DeveloperServerUrlFeedback.RestoredDefault
    )

    fun resolveServerUrlFeedbackMessage(
        feedback: DeveloperServerUrlFeedback?
    ): DeveloperMessageSpec? = when (feedback) {
        DeveloperServerUrlFeedback.TestSuccess -> {
            DeveloperMessageSpec(Strings.toast_tina_server_test_ok)
        }

        DeveloperServerUrlFeedback.TestFailure -> {
            DeveloperMessageSpec(Strings.toast_tina_server_test_failed)
        }

        DeveloperServerUrlFeedback.RestoredDefault -> {
            DeveloperMessageSpec(Strings.toast_tina_server_restored_default)
        }

        null -> null
    }

    fun createBundledPackagesReinstallStartedState(): DeveloperBundledPackagesReinstallState = DeveloperBundledPackagesReinstallState(
        feedback = DeveloperBundledPackagesReinstallFeedback.Started
    )

    fun resolveBundledPackagesReinstallResult(
        errorMessage: String?
    ): DeveloperBundledPackagesReinstallState {
        val normalizedErrorMessage = errorMessage?.takeUnless { it.isBlank() }
        return if (normalizedErrorMessage == null) {
            DeveloperBundledPackagesReinstallState(
                feedback = DeveloperBundledPackagesReinstallFeedback.Success
            )
        } else {
            DeveloperBundledPackagesReinstallState(
                feedback = DeveloperBundledPackagesReinstallFeedback.Failure,
                errorMessage = normalizedErrorMessage
            )
        }
    }

    fun resolveBundledPackagesReinstallToast(
        state: DeveloperBundledPackagesReinstallState,
        unknownErrorMessage: String,
        useLongDuration: Boolean = false
    ): DeveloperToastSpec = when (state.feedback) {
        DeveloperBundledPackagesReinstallFeedback.Started -> DeveloperToastSpec(
            message = DeveloperMessageSpec(Strings.toast_reinstalling_bundled_packages)
        )

        DeveloperBundledPackagesReinstallFeedback.Success -> DeveloperToastSpec(
            message = DeveloperMessageSpec(Strings.toast_reinstall_bundled_packages_success)
        )

        DeveloperBundledPackagesReinstallFeedback.Failure -> DeveloperToastSpec(
            message = DeveloperMessageSpec(
                messageRes = Strings.toast_reinstall_bundled_packages_failed,
                formatArgs = listOf(state.errorMessage ?: unknownErrorMessage)
            ),
            duration = if (useLongDuration) {
                DeveloperToastDuration.Long
            } else {
                DeveloperToastDuration.Short
            }
        )
    }

    fun resolveServerConfigPreview(config: ServerConfigResponse): DeveloperServerConfigPreview = DeveloperServerConfigPreview(
        version = config.version,
        updatedAt = config.updatedAt?.takeUnless { it.isBlank() },
        configRefreshIntervalSecs = config.configRefreshIntervalSecs,
        feedbackEnabled = config.features.feedbackEnabled,
        pluginMarketEnabled = config.features.pluginMarketEnabled,
        packageManagerEnabled = config.features.packageManagerEnabled,
        developerOptionsEnabled = config.features.developerOptionsEnabled,
        minClientVersion = config.client.minClientVersion,
        recommendedClientVersion = config.client.recommendedClientVersion,
        forceUpdate = config.client.forceUpdate
    )

    fun resolveActionEffect(action: DeveloperOptionsAction): DeveloperOptionsActionEffect = when (action) {
        DeveloperOptionsAction.DisableDeveloperOptions -> DeveloperOptionsActionEffect(
            disableDeveloperOptions = true,
            navigateBack = true
        )
    }
}
