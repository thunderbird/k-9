package com.fsck.k9.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.fsck.k9.Account
import com.fsck.k9.preferences.AccountManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsViewModel(
    private val accountManager: AccountManager,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val accounts = accountManager.getAccountsFlow().asLiveData()

    fun moveAccount(account: Account, newPosition: Int) {
        viewModelScope.launch(coroutineDispatcher) {
            // Delay saving the account so the animation is not disturbed
            delay(500)

            withContext(NonCancellable) {
                accountManager.moveAccount(account, newPosition)
            }
        }
    }
}
