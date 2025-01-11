package app.k9mail.legacy.preferences

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfig
import kotlinx.coroutines.flow.Flow

interface DrawerConfigManager {
    fun save(config: DrawerConfig)
    fun getDrawerConfig(): DrawerConfig
    fun getDrawerConfigFlow(): Flow<DrawerConfig>
}
