package app.k9mail.feature.account.oauth.ui.preview

import app.k9mail.core.ui.compose.common.mvi.BaseViewModel
import app.k9mail.feature.account.oauth.ui.AccountOAuthContract

/**
 * Only for previewing the UI.
 */
class PreviewAccountOAuthViewModel :
    BaseViewModel<AccountOAuthContract.State, AccountOAuthContract.Event, AccountOAuthContract.Effect>(
        AccountOAuthContract.State(),
    ),
    AccountOAuthContract.ViewModel {

    override fun initState(state: AccountOAuthContract.State) = Unit

    override fun event(event: AccountOAuthContract.Event) = Unit
}
