package com.fsck.k9.view

import com.fsck.k9.K9
import com.fsck.k9.ui.base.Theme
import com.fsck.k9.ui.base.ThemeManager

class WebViewConfigProvider(private val themeManager: ThemeManager) {
    fun createForMessageView() = createWebViewConfig(themeManager.messageViewTheme)

    fun createForMessageCompose() = createWebViewConfig(themeManager.messageComposeTheme)

    private fun createWebViewConfig(theme: Theme): WebViewConfig {
        return WebViewConfig(
            useDarkMode = theme == Theme.DARK,
            autoFitWidth = K9.isAutoFitWidth,
            textZoom = K9.fontSizes.messageViewContentAsPercent,
        )
    }
}
