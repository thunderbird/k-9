package app.k9mail.feature.navigation.drawer.ui

import app.k9mail.core.mail.folder.api.Folder
import app.k9mail.core.mail.folder.api.FolderType
import app.k9mail.core.ui.compose.testing.MainDispatcherRule
import app.k9mail.core.ui.compose.testing.mvi.advanceUntilIdle
import app.k9mail.core.ui.compose.testing.mvi.assertThatAndEffectTurbineConsumed
import app.k9mail.core.ui.compose.testing.mvi.runMviTest
import app.k9mail.core.ui.compose.testing.mvi.turbinesWithInitialStateCheck
import app.k9mail.feature.navigation.drawer.NavigationDrawerExternalContract.DrawerConfig
import app.k9mail.feature.navigation.drawer.domain.entity.DisplayAccount
import app.k9mail.feature.navigation.drawer.domain.entity.DisplayAccountFolder
import app.k9mail.feature.navigation.drawer.domain.entity.DisplayFolder
import app.k9mail.feature.navigation.drawer.domain.entity.DisplayUnifiedFolder
import app.k9mail.feature.navigation.drawer.domain.entity.DisplayUnifiedFolderType
import app.k9mail.feature.navigation.drawer.ui.DrawerContract.Effect
import app.k9mail.feature.navigation.drawer.ui.DrawerContract.Event
import app.k9mail.feature.navigation.drawer.ui.DrawerContract.State
import app.k9mail.feature.navigation.drawer.ui.FakeData.DISPLAY_ACCOUNT
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
internal class DrawerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `should collect drawer config`() = runTest {
        val drawerConfig = createDrawerConfig()
        val getDrawerConfigFlow = MutableStateFlow(drawerConfig)
        val testSubject = createTestSubject(
            drawerConfigFlow = getDrawerConfigFlow,
        )

        advanceUntilIdle()

        assertThat(testSubject.state.value.config).isEqualTo(drawerConfig)

        val newDrawerConfig = createDrawerConfig(showUnifiedInbox = true)

        getDrawerConfigFlow.emit(newDrawerConfig)

        advanceUntilIdle()

