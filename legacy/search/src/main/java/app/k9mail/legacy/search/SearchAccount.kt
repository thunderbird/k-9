package app.k9mail.legacy.search

import app.k9mail.legacy.account.Account
import app.k9mail.legacy.account.BaseAccount
import app.k9mail.legacy.search.api.SearchAttribute
import app.k9mail.legacy.search.api.SearchField

/**
 * This class is basically a wrapper around a LocalSearch. It allows to expose it as an account.
 * This is a meta-account containing all the messages that match the search.
 */
class SearchAccount(
    val id: String,
    search: LocalSearch,
    override val name: String,
    override val email: String,
) : BaseAccount {
    /**
     * Returns the ID of this `SearchAccount` instance.
     *
     * This isn't really a UUID. But since we don't expose this value to other apps and we only use the account UUID
     * as opaque string (e.g. as key in a `Map`) we're fine.
     *
     * Using a constant string is necessary to identify the same search account even when the corresponding
     * [SearchAccount] object has been recreated.
     */
    override val uuid: String = id

    val relatedSearch: LocalSearch = search

    companion object {
        const val UNIFIED_INBOX = "unified_inbox"
        const val NEW_MESSAGES = "new_messages"

        @JvmStatic
        fun createUnifiedInboxAccount(
            unifiedInboxTitle: String,
            unifiedInboxDetail: String,
            accounts: List<Account>,
        ): SearchAccount {
            val tmpSearch = LocalSearch().apply {
                id = UNIFIED_INBOX
                addAccountUuids(accounts.filter { it.isShowInUnifiedInbox }.map { it.uuid }.toTypedArray())
                and(SearchField.INTEGRATE, "1", SearchAttribute.EQUALS)
            }

            return SearchAccount(
                id = UNIFIED_INBOX,
                search = tmpSearch,
                name = unifiedInboxTitle,
                email = unifiedInboxDetail,
            )
        }
    }
}
