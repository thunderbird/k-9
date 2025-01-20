package com.fsck.k9.preferences

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfig
import app.k9mail.legacy.preferences.DrawerConfigManager
import com.fsck.k9.K9
import com.fsck.k9.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class RealDrawerConfigManager(
    private val preferences: Preferences,
    private val coroutineScope: CoroutineScope,
) : DrawerConfigManager {
    private val drawerConfigFlow = MutableSharedFlow<DrawerConfig>(replay = 1)
    private var drawerConfig: DrawerConfig? = null

    override fun save(config: DrawerConfig) {
        saveDrawerConfig(config)
        updateDrawerConfigFlow(config)
    }

    private fun loadDrawerConfig(): DrawerConfig {
        val drawerConfig = DrawerConfig(
            showAccountSelector = K9.isShowAccountSelector,
            showStarredCount = K9.isShowStarredCount,
            showUnifiedFolders = K9.isShowUnifiedInbox,
        )

        updateDrawerConfigFlow(drawerConfig)

        return drawerConfig
    }

    private fun updateDrawerConfigFlow(config: DrawerConfig) {
        coroutineScope.launch {
            drawerConfigFlow.emit(config)
        }
    }

    @Synchronized
    override fun getDrawerConfig(): DrawerConfig {
        return drawerConfig ?: loadDrawerConfig().also { drawerConfig = it }
    }

    override fun getDrawerConfigFlow(): Flow<DrawerConfig> {
        getDrawerConfig()
        return drawerConfigFlow.distinctUntilChanged()
    }

    @Synchronized
    private fun saveDrawerConfig(config: DrawerConfig) {
        val editor = preferences.createStorageEditor()
        K9.save(editor)
        writeDrawerConfig(editor, config)
        editor.commit()
    }

    private fun writeDrawerConfig(editor: StorageEditor, config: DrawerConfig) {
        editor.putBoolean("showAccountSelector", config.showAccountSelector)
        editor.putBoolean("showUnifiedInbox", config.showUnifiedFolders)
        editor.putBoolean("showStarredCount", config.showStarredCount)
    }
}