        assertThat(testSubject.state.value.config).isEqualTo(newDrawerConfig)
    }

    @Test
    fun `should change loading state when OnSyncAccount event is received`() = runMviTest {
        val initialState = State(
            accounts = listOf(DISPLAY_ACCOUNT).toImmutableList(),
            selectedAccountId = DISPLAY_ACCOUNT.id,
        )
        val testSubject = createTestSubject(
            initialState = initialState,
            displayAccountsFlow = flow { emit(listOf(DISPLAY_ACCOUNT)) },
            syncAccountFlow = flow {
                delay(25)
                emit(Result.success(Unit))
            },
        )
        val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

        testSubject.event(Event.OnSyncAccount)

        assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(initialState.copy(isLoading = true))
        assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(initialState.copy(isLoading = false))
    }

    @Test
    fun `should skip loading when no account is selected and OnSyncAccount event is received`() = runMviTest {
        val initialState = State(selectedAccountId = null)
        var counter = 0
        val testSubject = createTestSubject(
            initialState = initialState,
            syncAccountFlow = flow {
                delay(25)
                counter++
                emit(Result.success(Unit))
            },
        )

        val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

        testSubject.event(Event.OnSyncAccount)

        advanceUntilIdle()

        assertThat(testSubject.state.value.isLoading).isEqualTo(false)
        assertThat(counter).isEqualTo(0)

        turbines.stateTurbine.ensureAllEventsConsumed()
        turbines.effectTurbine.ensureAllEventsConsumed()
    }

    @Test
    fun `should skip loading when already loading and OnSyncAccount event received`() = runMviTest {
        val initialState = State(isLoading = true)
        var counter = 0
        val testSubject = createTestSubject(
            initialState = initialState,
            syncAccountFlow = flow {
                counter++
                delay(25)
                emit(Result.success(Unit))
            },
        )
        val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

        testSubject.event(Event.OnSyncAccount)

        advanceUntilIdle()

        assertThat(testSubject.state.value.isLoading).isEqualTo(true)
        assertThat(counter).isEqualTo(0)

        turbines.stateTurbine.ensureAllEventsConsumed()
        turbines.effectTurbine.ensureAllEventsConsumed()
    }

    @Test
    fun `should change loading state when OnSyncAllAccounts event is received`() = runMviTest {
        val testSubject = createTestSubject(
            syncAllAccounts = flow {
                delay(25)
                emit(Result.success(Unit))
            },
        )
        val initialState = State(isLoading = false)
        val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

        testSubject.event(Event.OnSyncAllAccounts)

        assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(State(isLoading = true))
        assertThat(turbines.stateTurbine.awaitItem()).isEqualTo(State(isLoading = false))
    }

    @Test
    fun `should skip loading when already loading and OnSyncAllAccounts event received`() = runMviTest {
        val initialState = State(isLoading = true)
        var counter = 0
        val testSubject = createTestSubject(
            initialState = initialState,
            syncAccountFlow = flow {
                counter++
                delay(25)
                emit(Result.success(Unit))
            },
        )
        val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

        testSubject.event(Event.OnSyncAllAccounts)

        advanceUntilIdle()

        assertThat(testSubject.state.value.isLoading).isEqualTo(true)
        assertThat(counter).isEqualTo(0)

        turbines.stateTurbine.ensureAllEventsConsumed()
        turbines.effectTurbine.ensureAllEventsConsumed()
    }

    @Test
    fun `should collect display accounts when created and select first as selected`() = runTest {
        val displayAccounts = createDisplayAccountList(2)
        val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
        )

        advanceUntilIdle()

        assertThat(testSubject.state.value.accounts.size).isEqualTo(displayAccounts.size)
        assertThat(testSubject.state.value.accounts).isEqualTo(displayAccounts)
        assertThat(testSubject.state.value.selectedAccountId).isEqualTo(displayAccounts.first().id)
    }

    @Test
    fun `should reselect selected account when old not present anymore`() = runTest {
        val displayAccounts = createDisplayAccountList(3)
        val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
        )

        advanceUntilIdle()

        val newDisplayAccounts = displayAccounts.drop(1)
        getDisplayAccountsFlow.emit(newDisplayAccounts)

        advanceUntilIdle()

        assertThat(testSubject.state.value.accounts.size).isEqualTo(newDisplayAccounts.size)
        assertThat(testSubject.state.value.accounts).isEqualTo(newDisplayAccounts)
        assertThat(testSubject.state.value.selectedAccountId).isEqualTo(newDisplayAccounts.first().id)
    }

    @Test
    fun `should set selected account to null when no accounts are present`() = runTest {
        val getDisplayAccountsFlow = MutableStateFlow(emptyList<DisplayAccount>())
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
        )

        advanceUntilIdle()

        assertThat(testSubject.state.value.accounts.size).isEqualTo(0)
        assertThat(testSubject.state.value.selectedAccountId).isEqualTo(null)
    }

    @Test
    fun `should send OpenAccount effect when OnAccountClick event is received`() = runMviTest {
        val displayAccounts = createDisplayAccountList(3)
        val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
        )
        val turbines = turbinesWithInitialStateCheck(
            testSubject,
            State(
                accounts = displayAccounts.toImmutableList(),
                selectedAccountId = displayAccounts.first().id,
            ),
        )

        advanceUntilIdle()

        testSubject.event(Event.OnAccountClick(displayAccounts[1]))

        advanceUntilIdle()

        turbines.assertThatAndEffectTurbineConsumed {
            isEqualTo(Effect.OpenAccount(displayAccounts[1].id))
        }
    }

    @Test
    fun `should collect display folders for selected account`() = runTest {
        val displayAccounts = createDisplayAccountList(3)
        val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
        val displayFoldersMap = mapOf(
            displayAccounts[0].id to createDisplayFolderList(3),
        )
        val displayFoldersFlow = MutableStateFlow(displayFoldersMap)
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
            displayFoldersFlow = displayFoldersFlow,
        )

        advanceUntilIdle()

        val displayFolders = displayFoldersMap[displayAccounts[0].id] ?: emptyList()
        assertThat(testSubject.state.value.folders.size).isEqualTo(displayFolders.size)
        assertThat(testSubject.state.value.folders).isEqualTo(displayFolders)
    }

    @Test
    fun `should collect display folders when selected account is changed`() = runTest {
        val displayAccounts = createDisplayAccountList(3)
        val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
        val displayFoldersMap = mapOf(
            displayAccounts[0].id to createDisplayFolderList(1),
            displayAccounts[1].id to createDisplayFolderList(5),
            displayAccounts[2].id to createDisplayFolderList(10),
        )
        val displayFoldersFlow = MutableStateFlow(displayFoldersMap)
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
            displayFoldersFlow = displayFoldersFlow,
        )

        advanceUntilIdle()

        testSubject.event(Event.SelectAccount(displayAccounts[1].id))

        advanceUntilIdle()

        val displayFolders = displayFoldersMap[displayAccounts[1].id] ?: emptyList()
        assertThat(testSubject.state.value.folders.size).isEqualTo(displayFolders.size)
        assertThat(testSubject.state.value.folders).isEqualTo(displayFolders)
    }

    @Test
    fun `should emit OpenFolder effect when OnFolderClick event is received`() = runMviTest {
        val displayAccounts = createDisplayAccountList(3)
        val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
        val displayFoldersMap = mapOf(
            displayAccounts[0].id to createDisplayFolderList(3),
        )
        val displayFoldersFlow = MutableStateFlow(displayFoldersMap)
        val initialState = State(
            accounts = displayAccounts.toImmutableList(),
            selectedAccountId = displayAccounts[0].id,
            folders = displayFoldersMap[displayAccounts[0].id]!!.toImmutableList(),
            selectedFolderId = displayFoldersMap[displayAccounts[0].id]!![0].id,
        )
        val testSubject = createTestSubject(
            displayAccountsFlow = getDisplayAccountsFlow,
            displayFoldersFlow = displayFoldersFlow,
        )
        val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

        advanceUntilIdle()

        val displayFolders = displayFoldersMap[displayAccounts[0].id] ?: emptyList()
        testSubject.event(Event.OnFolderClick(displayFolders[1]))

        assertThat(turbines.awaitEffectItem()).isEqualTo(Effect.OpenFolder(displayFolders[1].folder.id))

        turbines.assertThatAndEffectTurbineConsumed {
            isEqualTo(Effect.CloseDrawer)
        }
    }

    @Test
    fun `should emit OpenUnifiedFolder when OnFolderClick event for unified folder is received`() =
        runMviTest {
            val displayAccounts = createDisplayAccountList(1)
            val getDisplayAccountsFlow = MutableStateFlow(displayAccounts)
            val displayFoldersMap = mapOf(
                displayAccounts[0].id to
                    createDisplayFolderList(1) + listOf(createDisplayUnifiedFolder()),
            )
            val displayFoldersFlow = MutableStateFlow(displayFoldersMap)
            val initialState = State(
                accounts = displayAccounts.toImmutableList(),
                selectedAccountId = displayAccounts[0].id,
                folders = displayFoldersMap[displayAccounts[0].id]!!.toImmutableList(),
                selectedFolderId = displayFoldersMap[displayAccounts[0].id]!![0].id,
            )
            val testSubject = createTestSubject(
                displayAccountsFlow = getDisplayAccountsFlow,
                displayFoldersFlow = displayFoldersFlow,
            )
            val turbines = turbinesWithInitialStateCheck(testSubject, initialState)

            advanceUntilIdle()

            val displayFolders = displayFoldersMap[displayAccounts[0].id] ?: emptyList()
            testSubject.event(Event.OnFolderClick(displayFolders[1]))

            assertThat(turbines.awaitEffectItem()).isEqualTo(Effect.OpenUnifiedFolder)

            turbines.assertThatAndEffectTurbineConsumed {
                isEqualTo(Effect.CloseDrawer)
            }
        }

    @Test
    fun `should change state when OnAccountSelectorClick event is received`() = runMviTest {
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State())
        val drawerConfigWithAccountSelectorEnabled = createDrawerConfig(showAccountSelector = true)
        val drawerConfigWithAccountSelectorDisabled = createDrawerConfig(showAccountSelector = false)

        testSubject.event(Event.OnAccountSelectorClick)

        assertThat(turbines.awaitStateItem()).isEqualTo(State(config = drawerConfigWithAccountSelectorDisabled))

        testSubject.event(Event.OnAccountSelectorClick)

        assertThat(turbines.awaitStateItem()).isEqualTo(State(config = drawerConfigWithAccountSelectorEnabled))
    }

    @Test
    fun `should emit OpenManageFolders effect when OnManageFoldersClick event is received`() = runMviTest {
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State())

        testSubject.event(Event.OnManageFoldersClick)

        turbines.assertThatAndEffectTurbineConsumed {
            isEqualTo(Effect.OpenManageFolders)
        }
    }

    @Test
    fun `should emit OpenSettings effect when OnSettingsClick event is received`() = runMviTest {
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State())

        testSubject.event(Event.OnSettingsClick)

        turbines.assertThatAndEffectTurbineConsumed {
            isEqualTo(Effect.OpenSettings)
        }
    }

    private fun createTestSubject(
        initialState: State = State(),
        drawerConfigFlow: Flow<DrawerConfig> = flow { emit(createDrawerConfig()) },
        displayAccountsFlow: Flow<List<DisplayAccount>> = flow { emit(emptyList()) },
        displayFoldersFlow: Flow<Map<String, List<DisplayFolder>>> = flow { emit(emptyMap()) },
        syncAccountFlow: Flow<Result<Unit>> = flow { emit(Result.success(Unit)) },
        syncAllAccounts: Flow<Result<Unit>> = flow { emit(Result.success(Unit)) },
    ): DrawerViewModel {
        return DrawerViewModel(
            initialState = initialState,
            getDrawerConfig = { drawerConfigFlow },
            getDisplayAccounts = { displayAccountsFlow },
            getDisplayFoldersForAccount = { accountid, _ ->
                displayFoldersFlow.map { it[accountid] ?: emptyList() }
            },
            syncAccount = { syncAccountFlow },
            syncAllAccounts = { syncAllAccounts },
            saveDrawerConfig = { flow { emit(Unit) } },
        )
    }

    private fun createDrawerConfig(
        showUnifiedInbox: Boolean = false,
        showStarredCount: Boolean = false,
        showAccountSelector: Boolean = true,
    ): DrawerConfig {
        return DrawerConfig(
            showUnifiedFolders = showUnifiedInbox,
            showStarredCount = showStarredCount,
            showAccountSelector = showAccountSelector,
        )
    }

    private fun createDisplayAccount(
        id: String = "uuid",
        name: String = "name",
        email: String = "test@example.com",
        unreadCount: Int = 0,
        starredCount: Int = 0,
    ): DisplayAccount {
        return DisplayAccount(
            id = id,
            name = name,
            email = email,
            color = 0,
            unreadMessageCount = unreadCount,
            starredMessageCount = starredCount,
        )
    }

    private fun createDisplayAccountList(count: Int): List<DisplayAccount> {
        return List(count) { index ->
            createDisplayAccount(
                id = "uuid-$index",
            )
        }
    }

    private fun createDisplayFolder(
        accountId: String = "uuid",
        id: Long = 1234,
        name: String = "name",
        type: FolderType = FolderType.REGULAR,
        unreadCount: Int = 0,
        starredCount: Int = 0,
    ): DisplayAccountFolder {
        val folder = Folder(
            id = id,
            name = name,
            type = type,
            isLocalOnly = false,
        )

        return DisplayAccountFolder(
            accountId = accountId,
            folder = folder,
            isInTopGroup = false,
            unreadMessageCount = unreadCount,
            starredMessageCount = starredCount,
        )
    }

    private fun createDisplayFolderList(count: Int): List<DisplayAccountFolder> {
        return List(count) { index ->
            createDisplayFolder(
                id = index.toLong() + 100,
            )
        }
    }

    private fun createDisplayUnifiedFolder(
        id: String = "unified_inbox",
        unifiedType: DisplayUnifiedFolderType = DisplayUnifiedFolderType.INBOX,
        unreadCount: Int = 0,
        starredCount: Int = 0,
    ): DisplayUnifiedFolder {
        return DisplayUnifiedFolder(
            id = id,
            unifiedType = unifiedType,
            unreadMessageCount = unreadCount,
            starredMessageCount = starredCount,
        )
    }
}
