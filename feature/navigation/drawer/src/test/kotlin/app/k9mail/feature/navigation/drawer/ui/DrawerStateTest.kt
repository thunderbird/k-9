package app.k9mail.feature.navigation.drawer.ui

import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfig
import app.k9mail.feature.navigation.drawer.ui.DrawerContract.State
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

internal class DrawerStateTest {

    @Test
    fun `should set default values`() {
        val state = State()

        assertThat(state).isEqualTo(
            State(
                config = DrawerConfig(
                    showUnifiedFolders = false,
                    showStarredCount = false,
                    showAccountSelector = true,
                ),
                accounts = persistentListOf(),
                selectedAccountId = null,
                folders = persistentListOf(),
                selectedFolderId = null,
                isLoading = false,
            ),
        )
    }
}
