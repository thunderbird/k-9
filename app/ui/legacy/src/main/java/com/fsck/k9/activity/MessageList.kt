package com.fsck.k9.activity

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import androidx.appcompat.app.ActionBar
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import app.k9mail.core.android.common.contact.CachingRepository
import app.k9mail.core.android.common.contact.ContactRepository
import app.k9mail.core.featureflag.FeatureFlagKey
import app.k9mail.core.featureflag.FeatureFlagProvider
import app.k9mail.feature.launcher.FeatureLauncherActivity
import com.fsck.k9.Account
import com.fsck.k9.K9
import com.fsck.k9.K9.SplitViewMode
import com.fsck.k9.Preferences
import com.fsck.k9.account.BackgroundAccountRemover
import com.fsck.k9.activity.compose.MessageActions
import com.fsck.k9.controller.MessageReference
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.helper.ParcelableUtil
import com.fsck.k9.mailstore.SearchStatusManager
import com.fsck.k9.preferences.GeneralSettingsManager
import com.fsck.k9.search.LocalSearch
import com.fsck.k9.search.SearchAccount
import com.fsck.k9.search.SearchSpecification
import com.fsck.k9.search.SearchSpecification.SearchCondition
import com.fsck.k9.search.SearchSpecification.SearchField
import com.fsck.k9.search.isUnifiedInbox
import com.fsck.k9.ui.BuildConfig
import com.fsck.k9.ui.K9Drawer
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.K9Activity
import com.fsck.k9.ui.managefolders.ManageFoldersActivity
import com.fsck.k9.ui.messagelist.DefaultFolderProvider
import com.fsck.k9.ui.messagelist.MessageListFragment
import com.fsck.k9.ui.messagelist.MessageListFragment.MessageListFragmentListener
import com.fsck.k9.ui.messageview.Direction
import com.fsck.k9.ui.messageview.MessageViewContainerFragment
import com.fsck.k9.ui.messageview.MessageViewContainerFragment.MessageViewContainerListener
import com.fsck.k9.ui.messageview.MessageViewFragment.MessageViewFragmentListener
import com.fsck.k9.ui.messageview.PlaceholderFragment
import com.fsck.k9.ui.onboarding.OnboardingActivity
import com.fsck.k9.ui.permissions.K9PermissionUiHelper
import com.fsck.k9.ui.permissions.Permission
import com.fsck.k9.ui.permissions.PermissionUiHelper
import com.fsck.k9.view.ViewSwitcher
import com.fsck.k9.view.ViewSwitcher.OnSwitchCompleteListener
import com.mikepenz.materialdrawer.util.getOptimalDrawerWidth
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * MessageList is the primary user interface for the program. This Activity shows a list of messages.
 *
 * From this Activity the user can perform all standard message operations.
 */
