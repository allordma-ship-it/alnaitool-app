package com.aurastream.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurastream.tv.AppContainer
import com.aurastream.tv.AuraApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as AuraApp).container

/**
 * Creates a ViewModel scoped to the current NavBackStackEntry with access to the
 * [AppContainer] and the entry's [SavedStateHandle] (navigation arguments).
 */
@Composable
inline fun <reified VM : ViewModel> auraViewModel(
    key: String? = null,
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM {
    val container = appContainer()
    return viewModel(key = key) { create(container, createSavedStateHandle()) }
}
