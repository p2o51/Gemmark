package com.gemmark.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemmark.app.GemmarkApplication
import com.gemmark.app.di.AppContainer

/**
 * Creates a screen ViewModel with the app container injected.
 *
 * Deliberately avoids CreationExtras.APPLICATION_KEY: with Navigation 3's
 * per-entry ViewModelStore decorator the extras of a nav entry do not carry
 * the application, so the classic AndroidViewModelFactory pattern NPEs.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    noinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as GemmarkApplication
    return viewModel(key = key) { create(app.container) }
}
