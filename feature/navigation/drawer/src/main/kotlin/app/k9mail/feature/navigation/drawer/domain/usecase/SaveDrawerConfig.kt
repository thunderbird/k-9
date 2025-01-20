package app.k9mail.feature.navigation.drawer.domain.usecase

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract
import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfigWriter
import app.k9mail.feature.navigation.drawer.domain.DomainContract.UseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class SaveDrawerConfig(
    private val drawerConfigWriter: DrawerConfigWriter,
) : UseCase.SaveDrawerConfig {
    override fun invoke(drawerConfig: NavigationDrawerExternalContract.DrawerConfig): Flow<Unit> {
        return flow {
            emit(drawerConfigWriter.writeDrawerConfig(drawerConfig))
        }
    }
}
