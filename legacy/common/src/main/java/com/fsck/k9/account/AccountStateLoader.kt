package com.fsck.k9.account

import app.k9mail.core.common.mail.Protocols
import app.k9mail.feature.account.common.AccountCommonExternalContract
import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.common.domain.entity.AuthorizationState
import com.fsck.k9.Account
import com.fsck.k9.backends.toImapServerSettings
import com.fsck.k9.logging.Timber
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.preferences.AccountManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fsck.k9.Account as K9Account

class AccountStateLoader(
    private val accountManager: AccountManager,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountCommonExternalContract.AccountStateLoader {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadAccountState(accountUuid: String): AccountState? {
        return try {
            withContext(coroutineDispatcher) {
                load(accountUuid)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error while loading account")

            null
        }
    }

    private fun load(accountUuid: String): AccountState? {
        return accountManager.getAccount(accountUuid)?.let { mapToAccountState(it) }
    }

    private fun mapToAccountState(account: K9Account): AccountState {
        return AccountState(
            uuid = account.uuid,
            emailAddress = account.email,
            incomingServerSettings = account.incomingServerSettingsExtra,
            outgoingServerSettings = account.outgoingServerSettings,
            authorizationState = AuthorizationState(account.oAuthState),
        )
    }
}

private val Account.incomingServerSettingsExtra: ServerSettings
    get() = when (incomingServerSettings.type) {
        Protocols.IMAP -> toImapServerSettings()
        else -> incomingServerSettings
    }
