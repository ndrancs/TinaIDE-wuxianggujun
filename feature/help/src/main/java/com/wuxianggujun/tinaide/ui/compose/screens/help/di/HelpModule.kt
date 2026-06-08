package com.wuxianggujun.tinaide.ui.compose.screens.help.di

import com.wuxianggujun.tinaide.data.repository.FeedbackRepository
import com.wuxianggujun.tinaide.ui.compose.screens.help.HelpViewModel
import com.wuxianggujun.tinaide.ui.compose.viewmodel.FeedbackViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val helpModule = module {
    factory { FeedbackRepository(get()) }
    viewModel { FeedbackViewModel(get(), get()) }
    viewModel { HelpViewModel(androidApplication()) }
}
