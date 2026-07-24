package com.freqcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Builds a [ViewModelProvider.Factory] around a constructor lambda - shared by every ViewModel's `provideFactory`. */
fun <T : ViewModel> viewModelFactory(factory: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = factory() as VM
    }
