package com.fsck.k9.feature

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract
import app.k9mail.legacy.preferences.DrawerConfigManager

class NavigationDrawerConfigWriter(
    private val drawerConfigManager: DrawerConfigManager,
) : NavigationDrawerExternalContract.DrawerConfigWriter {
    override fun writeDrawerConfig(drawerConfig: NavigationDrawerExternalContract.DrawerConfig) {
        drawerConfigManager.save(drawerConfig)
    }
}
