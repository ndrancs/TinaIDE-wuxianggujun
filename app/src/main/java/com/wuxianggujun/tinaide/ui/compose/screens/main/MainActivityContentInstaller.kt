package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme

internal fun installMainActivityContent(
    activity: ComponentActivity,
    viewModels: MainActivityContentViewModels,
    services: MainActivityContentServices,
    bridges: MainActivityContentBridges,
    delegates: MainActivityContentDelegates,
    externalFileActions: MainActivityExternalFileActions,
) {
    activity.setContent {
        TinaIDETheme {
            MainActivityScreenHost(
                activity = activity,
                lifecycleScope = activity.lifecycleScope,
                viewModels = viewModels,
                services = services,
                bridges = bridges,
                delegates = delegates,
                externalFileActions = externalFileActions,
            )
        }
    }
}
