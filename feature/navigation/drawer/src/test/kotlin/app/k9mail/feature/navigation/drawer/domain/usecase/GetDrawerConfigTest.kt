package app.k9mail.feature.navigation.drawer.domain.usecase

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfig
import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfigLoader
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

internal class GetDrawerConfigTest {

    @Test
    fun `should get drawer config`() = runTest {
        val configProver: DrawerConfigLoader = mock()
        val drawerConfig = DrawerConfig(
            showUnifiedFolders = true,
            showStarredCount = true,
            showAccountSelector = true,
        )

        val testSubject = GetDrawerConfig(configProver = configProver)
        whenever(configProver.loadDrawerConfigFlow()).thenReturn(flowOf(drawerConfig))

        val result = testSubject().first()

        assertThat(result).isEqualTo(drawerConfig)
    }
}
