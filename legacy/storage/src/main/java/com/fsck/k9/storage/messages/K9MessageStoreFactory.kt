package com.fsck.k9.storage.messages

import com.fsck.k9.Account
import com.fsck.k9.mailstore.ListenableMessageStore
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.MessageStoreFactory
import com.fsck.k9.mailstore.NotifierMessageStore
import com.fsck.k9.mailstore.StorageManager
import com.fsck.k9.message.extractors.BasicPartInfoExtractor

class K9MessageStoreFactory(
    private val localStoreProvider: LocalStoreProvider,
    private val storageManager: StorageManager,
    private val basicPartInfoExtractor: BasicPartInfoExtractor,
) : MessageStoreFactory {
    override fun create(account: Account): ListenableMessageStore {
        val localStore = localStoreProvider.getInstance(account)
        val messageStore = K9MessageStore(localStore.database, storageManager, basicPartInfoExtractor, account.uuid)
        val notifierMessageStore = NotifierMessageStore(messageStore, localStore)
        return ListenableMessageStore(notifierMessageStore)
    }
}