open class MessageList :
    K9Activity(),
    MessageListFragmentListener,
    MessageViewFragmentListener,
    MessageViewContainerListener,
    FragmentManager.OnBackStackChangedListener,
    OnSwitchCompleteListener,
    PermissionUiHelper {

    protected val searchStatusManager: SearchStatusManager by inject()
    private val preferences: Preferences by inject()
    private val defaultFolderProvider: DefaultFolderProvider by inject()
    private val accountRemover: BackgroundAccountRemover by inject()
    private val generalSettingsManager: GeneralSettingsManager by inject()
    private val messagingController: MessagingController by inject()
    private val contactRepository: ContactRepository by inject()
    private val featureFlagProvider: FeatureFlagProvider by inject()

    private val permissionUiHelper: PermissionUiHelper = K9PermissionUiHelper(this)

    private lateinit var actionBar: ActionBar
    private var searchView: SearchView? = null
    private var initialSearchViewQuery: String? = null
    private var initialSearchViewIconified: Boolean = true

    private var drawer: K9Drawer? = null
    private var openFolderTransaction: FragmentTransaction? = null
    private var progressBar: ProgressBar? = null
    private var messageViewPlaceHolder: PlaceholderFragment? = null
    private var messageListFragment: MessageListFragment? = null
    private var messageViewContainerFragment: MessageViewContainerFragment? = null
    private var account: Account? = null
    private var search: LocalSearch? = null
    private var singleFolderMode = false

    private val lastDirection: Direction
        get() {
            return messageViewContainerFragment?.lastDirection
                ?: if (K9.isMessageViewShowNext) Direction.NEXT else Direction.PREVIOUS
        }

    private var messageListActivityConfig: MessageListActivityConfig? = null

    /**
     * `true` if the message list should be displayed as flat list (i.e. no threading)
     * regardless whether or not message threading was enabled in the settings. This is used for
     * filtered views, e.g. when only displaying the unread messages in a folder.
     */
    private var noThreading = false
    private var displayMode: DisplayMode = DisplayMode.MESSAGE_LIST
    private var messageReference: MessageReference? = null

    /**
     * If this is `true`, only the message view will be displayed and pressing the back button will finish the Activity.
     */
    private var messageViewOnly = false
    private var messageListWasDisplayed = false
    private var viewSwitcher: ViewSwitcher? = null

    private val isShowAccountChip: Boolean
        get() = messageListFragment?.isShowAccountChip ?: true

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        // If the app's main task was not created using the default launch intent (e.g. from a notification, a widget,
        // or a shortcut), using the app icon to "launch" the app will create a new MessageList instance instead of only
        // bringing the app's task to the foreground. We catch this situation here and simply finish the activity. This
        // will bring the task to the foreground, showing the last active screen.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && !isTaskRoot) {
            Timber.v("Not displaying MessageList. Only bringing the app task to the foreground.")
            finish()
            return
        }

        val accounts = preferences.accounts
        deleteIncompleteAccounts(accounts)
        val hasAccountSetup = accounts.any { it.isFinishedSetup }
        if (!hasAccountSetup) {
            featureFlagProvider.provide(FeatureFlagKey("new_onboarding")).onEnabled {
                FeatureLauncherActivity.launchOnboarding(this)
            }.onDisabled {
                OnboardingActivity.launch(this)
            }.onUnavailable {
                Timber.d("Feature flag 'new_onboarding' is unavailable, falling back to old onboarding")
                OnboardingActivity.launch(this)
            }
            finish()
            return
        }

        if (UpgradeDatabases.actionUpgradeDatabases(this, intent)) {
            finish()
            return
        }

        if (useSplitView()) {
            setLayout(R.layout.split_message_list)
        } else {
            setLayout(R.layout.message_list)
            viewSwitcher = findViewById<ViewSwitcher>(R.id.container).apply {
                firstInAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_in_left)
                firstOutAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_out_right)
                secondInAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_in_right)
                secondOutAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_out_left)
                setOnSwitchCompleteListener(this@MessageList)
            }
        }

        window.statusBarColor = Color.TRANSPARENT

        val rootLayout = findViewById<View>(R.id.drawerLayout)
        rootLayout.systemUiVisibility = rootLayout.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(view.paddingLeft, insets.systemWindowInsetTop, view.paddingRight, view.paddingBottom)
            insets
        }

        val swipeRefreshLayout = findViewById<View>(R.id.material_drawer_swipe_refresh)
        swipeRefreshLayout.layoutParams.width = getOptimalDrawerWidth(this)

        initializeActionBar()
        initializeDrawer(savedInstanceState)

        if (!decodeExtras(intent)) {
            return
        }

        if (isDrawerEnabled) {
            configureDrawer()
            drawer!!.updateUserAccountsAndFolders(account)
        }

        findFragments()
        initializeDisplayMode(savedInstanceState)
        initializeLayout()
        initializeFragments()
        displayViews()

        if (savedInstanceState == null) {
            checkAndRequestPermissions()
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (isFinishing) {
            return
        }

        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            // There's nothing to do if the default launcher Intent was used.
            // This only brings the existing screen to the foreground.
            return
        }

        setIntent(intent)

        // Start with a fresh fragment back stack
        supportFragmentManager.popBackStackImmediate(
            FIRST_FRAGMENT_TRANSACTION,
            FragmentManager.POP_BACK_STACK_INCLUSIVE,
        )

        removeMessageListFragment()
        removeMessageViewContainerFragment()

        messageReference = null
        search = null

        if (!decodeExtras(intent)) {
            return
        }

        if (isDrawerEnabled) {
            configureDrawer()
            drawer!!.updateUserAccountsAndFolders(account)
        }

        initializeDisplayMode(null)
        initializeFragments()
        displayViews()
    }

    private fun deleteIncompleteAccounts(accounts: List<Account>) {
        accounts.filter { !it.isFinishedSetup }.forEach {
            accountRemover.removeAccountAsync(it.uuid)
        }
    }

    private fun findFragments() {
        val fragmentManager = supportFragmentManager
        messageListFragment = fragmentManager.findFragmentById(R.id.message_list_container) as MessageListFragment?
        messageViewContainerFragment =
            fragmentManager.findFragmentByTag(FRAGMENT_TAG_MESSAGE_VIEW_CONTAINER) as MessageViewContainerFragment?

        messageListFragment?.let { messageListFragment ->
            messageViewContainerFragment?.setViewModel(messageListFragment.viewModel)
            initializeFromLocalSearch(messageListFragment.localSearch)
        }
    }

    private fun initializeFragments() {
        val fragmentManager = supportFragmentManager
        fragmentManager.addOnBackStackChangedListener(this)

        val hasMessageListFragment = messageListFragment != null
        if (!hasMessageListFragment) {
            val fragmentTransaction = fragmentManager.beginTransaction()
            val messageListFragment = MessageListFragment.newInstance(
                search!!,
                false,
                K9.isThreadedViewEnabled && !noThreading,
            )
            fragmentTransaction.add(R.id.message_list_container, messageListFragment)
            fragmentTransaction.commitNow()

            this.messageListFragment = messageListFragment
        }

        // Check if the fragment wasn't restarted and has a MessageReference in the arguments.
        // If so, open the referenced message.
        if (!hasMessageListFragment && messageViewContainerFragment == null && messageReference != null) {
            openMessage(messageReference!!)
        }
    }

    /**
     * Set the initial display mode (message list, message view, or split view).
     *
     * **Note:**
     * This method has to be called after [.findFragments] because the result depends on
     * the availability of a [MessageViewFragment] instance.
     */
    private fun initializeDisplayMode(savedInstanceState: Bundle?) {
        if (useSplitView()) {
            displayMode = DisplayMode.SPLIT_VIEW
            return
        }

        if (savedInstanceState != null) {
            val savedDisplayMode = savedInstanceState.getSerializable(STATE_DISPLAY_MODE) as DisplayMode
            if (savedDisplayMode != DisplayMode.SPLIT_VIEW) {
                displayMode = savedDisplayMode
                return
            }
        }

        displayMode = if (messageViewContainerFragment != null || messageReference != null) {
            DisplayMode.MESSAGE_VIEW
        } else {
            DisplayMode.MESSAGE_LIST
        }
    }

    private fun useSplitView(): Boolean {
        val splitViewMode = K9.splitViewMode
        val orientation = resources.configuration.orientation
        return splitViewMode === SplitViewMode.ALWAYS ||
            splitViewMode === SplitViewMode.WHEN_IN_LANDSCAPE && orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun initializeLayout() {
        progressBar = findViewById(R.id.message_list_progress)
        messageViewPlaceHolder = PlaceholderFragment()
    }

    private fun displayViews() {
        when (displayMode) {
            DisplayMode.MESSAGE_LIST -> {
                showMessageList()
            }
            DisplayMode.MESSAGE_VIEW -> {
                showMessageView()
            }
            DisplayMode.SPLIT_VIEW -> {
                val messageListFragment = checkNotNull(this.messageListFragment)

                messageListWasDisplayed = true
                messageListFragment.setFullyActive()

                messageViewContainerFragment.let { messageViewContainerFragment ->
                    if (messageViewContainerFragment == null) {
                        showMessageViewPlaceHolder()
                    } else {
                        messageViewContainerFragment.isActive = true
                    }
                }

                setDrawerLockState()
                onMessageListDisplayed()
            }
        }
    }

    private fun decodeExtras(intent: Intent): Boolean {
        val launchData = decodeExtrasToLaunchData(intent)

        // If Unified Inbox was disabled show default account instead
        val search = if (launchData.search.isUnifiedInbox && !K9.isShowUnifiedInbox) {
            createDefaultLocalSearch()
        } else {
            launchData.search
        }

        // If no account has been specified, keep the currently active account when opening the Unified Inbox
        val account = launchData.account
            ?: account?.takeIf { launchData.search.isUnifiedInbox }
            ?: search.firstAccount()

        if (account == null) {
            finish()
            return false
        }

        this.account = account
        this.search = search
        singleFolderMode = search.folderIds.size == 1
        noThreading = launchData.noThreading
        messageReference = launchData.messageReference
        messageViewOnly = launchData.messageViewOnly

        return true
    }

    private fun decodeExtrasToLaunchData(intent: Intent): LaunchData {
        val action = intent.action
        val queryString = intent.getStringExtra(SearchManager.QUERY)

        if (action == ACTION_SHORTCUT) {
            // Handle shortcut intents
            val specialFolder = intent.getStringExtra(EXTRA_SPECIAL_FOLDER)
            if (specialFolder == SearchAccount.UNIFIED_INBOX) {
                return LaunchData(search = SearchAccount.createUnifiedInboxAccount().relatedSearch)
            }

            val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT)
            if (accountUuid != null) {
                val account = preferences.getAccount(accountUuid)
                if (account == null) {
                    Timber.d("Account %s not found.", accountUuid)
                    return LaunchData(createDefaultLocalSearch())
                }

                val folderId = defaultFolderProvider.getDefaultFolder(account)
                val search = LocalSearch().apply {
                    addAccountUuid(accountUuid)
                    addAllowedFolder(folderId)
                }

                return LaunchData(search = search)
            }
        } else if (action == Intent.ACTION_SEARCH && queryString != null) {
            // Query was received from Search Dialog
            val query = queryString.trim()

            val search = LocalSearch().apply {
                isManualSearch = true
                or(SearchCondition(SearchField.SENDER, SearchSpecification.Attribute.CONTAINS, query))
                or(SearchCondition(SearchField.TO, SearchSpecification.Attribute.CONTAINS, query))
                or(SearchCondition(SearchField.CC, SearchSpecification.Attribute.CONTAINS, query))
                or(SearchCondition(SearchField.BCC, SearchSpecification.Attribute.CONTAINS, query))
                or(SearchCondition(SearchField.SUBJECT, SearchSpecification.Attribute.CONTAINS, query))
                or(SearchCondition(SearchField.MESSAGE_CONTENTS, SearchSpecification.Attribute.CONTAINS, query))
            }

            val appData = intent.getBundleExtra(SearchManager.APP_DATA)
            if (appData != null) {
                val searchAccountUuid = appData.getString(EXTRA_SEARCH_ACCOUNT)
                if (searchAccountUuid != null) {
                    search.addAccountUuid(searchAccountUuid)
                    // searches started from a folder list activity will provide an account, but no folder
                    if (appData.containsKey(EXTRA_SEARCH_FOLDER)) {
                        val folderId = appData.getLong(EXTRA_SEARCH_FOLDER)
                        search.addAllowedFolder(folderId)
                    }
                } else if (BuildConfig.DEBUG) {
                    throw AssertionError("Invalid app data in search intent")
                }
            }

            return LaunchData(
                search = search,
                noThreading = true,
            )
        } else if (intent.hasExtra(EXTRA_MESSAGE_REFERENCE)) {
            val messageReferenceString = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE)
            val messageReference = MessageReference.parse(messageReferenceString)

            if (messageReference != null) {
                val search = if (intent.hasByteArrayExtra(EXTRA_SEARCH)) {
                    ParcelableUtil.unmarshall(intent.getByteArrayExtra(EXTRA_SEARCH), LocalSearch.CREATOR)
                } else {
                    messageReference.toLocalSearch()
                }

                return LaunchData(
                    search = search,
                    messageReference = messageReference,
                    messageViewOnly = intent.getBooleanExtra(EXTRA_MESSAGE_VIEW_ONLY, false),
                )
            }
        } else if (intent.hasByteArrayExtra(EXTRA_SEARCH)) {
            // regular LocalSearch object was passed
            val search = ParcelableUtil.unmarshall(intent.getByteArrayExtra(EXTRA_SEARCH), LocalSearch.CREATOR)
            val noThreading = intent.getBooleanExtra(EXTRA_NO_THREADING, false)
            val account = intent.getStringExtra(EXTRA_ACCOUNT)?.let { accountUuid ->
                preferences.getAccount(accountUuid)
            }

            return LaunchData(search = search, account = account, noThreading = noThreading)
        }

        // Default action
        val search = if (K9.isShowUnifiedInbox) {
            SearchAccount.createUnifiedInboxAccount().relatedSearch
        } else {
            createDefaultLocalSearch()
        }

        return LaunchData(search)
    }

    private fun createDefaultLocalSearch(): LocalSearch {
        val account = preferences.defaultAccount ?: error("No default account available")
        return LocalSearch().apply {
            addAccountUuid(account.uuid)
            addAllowedFolder(defaultFolderProvider.getDefaultFolder(account))
        }
    }

    private fun checkAndRequestPermissions() {
        if (!hasPermission(Permission.READ_CONTACTS)) {
            requestPermissionOrShowRationale(Permission.READ_CONTACTS)
        }
    }

    public override fun onResume() {
        super.onResume()

        if (messageListActivityConfig == null) {
            messageListActivityConfig = MessageListActivityConfig.create(generalSettingsManager)
        } else if (messageListActivityConfig != MessageListActivityConfig.create(generalSettingsManager)) {
            recreateCompat()
        }

        if (displayMode != DisplayMode.MESSAGE_VIEW) {
            onMessageListDisplayed()
        }

        if (this !is Search) {
            // necessary b/c no guarantee Search.onStop will be called before MessageList.onResume
            // when returning from search results
            searchStatusManager.isActive = false
        }
    }

    override fun onStart() {
        super.onStart()

        if (contactRepository is CachingRepository) {
            (contactRepository as CachingRepository).clearCache()
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putSerializable(STATE_DISPLAY_MODE, displayMode)
        outState.putBoolean(STATE_MESSAGE_VIEW_ONLY, messageViewOnly)
        outState.putBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED, messageListWasDisplayed)
        searchView?.let { searchView ->
            outState.putBoolean(STATE_SEARCH_VIEW_ICONIFIED, searchView.isIconified)
            outState.putString(STATE_SEARCH_VIEW_QUERY, searchView.query?.toString())
        }
    }

    public override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        messageViewOnly = savedInstanceState.getBoolean(STATE_MESSAGE_VIEW_ONLY)
        messageListWasDisplayed = savedInstanceState.getBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED)
        initialSearchViewIconified = savedInstanceState.getBoolean(STATE_SEARCH_VIEW_ICONIFIED, true)
        initialSearchViewQuery = savedInstanceState.getString(STATE_SEARCH_VIEW_QUERY)
    }

    private fun initializeActionBar() {
        actionBar = supportActionBar!!
        actionBar.setDisplayHomeAsUpEnabled(true)
    }

    private fun initializeDrawer(savedInstanceState: Bundle?) {
        if (!isDrawerEnabled) {
            val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            return
        }

        drawer = K9Drawer(this, savedInstanceState)
    }

    fun createDrawerListener(): DrawerListener {
        return object : DrawerListener {
            override fun onDrawerClosed(drawerView: View) {
                if (openFolderTransaction != null) {
                    commitOpenFolderTransaction()
                }
            }

            override fun onDrawerStateChanged(newState: Int) = Unit

            override fun onDrawerOpened(drawerView: View) {
                collapseSearchView()
                messageListFragment?.finishActionMode()
            }

            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = Unit
        }
    }

    fun openFolder(folderId: Long) {
        if (displayMode == DisplayMode.SPLIT_VIEW) {
            removeMessageViewContainerFragment()
            showMessageViewPlaceHolder()
        }

        val search = LocalSearch()
        search.addAccountUuid(account!!.uuid)
        search.addAllowedFolder(folderId)

        performSearch(search)
    }

    private fun openFolderImmediately(folderId: Long) {
        openFolder(folderId)
        commitOpenFolderTransaction()
    }

    private fun commitOpenFolderTransaction() {
        openFolderTransaction!!.commit()
        openFolderTransaction = null

        messageListFragment!!.setFullyActive()

        onMessageListDisplayed()
    }

    fun openUnifiedInbox() {
        actionDisplaySearch(this, SearchAccount.createUnifiedInboxAccount().relatedSearch, false, false)
    }

    fun launchManageFoldersScreen() {
        if (account == null) {
            Timber.e("Tried to open \"Manage folders\", but no account selected!")
            return
        }

        ManageFoldersActivity.launch(this, account!!)
    }

    fun openRealAccount(account: Account): Boolean {
        val shouldCloseDrawer = account.autoExpandFolderId != null

        val folderId = defaultFolderProvider.getDefaultFolder(account)

        val search = LocalSearch()
        search.addAllowedFolder(folderId)
        search.addAccountUuid(account.uuid)
        actionDisplaySearch(this, search, noThreading = false, newTask = false)

        return shouldCloseDrawer
    }

    private fun performSearch(search: LocalSearch) {
        initializeFromLocalSearch(search)

        val fragmentManager = supportFragmentManager

        check(!(BuildConfig.DEBUG && fragmentManager.backStackEntryCount > 0)) {
            "Don't call performSearch() while there are fragments on the back stack"
        }

        val openFolderTransaction = fragmentManager.beginTransaction()
        val messageListFragment = MessageListFragment.newInstance(search, false, K9.isThreadedViewEnabled)
        openFolderTransaction.replace(R.id.message_list_container, messageListFragment)

        this.messageListFragment = messageListFragment
        this.openFolderTransaction = openFolderTransaction
    }

    protected open val isDrawerEnabled: Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        var eventHandled = false
        if (event.action == KeyEvent.ACTION_DOWN && isSearchViewCollapsed()) {
            eventHandled = onCustomKeyDown(event)
        }

        if (!eventHandled) {
            eventHandled = super.dispatchKeyEvent(event)
        }

        return eventHandled
    }

    override fun onBackPressed() {
        if (isDrawerEnabled && drawer!!.isOpen) {
            drawer!!.close()
        } else if (displayMode == DisplayMode.MESSAGE_VIEW) {
            if (messageViewOnly) {
                finish()
            } else {
                showMessageList()
            }
        } else if (!isSearchViewCollapsed()) {
            collapseSearchView()
        } else {
            if (isDrawerEnabled && account != null && supportFragmentManager.backStackEntryCount == 0) {
                if (K9.isShowUnifiedInbox) {
                    if (search!!.id != SearchAccount.UNIFIED_INBOX) {
                        openUnifiedInbox()
                    } else {
                        super.onBackPressed()
                    }
                } else {
                    val defaultFolderId = defaultFolderProvider.getDefaultFolder(account!!)
                    val currentFolder = if (singleFolderMode) search!!.folderIds[0] else null
                    if (currentFolder == null || defaultFolderId != currentFolder) {
                        openFolderImmediately(defaultFolderId)
                    } else {
                        super.onBackPressed()
                    }
                }
            } else {
                super.onBackPressed()
            }
        }
    }

    /**
     * Handle hotkeys
     *
     * This method is called by [.dispatchKeyEvent] before any view had the chance to consume this key event.
     *
     * @return `true` if this event was consumed.
     */
    private fun onCustomKeyDown(event: KeyEvent): Boolean {
        if (!event.hasNoModifiers()) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (messageViewContainerFragment != null && displayMode != DisplayMode.MESSAGE_LIST &&
                    K9.isUseVolumeKeysForNavigation
                ) {
                    showPreviousMessage()
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (messageViewContainerFragment != null && displayMode != DisplayMode.MESSAGE_LIST &&
                    K9.isUseVolumeKeysForNavigation
                ) {
                    showNextMessage()
                    return true
                }
            }
            KeyEvent.KEYCODE_DEL -> {
                onDeleteHotKey()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                return if (messageViewContainerFragment != null && displayMode == DisplayMode.MESSAGE_VIEW) {
                    showPreviousMessage()
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                return if (messageViewContainerFragment != null && displayMode == DisplayMode.MESSAGE_VIEW) {
                    showNextMessage()
                } else {
                    false
                }
            }
        }

        when (if (event.unicodeChar != 0) event.unicodeChar.toChar() else null) {
            'c' -> {
                messageListFragment!!.onCompose()
                return true
            }
            'o' -> {
                messageListFragment!!.onCycleSort()
                return true
            }
            'i' -> {
                messageListFragment!!.onReverseSort()
                return true
            }
            'd' -> {
                onDeleteHotKey()
                return true
            }
            's' -> {
                messageListFragment!!.toggleMessageSelect()
                return true
            }
            'g' -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onToggleFlagged()
                } else if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onToggleFlagged()
                }
                return true
            }
            'm' -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onMove()
                } else if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onMove()
                }
                return true
            }
            'v' -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onArchive()
                } else if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onArchive()
                }
                return true
            }
            'y' -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onCopy()
                } else if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onCopy()
                }
                return true
            }
            'z' -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onToggleRead()
                } else if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onToggleRead()
                }
                return true
            }
            'f' -> {
                if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onForward()
                }
                return true
            }
            'a' -> {
                if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onReplyAll()
                }
                return true
            }
            'r' -> {
                if (messageViewContainerFragment != null) {
                    messageViewContainerFragment!!.onReply()
                }
                return true
            }
            'j', 'p' -> {
                if (messageViewContainerFragment != null) {
                    showPreviousMessage()
                }
                return true
            }
            'n', 'k' -> {
                if (messageViewContainerFragment != null) {
                    showNextMessage()
                }
                return true
            }
        }

        return false
    }

    private fun onDeleteHotKey() {
        if (displayMode == DisplayMode.MESSAGE_LIST) {
            messageListFragment!!.onDelete()
        } else if (messageViewContainerFragment != null) {
            messageViewContainerFragment!!.onDelete()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow these events too to avoid the audible notification of a volume change
        if (K9.isUseVolumeKeysForNavigation) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                Timber.v("Swallowed key up.")
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            if (displayMode != DisplayMode.MESSAGE_VIEW && !isAdditionalMessageListDisplayed) {
                if (isDrawerEnabled) {
                    if (drawer!!.isOpen) {
                        drawer!!.close()
                    } else {
                        drawer!!.open()
                    }
                } else {
                    finish()
                }
            } else {
                goBack()
            }
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.message_list_option, menu)

        val searchItem = menu.findItem(R.id.search)
        initializeSearchMenuItem(searchItem)

        return true
    }

    private fun initializeSearchMenuItem(searchItem: MenuItem) {
        // Reuse existing SearchView if available
        searchView?.let { searchView ->
            searchItem.actionView = searchView
            return
        }

        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE
        searchView.queryHint = resources.getString(R.string.search_action)
        val searchManager = getSystemService(SEARCH_SERVICE) as SearchManager
        searchView.setSearchableInfo(searchManager.getSearchableInfo(componentName))
        searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    messageListFragment?.onSearchRequested(query)
                    collapseSearchView()
                    return true
                }

                override fun onQueryTextChange(s: String): Boolean {
                    return false
                }
            },
        )

        searchView.setQuery(initialSearchViewQuery, false)
        searchView.isIconified = initialSearchViewIconified

        this.searchView = searchView
    }

    private fun isSearchViewCollapsed(): Boolean = searchView?.isIconified == true

    private fun collapseSearchView() {
        searchView?.let { searchView ->
            searchView.setQuery(null, false)
            searchView.isIconified = true
        }
    }

    fun setActionBarTitle(title: String, subtitle: String? = null) {
        actionBar.title = title
        actionBar.subtitle = subtitle
    }

    override fun setMessageListTitle(title: String, subtitle: String?) {
        if (displayMode != DisplayMode.MESSAGE_VIEW) {
            setActionBarTitle(title, subtitle)
        }
    }

    override fun setMessageListProgressEnabled(enable: Boolean) {
        progressBar!!.visibility = if (enable) View.VISIBLE else View.INVISIBLE
    }

    override fun setMessageListProgress(level: Int) {
        progressBar!!.progress = level
    }

    override fun openMessage(messageReference: MessageReference) {
        val account = preferences.getAccount(messageReference.accountUuid) ?: error("Account not found")
        val folderId = messageReference.folderId

        val draftsFolderId = account.draftsFolderId
        if (draftsFolderId != null && folderId == draftsFolderId) {
            displayMode = DisplayMode.MESSAGE_LIST
            MessageActions.actionEditDraft(this, messageReference)
        } else {
            val fragment = MessageViewContainerFragment.newInstance(messageReference, isShowAccountChip)
            supportFragmentManager.commitNow {
                replace(R.id.message_view_container, fragment, FRAGMENT_TAG_MESSAGE_VIEW_CONTAINER)
            }

            messageViewContainerFragment = fragment

            messageListFragment?.let { messageListFragment ->
                fragment.setViewModel(messageListFragment.viewModel)
            }

            if (displayMode == DisplayMode.SPLIT_VIEW) {
                fragment.isActive = true
            } else {
                showMessageView()
            }
        }

        collapseSearchView()
    }

    override fun onForward(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionForward(this, messageReference, decryptionResultForReply)
    }

    override fun onForwardAsAttachment(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionForwardAsAttachment(this, messageReference, decryptionResultForReply)
    }

    override fun onEditAsNewMessage(messageReference: MessageReference) {
        MessageActions.actionEditDraft(this, messageReference)
    }

    override fun onReply(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionReply(this, messageReference, false, decryptionResultForReply)
    }

    override fun onReplyAll(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionReply(this, messageReference, true, decryptionResultForReply)
    }

    override fun onCompose(account: Account?) {
        MessageActions.actionCompose(this, account)
    }

    override fun onBackStackChanged() {
        findFragments()
        messageListFragment?.setFullyActive()

        if (isDrawerEnabled && !isAdditionalMessageListDisplayed) {
            unlockDrawer()
        }

        if (displayMode == DisplayMode.SPLIT_VIEW) {
            showMessageViewPlaceHolder()
        }
    }

    private fun addMessageListFragment(fragment: MessageListFragment) {
        messageListFragment?.isActive = false

        supportFragmentManager.commit {
            replace(R.id.message_list_container, fragment)

            setReorderingAllowed(true)

            if (supportFragmentManager.backStackEntryCount == 0) {
                addToBackStack(FIRST_FRAGMENT_TRANSACTION)
            } else {
                addToBackStack(null)
            }
        }

        messageListFragment = fragment
        fragment.setFullyActive()

        if (isDrawerEnabled) {
            lockDrawer()
        }
    }

    override fun startSearch(query: String, account: Account?, folderId: Long?): Boolean {
        // If this search was started from a MessageList of a single folder, pass along that folder info
        // so that we can enable remote search.
        val appData = if (account != null && folderId != null) {
            Bundle().apply {
                putString(EXTRA_SEARCH_ACCOUNT, account.uuid)
                putLong(EXTRA_SEARCH_FOLDER, folderId)
            }
        } else {
            // TODO Handle the case where we're searching from within a search result.
            null
        }
        val searchIntent = Intent(this, Search::class.java).apply {
            action = Intent.ACTION_SEARCH
            putExtra(SearchManager.QUERY, query)
            putExtra(SearchManager.APP_DATA, appData)
        }
        startActivity(searchIntent)

        return true
    }

    override fun startSupportActionMode(callback: ActionMode.Callback): ActionMode? {
        collapseSearchView()
        return super.startSupportActionMode(callback)
    }

    override fun showThread(account: Account, threadRootId: Long) {
        showMessageViewPlaceHolder()

        val tmpSearch = LocalSearch().apply {
            addAccountUuid(account.uuid)
            and(SearchField.THREAD_ID, threadRootId.toString(), SearchSpecification.Attribute.EQUALS)
        }

        initializeFromLocalSearch(tmpSearch)

        val fragment = MessageListFragment.newInstance(tmpSearch, true, false)
        addMessageListFragment(fragment)
    }

    private fun showMessageViewPlaceHolder() {
        removeMessageViewContainerFragment()

        // Add placeholder fragment if necessary
        val fragmentManager = supportFragmentManager
        if (fragmentManager.findFragmentByTag(FRAGMENT_TAG_PLACEHOLDER) == null) {
            val fragmentTransaction = fragmentManager.beginTransaction()
            fragmentTransaction.replace(R.id.message_view_container, messageViewPlaceHolder!!, FRAGMENT_TAG_PLACEHOLDER)
            fragmentTransaction.commit()
        }

        messageListFragment!!.setActiveMessage(null)
    }

    private fun removeMessageViewContainerFragment() {
        if (messageViewContainerFragment != null) {
            val fragmentTransaction = supportFragmentManager.beginTransaction()
            fragmentTransaction.remove(messageViewContainerFragment!!)
            messageViewContainerFragment = null
            fragmentTransaction.commit()

            showDefaultTitleView()
        }
    }

    private fun removeMessageListFragment() {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.remove(messageListFragment!!)
        messageListFragment = null
        fragmentTransaction.commit()
    }

    override fun goBack() {
        val fragmentManager = supportFragmentManager
        when {
            displayMode == DisplayMode.MESSAGE_VIEW -> showMessageList()
            fragmentManager.backStackEntryCount > 0 -> fragmentManager.popBackStack()
            else -> finish()
        }
    }

    override fun closeMessageView() {
        returnToMessageList()
    }

    override fun setActiveMessage(messageReference: MessageReference) {
        val messageListFragment = checkNotNull(messageListFragment)

        messageListFragment.setActiveMessage(messageReference)
    }

    override fun showNextMessageOrReturn() {
        if (K9.isMessageViewReturnToList || !showLogicalNextMessage()) {
            returnToMessageList()
        }
    }

    private fun returnToMessageList() {
        if (displayMode == DisplayMode.SPLIT_VIEW) {
            showMessageViewPlaceHolder()
        } else {
            showMessageList()
        }
    }

    private fun showLogicalNextMessage(): Boolean {
        val couldMoveInLastDirection = when (lastDirection) {
            Direction.NEXT -> showNextMessage()
            Direction.PREVIOUS -> showPreviousMessage()
        }

        return if (couldMoveInLastDirection) {
            true
        } else {
            showNextMessage() || showPreviousMessage()
        }
    }

    override fun setProgress(enable: Boolean) {
        setProgressBarIndeterminateVisibility(enable)
    }

    private fun showNextMessage(): Boolean {
        val messageViewContainerFragment = checkNotNull(messageViewContainerFragment)

        return messageViewContainerFragment.showNextMessage()
    }

    private fun showPreviousMessage(): Boolean {
        val messageViewContainerFragment = checkNotNull(messageViewContainerFragment)

        return messageViewContainerFragment.showPreviousMessage()
    }

    private fun showMessageList() {
        messageViewOnly = false
        messageListWasDisplayed = true
        displayMode = DisplayMode.MESSAGE_LIST

        messageViewContainerFragment?.isActive = false
        messageListFragment!!.isActive = true
        messageListFragment!!.setActiveMessage(null)

        viewSwitcher!!.showFirstView()

        setDrawerLockState()

        showDefaultTitleView()

        onMessageListDisplayed()
    }

    private fun setDrawerLockState() {
        if (!isDrawerEnabled) return

        if (isAdditionalMessageListDisplayed) {
            lockDrawer()
        } else {
            unlockDrawer()
        }
    }

    private fun showMessageView() {
        val messageViewContainerFragment = checkNotNull(this.messageViewContainerFragment)

        displayMode = DisplayMode.MESSAGE_VIEW
        messageListFragment?.isActive = false
        messageViewContainerFragment.isActive = true

        if (!messageListWasDisplayed) {
            viewSwitcher!!.animateFirstView = false
        }
        viewSwitcher!!.showSecondView()

        if (isDrawerEnabled) {
            lockDrawer()
        }

        showMessageTitleView()
    }

    private fun showDefaultTitleView() {
        if (messageListFragment != null) {
            messageListFragment!!.updateTitle()
        }
    }

    private fun showMessageTitleView() {
        setActionBarTitle("")
    }

    override fun onSwitchComplete(displayedChild: Int) {
        if (displayedChild == 0) {
            removeMessageViewContainerFragment()
            messageListFragment?.onFullyActive()
        }
    }

    private fun onMessageListDisplayed() {
        clearNotifications()
    }

    private fun clearNotifications() {
        messagingController.clearNotifications(search)
    }

    override fun startIntentSenderForResult(
        intent: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
    ) {
        // If any of the high 16 bits are set it is not one of our request codes
        if (requestCode and REQUEST_CODE_MASK != 0) {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags)
            return
        }

        val modifiedRequestCode = requestCode or REQUEST_FLAG_PENDING_INTENT
        super.startIntentSenderForResult(intent, modifiedRequestCode, fillInIntent, flagsMask, flagsValues, extraFlags)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // If any of the high 16 bits are set it is not one of our request codes
        if (requestCode and REQUEST_CODE_MASK != 0) return

        if (requestCode and REQUEST_FLAG_PENDING_INTENT != 0) {
            val originalRequestCode = requestCode xor REQUEST_FLAG_PENDING_INTENT
            if (messageViewContainerFragment != null) {
                messageViewContainerFragment!!.onPendingIntentResult(originalRequestCode, resultCode, data)
            }
        }
    }

    private val isAdditionalMessageListDisplayed: Boolean
        get() = supportFragmentManager.backStackEntryCount > 0

    private fun lockDrawer() {
        drawer!!.lock()
        actionBar.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
    }

    private fun unlockDrawer() {
        drawer!!.unlock()
        actionBar.setHomeAsUpIndicator(R.drawable.ic_menu)
    }

    private fun initializeFromLocalSearch(search: LocalSearch?) {
        this.search = search
        singleFolderMode = false

        if (!search!!.searchAllAccounts()) {
            val accountUuids = search.accountUuids
            if (accountUuids.size == 1) {
                account = preferences.getAccount(accountUuids[0])
                val folderIds = search.folderIds
                singleFolderMode = folderIds.size == 1
            } else {
                account = null
            }
        }

        configureDrawer()
    }

    private fun LocalSearch.firstAccount(): Account? {
        return if (searchAllAccounts()) {
            preferences.defaultAccount
        } else {
            val accountUuid = accountUuids.first()
            preferences.getAccount(accountUuid)
        }
    }

    private fun MessageReference.toLocalSearch(): LocalSearch {
        return LocalSearch().apply {
            addAccountUuid(accountUuid)
            addAllowedFolder(folderId)
        }
    }

    private fun MessageListFragment.setFullyActive() {
        isActive = true
        onFullyActive()
    }

    private fun configureDrawer() {
        val drawer = drawer ?: return
        drawer.selectAccount(account!!.uuid)
        when {
            singleFolderMode -> drawer.selectFolder(search!!.folderIds[0])
            // Don't select any item in the drawer because the Unified Inbox is displayed, but not listed in the drawer
            search!!.id == SearchAccount.UNIFIED_INBOX && !K9.isShowUnifiedInbox -> drawer.deselect()
            search!!.id == SearchAccount.UNIFIED_INBOX -> drawer.selectUnifiedInbox()
            else -> drawer.deselect()
        }
    }

    override fun hasPermission(permission: Permission): Boolean {
        return permissionUiHelper.hasPermission(permission)
    }

    override fun requestPermissionOrShowRationale(permission: Permission) {
        permissionUiHelper.requestPermissionOrShowRationale(permission)
    }

    override fun requestPermission(permission: Permission) {
        permissionUiHelper.requestPermission(permission)
    }

    private enum class DisplayMode {
        MESSAGE_LIST, MESSAGE_VIEW, SPLIT_VIEW
    }

    private class LaunchData(
        val search: LocalSearch,
        val account: Account? = null,
        val messageReference: MessageReference? = null,
        val noThreading: Boolean = false,
        val messageViewOnly: Boolean = false,
    )

    companion object : KoinComponent {
        private const val EXTRA_SEARCH = "search_bytes"
        private const val EXTRA_NO_THREADING = "no_threading"

        private const val ACTION_SHORTCUT = "shortcut"
        private const val EXTRA_SPECIAL_FOLDER = "special_folder"

        const val EXTRA_ACCOUNT = "account_uuid"
        private const val EXTRA_MESSAGE_REFERENCE = "message_reference"
        private const val EXTRA_MESSAGE_VIEW_ONLY = "message_view_only"

        // used for remote search
        const val EXTRA_SEARCH_ACCOUNT = "com.fsck.k9.search_account"
        private const val EXTRA_SEARCH_FOLDER = "com.fsck.k9.search_folder"

        private const val STATE_DISPLAY_MODE = "displayMode"
        private const val STATE_MESSAGE_VIEW_ONLY = "messageViewOnly"
        private const val STATE_MESSAGE_LIST_WAS_DISPLAYED = "messageListWasDisplayed"
        private const val STATE_SEARCH_VIEW_ICONIFIED = "searchViewIconified"
        private const val STATE_SEARCH_VIEW_QUERY = "searchViewQuery"

        private const val FIRST_FRAGMENT_TRANSACTION = "first"
        private const val FRAGMENT_TAG_MESSAGE_VIEW_CONTAINER = "MessageViewContainerFragment"
        private const val FRAGMENT_TAG_PLACEHOLDER = "MessageViewPlaceholder"

        private const val REQUEST_CODE_MASK = 0xFFFF0000.toInt()
        private const val REQUEST_FLAG_PENDING_INTENT = 1 shl 15

        private val defaultFolderProvider: DefaultFolderProvider by inject()

        @JvmStatic
        @JvmOverloads
        fun actionDisplaySearch(
            context: Context,
            search: SearchSpecification?,
            noThreading: Boolean,
            newTask: Boolean,
            clearTop: Boolean = true,
        ) {
            context.startActivity(intentDisplaySearch(context, search, noThreading, newTask, clearTop))
        }

        @JvmStatic
        fun intentDisplaySearch(
            context: Context?,
            search: SearchSpecification?,
            noThreading: Boolean,
            newTask: Boolean,
            clearTop: Boolean,
        ): Intent {
            return Intent(context, MessageList::class.java).apply {
                putExtra(EXTRA_SEARCH, ParcelableUtil.marshall(search))
                putExtra(EXTRA_NO_THREADING, noThreading)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                if (clearTop) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun createUnifiedInboxIntent(context: Context, account: Account): Intent {
            return Intent(context, MessageList::class.java).apply {
                val search = SearchAccount.createUnifiedInboxAccount().relatedSearch

                putExtra(EXTRA_ACCOUNT, account.uuid)
                putExtra(EXTRA_SEARCH, ParcelableUtil.marshall(search))
                putExtra(EXTRA_NO_THREADING, false)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun createNewMessagesIntent(context: Context, account: Account): Intent {
            val search = LocalSearch().apply {
                id = SearchAccount.NEW_MESSAGES
                addAccountUuid(account.uuid)
                and(SearchField.NEW_MESSAGE, "1", SearchSpecification.Attribute.EQUALS)
            }

            return intentDisplaySearch(context, search, noThreading = false, newTask = true, clearTop = true)
        }

        @JvmStatic
        fun shortcutIntent(context: Context?, specialFolder: String?): Intent {
            return Intent(context, MessageList::class.java).apply {
                action = ACTION_SHORTCUT
                putExtra(EXTRA_SPECIAL_FOLDER, specialFolder)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        @JvmStatic
        fun shortcutIntentForAccount(context: Context?, account: Account): Intent {
            return Intent(context, MessageList::class.java).apply {
                action = ACTION_SHORTCUT
                putExtra(EXTRA_ACCOUNT, account.uuid)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun actionDisplayMessageIntent(
            context: Context,
            messageReference: MessageReference,
            openInUnifiedInbox: Boolean = false,
            messageViewOnly: Boolean = false,
        ): Intent {
            return actionDisplayMessageTemplateIntent(context, openInUnifiedInbox, messageViewOnly).apply {
                putExtra(EXTRA_MESSAGE_REFERENCE, messageReference.toIdentityString())
            }
        }

        fun actionDisplayMessageTemplateIntent(
            context: Context,
            openInUnifiedInbox: Boolean,
            messageViewOnly: Boolean,
        ): Intent {
            return Intent(context, MessageList::class.java).apply {
                if (openInUnifiedInbox) {
                    val search = SearchAccount.createUnifiedInboxAccount().relatedSearch
                    putExtra(EXTRA_SEARCH, ParcelableUtil.marshall(search))
                }

                putExtra(EXTRA_MESSAGE_VIEW_ONLY, messageViewOnly)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        fun actionDisplayMessageTemplateFillIntent(messageReference: MessageReference): Intent {
            return Intent().apply {
                putExtra(EXTRA_MESSAGE_REFERENCE, messageReference.toIdentityString())
            }
        }

        @JvmStatic
        fun launch(context: Context) {
            val intent = Intent(context, MessageList::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            context.startActivity(intent)
        }

        @JvmStatic
        fun launch(context: Context, account: Account) {
            val folderId = defaultFolderProvider.getDefaultFolder(account)

            val search = LocalSearch().apply {
                addAllowedFolder(folderId)
                addAccountUuid(account.uuid)
            }

            actionDisplaySearch(context, search, noThreading = false, newTask = false)
        }
    }
}

private fun Intent.hasByteArrayExtra(name: String): Boolean {
    return getByteArrayExtra(name) != null
}
