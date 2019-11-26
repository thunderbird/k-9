package com.fsck.k9.message.html

import org.koin.dsl.module.applicationContext

val htmlModule = applicationContext {
    bean { HtmlProcessor(get(), get()) }
    bean { HtmlSanitizer() }
    bean { DisplayHtmlFactory() }
}
