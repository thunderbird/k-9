package app.k9mail.feature.navigation.drawer

import kotlinx.coroutines.flow.Flow

interface NavigationDrawerExternalContract {

    data class DrawerConfig(
        val showUnifiedFolders: Boolean,
        val showStarredCount: Boolean,
        val showAccountSelector: Boolean,
    )

    fun interface DrawerConfigLoader {
        fun loadDrawerConfigFlow(): Flow<DrawerConfig>
    }

    fun interface DrawerConfigWriter {
        fun writeDrawerConfig(drawerConfig: DrawerConfig)
    }
}
