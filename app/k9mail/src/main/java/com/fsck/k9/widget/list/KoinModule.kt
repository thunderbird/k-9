package com.fsck.k9.widget.list

import org.koin.dsl.module.module


val messageListWidgetModule = module {
    single { MessageListWidgetUpdateListener(get()) }
}
