package com.codex.remote

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Process-wide owner for state that must survive Activity recreation and remain
 * available while the foreground SSH service keeps the process alive.
 */
class CodexRemoteApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    val appViewModel: AppViewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(this),
        )[AppViewModel::class.java]
    }
}
