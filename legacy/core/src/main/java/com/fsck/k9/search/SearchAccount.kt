package com.fsck.k9.search

import com.fsck.k9.BaseAccount
import com.fsck.k9.CoreResourceProvider
import com.fsck.k9.search.SearchSpecification.SearchField
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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

    companion object : KoinComponent {
        private val resourceProvider: CoreResourceProvider by inject()

        const val UNIFIED_INBOX = "unified_inbox"
        const val NEW_MESSAGES = "new_messages"

        @JvmStatic
        fun createUnifiedInboxAccount(): SearchAccount {
            val tmpSearch = LocalSearch().apply {
                id = UNIFIED_INBOX
                and(SearchField.INTEGRATE, "1", SearchSpecification.Attribute.EQUALS)
            }

            return SearchAccount(
                id = UNIFIED_INBOX,
                search = tmpSearch,
                name = resourceProvider.searchUnifiedInboxTitle(),
                email = resourceProvider.searchUnifiedInboxDetail(),
            )
        }
    }
}
