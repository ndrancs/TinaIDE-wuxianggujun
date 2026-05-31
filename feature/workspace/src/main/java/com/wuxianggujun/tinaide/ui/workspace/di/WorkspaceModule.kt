package com.wuxianggujun.tinaide.ui.workspace.di

import com.wuxianggujun.tinaide.core.proot.ToolchainConfig
import com.wuxianggujun.tinaide.ui.workspace.DependencyInstallViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val workspaceModule = module {
    viewModel { (tc: ToolchainConfig, llvm: Int?, installLinuxEnv: Boolean) ->
        DependencyInstallViewModel(get(), get(), get(), tc, llvm, installLinuxEnv)
    }
}
