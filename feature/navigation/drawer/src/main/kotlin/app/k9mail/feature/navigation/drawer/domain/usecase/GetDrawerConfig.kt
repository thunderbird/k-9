package app.k9mail.feature.navigation.drawer.domain.usecase

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfig
import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfigLoader
import app.k9mail.feature.navigation.drawer.domain.DomainContract.UseCase
import kotlinx.coroutines.flow.Flow

internal class GetDrawerConfig(
    private val configProver: DrawerConfigLoader,
) : UseCase.GetDrawerConfig {
    override operator fun invoke(): Flow<DrawerConfig> {
        return configProver.loadDrawerConfigFlow()
    }
}
