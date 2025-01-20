package com.fsck.k9.ui.messagelist

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.view.ActionMode
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.k9mail.legacy.account.Account
import app.k9mail.legacy.account.Account.Expunge
import app.k9mail.legacy.account.Account.SortType
import app.k9mail.legacy.account.AccountManager
import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import app.k9mail.legacy.search.LocalSearch
import app.k9mail.legacy.search.SearchAccount
import app.k9mail.legacy.ui.folder.FolderNameFormatter
import app.k9mail.ui.utils.itemtouchhelper.ItemTouchHelper
import app.k9mail.ui.utils.linearlayoutmanager.LinearLayoutManager
import com.fsck.k9.K9
import com.fsck.k9.SwipeAction
import com.fsck.k9.activity.FolderInfoHolder
import com.fsck.k9.activity.Search
import com.fsck.k9.activity.misc.ContactPicture
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.fragment.ConfirmationDialogFragment
import com.fsck.k9.fragment.ConfirmationDialogFragment.ConfirmationDialogFragmentListener
import com.fsck.k9.helper.Utility
import com.fsck.k9.helper.mapToSet
import com.fsck.k9.mail.Flag
import com.fsck.k9.mail.MessagingException
import com.fsck.k9.search.getAccounts
import com.fsck.k9.ui.R
import com.fsck.k9.ui.changelog.RecentChangesActivity
import com.fsck.k9.ui.changelog.RecentChangesViewModel
import com.fsck.k9.ui.choosefolder.ChooseFolderActivity
import com.fsck.k9.ui.helper.RelativeDateTimeFormatter
import com.fsck.k9.ui.messagelist.MessageListFragment.MessageListFragmentListener.Companion.MAX_PROGRESS
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import java.util.concurrent.Future
import kotlinx.datetime.Clock
import net.jcip.annotations.GuardedBy
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import timber.log.Timber

private const val MAXIMUM_MESSAGE_SORT_OVERRIDES = 3
private const val MINIMUM_CLICK_INTERVAL = 200L
private const val RECENT_CHANGES_SNACKBAR_DURATION = 10 * 1000

class MessageListFragment :
    Fragment(),
    ConfirmationDialogFragmentListener,
    MessageListItemActionListener {

    val viewModel: MessageListViewModel by viewModel()
    private val recentChangesViewModel: RecentChangesViewModel by viewModel()

    private val sortTypeToastProvider: SortTypeToastProvider by inject()
    private val folderNameFormatter: FolderNameFormatter by inject { parametersOf(requireContext()) }
    private val messagingController: MessagingController by inject()
    private val accountManager: AccountManager by inject()
    private val clock: Clock by inject()

    private val handler = MessageListHandler(this)
    private val activityListener = MessageListActivityListener()
    private val actionModeCallback = ActionModeCallback()

    private lateinit var fragmentListener: MessageListFragmentListener

    private lateinit var recentChangesSnackbar: Snackbar
    private var recyclerView: RecyclerView? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var floatingActionButton: FloatingActionButton? = null

    private lateinit var adapter: MessageListAdapter

    private lateinit var accountUuids: Array<String>
    private var account: Account? = null
    private var currentFolder: FolderInfoHolder? = null
    private var remoteSearchFuture: Future<*>? = null
    private var extraSearchResults: List<String>? = null
    private var threadTitle: String? = null
    private var allAccounts = false
    private var sortType = SortType.SORT_DATE
    private var sortAscending = true
    private var sortDateAscending = false
    private var actionMode: ActionMode? = null
    private var hasConnectivity: Boolean? = null
    private var isShowFloatingActionButton: Boolean = true

    /**
     * Relevant messages for the current context when we have to remember the chosen messages
     * between user interactions (e.g. selecting a folder for move operation).
     */
    private var activeMessages: List<MessageReference>? = null
    private var showingThreadedList = false
    private var isThreadDisplay = false
    private var activeMessage: MessageReference? = null
    private var rememberedSelected: Set<Long>? = null
    private var lastMessageClick = 0L

    lateinit var localSearch: LocalSearch
        private set
    var isSingleAccountMode = false
        private set
    private var isSingleFolderMode = false
    private var isRemoteSearch = false
    private var initialMessageListLoad = true

    private val isUnifiedInbox: Boolean
        get() = localSearch.id == SearchAccount.UNIFIED_INBOX

    private val isNewMessagesView: Boolean
        get() = localSearch.id == SearchAccount.NEW_MESSAGES

    /**
     * `true` after [.onCreate] was executed. Used in [.updateTitle] to
     * make sure we don't access member variables before initialization is complete.
     */
    private var isInitialized = false

    private var error: Error? = null

    /**
     * Set this to `true` when the fragment should be considered active. When active, the fragment adds its actions to
     * the toolbar. When inactive, the fragment won't add its actions to the toolbar, even it is still visible, e.g. as
     * part of an animation.
     */
    var isActive: Boolean = false
        set(value) {
            field = value
            resetActionMode()
            invalidateMenu()
            maybeHideFloatingActionButton()
        }

    val isShowAccountChip: Boolean
        get() = isUnifiedInbox || !isSingleAccountMode

    override fun onAttach(context: Context) {
        super.onAttach(context)

        fragmentListener = try {
            context as MessageListFragmentListener
        } catch (e: ClassCastException) {
            error("${context.javaClass} must implement MessageListFragmentListener")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        restoreInstanceState(savedInstanceState)
        val error = decodeArguments()
        if (error != null) {
            this.error = error
            return
        }

        viewModel.getMessageListLiveData().observe(this) { messageListInfo: MessageListInfo ->
            setMessageList(messageListInfo)
        }

        adapter = createMessageListAdapter()

        isInitialized = true
    }

    private fun restoreInstanceState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        activeMessages = savedInstanceState.getStringArray(STATE_ACTIVE_MESSAGES)?.map { MessageReference.parse(it)!! }
        restoreSelectedMessages(savedInstanceState)
        isRemoteSearch = savedInstanceState.getBoolean(STATE_REMOTE_SEARCH_PERFORMED)
        val messageReferenceString = savedInstanceState.getString(STATE_ACTIVE_MESSAGE)
        activeMessage = MessageReference.parse(messageReferenceString)
    }

    private fun restoreSelectedMessages(savedInstanceState: Bundle) {
        rememberedSelected = savedInstanceState.getLongArray(STATE_SELECTED_MESSAGES)?.toSet()
    }

    private fun decodeArguments(): Error? {
        val arguments = requireArguments()
        showingThreadedList = arguments.getBoolean(ARG_THREADED_LIST, false)
        isThreadDisplay = arguments.getBoolean(ARG_IS_THREAD_DISPLAY, false)
        localSearch = BundleCompat.getParcelable(arguments, ARG_SEARCH, LocalSearch::class.java)!!

        allAccounts = localSearch.searchAllAccounts()
        val searchAccounts = localSearch.getAccounts(accountManager)
        if (searchAccounts.size == 1) {
            isSingleAccountMode = true
            val singleAccount = searchAccounts[0]
            account = singleAccount
            accountUuids = arrayOf(singleAccount.uuid)
        } else {
            isSingleAccountMode = false
            account = null
            accountUuids = searchAccounts.map { it.uuid }.toTypedArray()
        }

        isSingleFolderMode = false
        if (isSingleAccountMode && localSearch.folderIds.size == 1) {
            try {
                val folderId = localSearch.folderIds[0]
                currentFolder = getFolderInfoHolder(folderId, account!!)
                isSingleFolderMode = true
            } catch (e: MessagingException) {
                return Error.FolderNotFound
            }
        }

        return null
    }

    private fun createMessageListAdapter(): MessageListAdapter {
        return MessageListAdapter(
            theme = requireActivity().theme,
            res = resources,
            layoutInflater = layoutInflater,
            contactsPictureLoader = ContactPicture.getContactPictureLoader(),
            listItemListener = this,
            appearance = messageListAppearance,
            relativeDateTimeFormatter = RelativeDateTimeFormatter(requireContext(), clock),
        ).apply {
            activeMessage = this@MessageListFragment.activeMessage
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return if (error == null) {
            inflater.inflate(R.layout.message_list_fragment, container, false)
        } else {
            inflater.inflate(R.layout.message_list_error, container, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (error == null) {
            initializeMessageListLayout(view)
        } else {
            initializeErrorLayout(view)
        }
    }

    private fun initializeErrorLayout(view: View) {
        val errorMessageView = view.findViewById<MaterialTextView>(R.id.message_list_error_message)
        errorMessageView.text = getString(error!!.errorText)
    }

    private fun initializeMessageListLayout(view: View) {
        initializeSwipeRefreshLayout(view)
        initializeFloatingActionButton(view)
        initializeRecyclerView(view)
        initializeRecentChangesSnackbar()

        // This needs to be done before loading the message list below
        initializeSortSettings()

        loadMessageList()
    }

    private fun initializeSwipeRefreshLayout(view: View) {
        val swipeRefreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.swiperefresh)

        if (isRemoteSearchAllowed) {
            swipeRefreshLayout.setOnRefreshListener { onRemoteSearchRequested() }
        } else if (isCheckMailSupported) {
            swipeRefreshLayout.setOnRefreshListener { checkMail() }
        }

        // Disable pull-to-refresh until the message list has been loaded
        swipeRefreshLayout.isEnabled = false

        this.swipeRefreshLayout = swipeRefreshLayout
    }

    private fun initializeFloatingActionButton(view: View) {
        isShowFloatingActionButton = K9.isShowComposeButtonOnMessageList
        if (isShowFloatingActionButton) {
            enableFloatingActionButton(view)
        } else {
            disableFloatingActionButton(view)
        }
    }

    private fun enableFloatingActionButton(view: View) {
        val floatingActionButton = view.findViewById<FloatingActionButton>(R.id.floating_action_button)

        floatingActionButton.setOnClickListener {
            onCompose()
        }

        this.floatingActionButton = floatingActionButton
    }

    private fun disableFloatingActionButton(view: View) {
        val floatingActionButton = view.findViewById<FloatingActionButton>(R.id.floating_action_button)
        floatingActionButton.isGone = true
    }

    private fun initializeRecyclerView(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.message_list)

        if (!isShowFloatingActionButton) {
            recyclerView.setPadding(0)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.itemAnimator = MessageListItemAnimator()

        val itemTouchHelper = ItemTouchHelper(
            MessageListSwipeCallback(
                requireContext(),
                resourceProvider = SwipeResourceProvider(requireContext()),
                swipeActionSupportProvider,
                swipeRightAction = K9.swipeRightAction,
                swipeLeftAction = K9.swipeLeftAction,
                adapter,
                swipeListener,
            ),
        )
        itemTouchHelper.attachToRecyclerView(recyclerView)

        recyclerView.adapter = adapter

        this.recyclerView = recyclerView
        this.itemTouchHelper = itemTouchHelper
    }

    private val shouldShowRecentChangesHintObserver = Observer<Boolean> { showRecentChangesHint ->
        val recentChangesSnackbarVisible = recentChangesSnackbar.isShown
        if (showRecentChangesHint && !recentChangesSnackbarVisible) {
            recentChangesSnackbar.show()
        } else if (!showRecentChangesHint && recentChangesSnackbarVisible) {
            recentChangesSnackbar.dismiss()
        }
    }

    private fun initializeRecentChangesSnackbar() {
        val coordinatorLayout = requireView().findViewById<View>(R.id.message_list_coordinator)

        recentChangesSnackbar = Snackbar
            .make(coordinatorLayout, R.string.changelog_snackbar_text, RECENT_CHANGES_SNACKBAR_DURATION)
            .setAction(R.string.changelog_snackbar_button_text) { launchRecentChangesActivity() }
            .addCallback(
                object : BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_TIMEOUT) {
                            recentChangesViewModel.onRecentChangesHintDismissed()
                        }
                    }
                },
            )

        recentChangesViewModel.shouldShowRecentChangesHint
            .observe(viewLifecycleOwner, shouldShowRecentChangesHintObserver)
    }

    private fun launchRecentChangesActivity() {
        recentChangesViewModel.shouldShowRecentChangesHint.removeObserver(shouldShowRecentChangesHintObserver)

        val intent = Intent(requireActivity(), RecentChangesActivity::class.java)
        startActivity(intent)
    }

    private fun initializeSortSettings() {
        if (isSingleAccountMode) {
            val account = this.account!!
            sortType = account.sortType
            sortAscending = account.isSortAscending(sortType)
            sortDateAscending = account.isSortAscending(SortType.SORT_DATE)
        } else {
            sortType = K9.sortType
            sortAscending = K9.isSortAscending(sortType)
            sortDateAscending = K9.isSortAscending(SortType.SORT_DATE)
        }
    }

    private fun loadMessageList() {
        val config = MessageListConfig(
            localSearch,
            showingThreadedList,
            sortType,
            sortAscending,
            sortDateAscending,
            activeMessage,
            viewModel.messageSortOverrides.toMap(),
        )
        viewModel.loadMessageList(config)
    }

    fun folderLoading(folderId: Long, loading: Boolean) {
        currentFolder?.let {
            if (it.databaseId == folderId) {
                it.loading = loading
                updateFooterText()
            }
        }
    }

    fun updateTitle() {
        if (error != null) {
            fragmentListener.setMessageListTitle(getString(R.string.message_list_error_title))
            return
        } else if (!isInitialized) {
            return
        }

        setWindowTitle()

        if (!localSearch.isManualSearch) {
            setWindowProgress()
        }
    }

    private fun setWindowProgress() {
        var level = 0
        if (currentFolder?.loading == true) {
            val folderTotal = activityListener.getFolderTotal()
            if (folderTotal > 0) {
                level = (MAX_PROGRESS * activityListener.getFolderCompleted() / folderTotal).coerceAtMost(MAX_PROGRESS)
            }
        }

        fragmentListener.setMessageListProgress(level)
    }

    private fun setWindowTitle() {
        val title = when {
            isUnifiedInbox -> getString(R.string.integrated_inbox_title)
            isNewMessagesView -> getString(R.string.new_messages_title)
            isManualSearch -> getString(R.string.search_results)
            isThreadDisplay -> threadTitle ?: ""
            isSingleFolderMode -> currentFolder!!.displayName
            else -> ""
        }

        val subtitle = account.let { account ->
            if (account == null || isUnifiedInbox || accountManager.getAccounts().size == 1) {
                null
            } else {
                account.displayName
            }
        }

        fragmentListener.setMessageListTitle(title, subtitle)
    }

    fun progress(progress: Boolean) {
        if (!progress) {
            swipeRefreshLayout?.isRefreshing = false
        }

        fragmentListener.setMessageListProgressEnabled(progress)
    }

    override fun onFooterClicked() {
        val currentFolder = this.currentFolder ?: return

        if (currentFolder.moreMessages && !localSearch.isManualSearch) {
            val folderId = currentFolder.databaseId
            messagingController.loadMoreMessages(account, folderId)
        } else if (isRemoteSearch) {
            val additionalSearchResults = extraSearchResults ?: return
            if (additionalSearchResults.isEmpty()) return

            val loadSearchResults: List<String>

            val limit = account!!.remoteSearchNumResults
            if (limit in 1 until additionalSearchResults.size) {
                extraSearchResults = additionalSearchResults.subList(limit, additionalSearchResults.size)
                loadSearchResults = additionalSearchResults.subList(0, limit)
            } else {
                extraSearchResults = null
                loadSearchResults = additionalSearchResults
                updateFooterText(null)
            }

            messagingController.loadSearchResults(
                account,
                currentFolder.databaseId,
                loadSearchResults,
                activityListener,
            )
        }
    }

    override fun onMessageClicked(messageListItem: MessageListItem) {
        if (!isActive) {
            // Ignore click events that are delivered after the Fragment is no longer active. This could happen when
            // the user taps two messages at almost the same time and the first tap opens a new MessageListFragment.
            return
        }

        val clickTime = SystemClock.elapsedRealtime()
        if (clickTime - lastMessageClick < MINIMUM_CLICK_INTERVAL) return

        if (adapter.selectedCount > 0) {
            toggleMessageSelect(messageListItem)
        } else {
            lastMessageClick = clickTime
            if (showingThreadedList && messageListItem.threadCount > 1) {
                fragmentListener.showThread(messageListItem.account, messageListItem.threadRoot)
            } else {
                openMessage(messageListItem.messageReference)
            }
        }
    }

    override fun onDestroyView() {
        recyclerView = null
        itemTouchHelper = null
        swipeRefreshLayout = null
        floatingActionButton = null

        if (isNewMessagesView && !requireActivity().isChangingConfigurations) {
            messagingController.clearNewMessages(account)
        }

        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (error != null) return

        outState.putLongArray(STATE_SELECTED_MESSAGES, adapter.selected.toLongArray())
        outState.putBoolean(STATE_REMOTE_SEARCH_PERFORMED, isRemoteSearch)
        outState.putStringArray(
            STATE_ACTIVE_MESSAGES,
            activeMessages?.map(MessageReference::toIdentityString)?.toTypedArray(),
        )
        if (activeMessage != null) {
            outState.putString(STATE_ACTIVE_MESSAGE, activeMessage!!.toIdentityString())
        }
    }

    private val messageListAppearance: MessageListAppearance
        get() = MessageListAppearance(
            fontSizes = K9.fontSizes,
            previewLines = K9.messageListPreviewLines,
            stars = !isOutbox && K9.isShowMessageListStars,
            senderAboveSubject = K9.isMessageListSenderAboveSubject,
            showContactPicture = K9.isShowContactPicture,
            showingThreadedList = showingThreadedList,
            backGroundAsReadIndicator = K9.isUseBackgroundAsUnreadIndicator,
            showAccountChip = isShowAccountChip,
            density = K9.messageListDensity,
        )

    private fun getFolderInfoHolder(folderId: Long, account: Account): FolderInfoHolder {
        val localFolder = MlfUtils.getOpenFolder(folderId, account)
        return FolderInfoHolder(folderNameFormatter, localFolder, account)
    }

    override fun onResume() {
        super.onResume()

        if (hasConnectivity == null) {
            hasConnectivity = Utility.hasConnectivity(requireActivity().application)
        }

        messagingController.addListener(activityListener)

        updateTitle()
    }

    override fun onPause() {
        super.onPause()

        messagingController.removeListener(activityListener)
    }

    private fun goBack() {
        fragmentListener.goBack()
    }

    fun onCompose() {
        if (!isSingleAccountMode) {
            fragmentListener.onCompose(null)
        } else {
            fragmentListener.onCompose(account)
        }
    }

    private fun changeSort(sortType: SortType) {
        val sortAscending = if (this.sortType == sortType) !sortAscending else null
        changeSort(sortType, sortAscending)
    }

    private fun onRemoteSearchRequested() {
        val searchAccount = account!!.uuid
        val folderId = currentFolder!!.databaseId
        val queryString = localSearch.remoteSearchArguments

        isRemoteSearch = true
        swipeRefreshLayout?.isEnabled = false

        remoteSearchFuture = messagingController.searchRemoteMessages(
            searchAccount,
            folderId,
            queryString,
            null,
            null,
            activityListener,
        )

        invalidateMenu()
    }

    /**
     * Change the sort type and sort order used for the message list.
     *
     * @param sortType Specifies which field to use for sorting the message list.
     * @param sortAscending Specifies the sort order. If this argument is `null` the default search order for the
     *   sort type is used.
     */
    // FIXME: Don't save the changes in the UI thread
    private fun changeSort(sortType: SortType, sortAscending: Boolean?) {
        this.sortType = sortType

        val account = account
        if (account != null) {
            account.sortType = this.sortType
            if (sortAscending == null) {
                this.sortAscending = account.isSortAscending(this.sortType)
            } else {
                this.sortAscending = sortAscending
            }
            account.setSortAscending(this.sortType, this.sortAscending)
            sortDateAscending = account.isSortAscending(SortType.SORT_DATE)

            accountManager.saveAccount(account)
        } else {
            K9.sortType = this.sortType
            if (sortAscending == null) {
                this.sortAscending = K9.isSortAscending(this.sortType)
            } else {
                this.sortAscending = sortAscending
            }
            K9.setSortAscending(this.sortType, this.sortAscending)
            sortDateAscending = K9.isSortAscending(SortType.SORT_DATE)

            K9.saveSettingsAsync()
        }

        reSort()
    }

    private fun reSort() {
        val toastString = sortTypeToastProvider.getToast(sortType, sortAscending)
        Toast.makeText(activity, toastString, Toast.LENGTH_SHORT).show()
        loadMessageList()
    }

    fun onCycleSort() {
        val sortTypes = SortType.entries
        val currentIndex = sortTypes.indexOf(sortType)
        val newIndex = if (currentIndex == sortTypes.lastIndex) 0 else currentIndex + 1
        val nextSortType = sortTypes[newIndex]
        changeSort(nextSortType)
    }

    private fun onDelete(messages: List<MessageReference>) {
        if (K9.isConfirmDelete) {
            // remember the message selection for #onCreateDialog(int)
            activeMessages = messages
            showDialog(R.id.dialog_confirm_delete)
        } else {
            onDeleteConfirmed(messages)
        }
    }

    private fun onDeleteConfirmed(messages: List<MessageReference>) {
        if (showingThreadedList) {
            messagingController.deleteThreads(messages)
        } else {
            messagingController.deleteMessages(messages)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            ACTIVITY_CHOOSE_FOLDER_MOVE,
            ACTIVITY_CHOOSE_FOLDER_COPY,
            -> {
                if (data == null) return

                val destinationFolderId = data.getLongExtra(ChooseFolderActivity.RESULT_SELECTED_FOLDER_ID, -1L)
                val messages = activeMessages!!
                if (destinationFolderId != -1L) {
                    activeMessages = null

                    if (messages.isNotEmpty()) {
                        MlfUtils.setLastSelectedFolder(accountManager, messages, destinationFolderId)
                    }

                    when (requestCode) {
                        ACTIVITY_CHOOSE_FOLDER_MOVE -> move(messages, destinationFolderId)
                        ACTIVITY_CHOOSE_FOLDER_COPY -> copy(messages, destinationFolderId)
                    }
                }
            }
        }
    }

    private fun onExpunge() {
        currentFolder?.let { folderInfoHolder ->
            messagingController.expunge(account, folderInfoHolder.databaseId)
        }
    }

    private fun onEmptySpam() {
        if (isShowingSpamFolder) {
            showDialog(R.id.dialog_confirm_empty_spam)
        }
    }

    private val isShowingSpamFolder: Boolean
        get() {
            if (!isSingleFolderMode) return false
            return currentFolder!!.databaseId == account!!.spamFolderId
        }

    private fun onEmptyTrash() {
        if (isShowingTrashFolder) {
            showDialog(R.id.dialog_confirm_empty_trash)
        }
    }

    private val isShowingTrashFolder: Boolean
        get() {
            if (!isSingleFolderMode) return false
            return currentFolder!!.databaseId == account!!.trashFolderId
        }

    private fun showDialog(dialogId: Int) {
        val dialogFragment = when (dialogId) {
            R.id.dialog_confirm_spam -> {
                val title = getString(R.string.dialog_confirm_spam_title)
                val selectionSize = activeMessages!!.size
                val message = resources.getQuantityString(
                    R.plurals.dialog_confirm_spam_message,
                    selectionSize,
                    selectionSize,
                )
                val confirmText = getString(R.string.dialog_confirm_spam_confirm_button)
                val cancelText = getString(R.string.dialog_confirm_spam_cancel_button)
                ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText)
            }

            R.id.dialog_confirm_delete -> {
                val title = getString(R.string.dialog_confirm_delete_title)
                val selectionSize = activeMessages!!.size
                val message = resources.getQuantityString(
                    R.plurals.dialog_confirm_delete_messages,
                    selectionSize,
                    selectionSize,
                )
                val confirmText = getString(R.string.dialog_confirm_delete_confirm_button)
                val cancelText = getString(R.string.dialog_confirm_delete_cancel_button)
                ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText)
            }

            R.id.dialog_confirm_mark_all_as_read -> {
                val title = getString(R.string.dialog_confirm_mark_all_as_read_title)
                val message = getString(R.string.dialog_confirm_mark_all_as_read_message)
                val confirmText = getString(R.string.dialog_confirm_mark_all_as_read_confirm_button)
                val cancelText = getString(R.string.dialog_confirm_mark_all_as_read_cancel_button)
                ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText)
            }

            R.id.dialog_confirm_empty_spam -> {
                val title = getString(R.string.dialog_confirm_empty_spam_title)
                val message = getString(R.string.dialog_confirm_empty_spam_message)
                val confirmText = getString(R.string.dialog_confirm_delete_confirm_button)
                val cancelText = getString(R.string.dialog_confirm_delete_cancel_button)
                ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText)
            }

            R.id.dialog_confirm_empty_trash -> {
                val title = getString(R.string.dialog_confirm_empty_trash_title)
                val message = getString(R.string.dialog_confirm_empty_trash_message)
                val confirmText = getString(R.string.dialog_confirm_delete_confirm_button)
                val cancelText = getString(R.string.dialog_confirm_delete_cancel_button)
                ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText)
            }

            else -> {
                throw RuntimeException("Called showDialog(int) with unknown dialog id.")
            }
        }

        dialogFragment.setTargetFragment(this, dialogId)
        dialogFragment.show(parentFragmentManager, getDialogTag(dialogId))
    }

    private fun getDialogTag(dialogId: Int): String {
        return "dialog-$dialogId"
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        if (isActive && error == null) {
            prepareMenu(menu)
        } else {
            hideMenu(menu)
        }
    }

    private fun prepareMenu(menu: Menu) {
        menu.findItem(R.id.compose).isVisible = !isShowFloatingActionButton
        menu.findItem(R.id.set_sort).isVisible = true
        menu.findItem(R.id.select_all).isVisible = true
        menu.findItem(R.id.mark_all_as_read).isVisible = isMarkAllAsReadSupported
        menu.findItem(R.id.empty_spam).isVisible = isShowingSpamFolder
        menu.findItem(R.id.empty_trash).isVisible = isShowingTrashFolder

        if (isSingleAccountMode) {
            menu.findItem(R.id.send_messages).isVisible = isOutbox
            menu.findItem(R.id.expunge).isVisible = isRemoteFolder && shouldShowExpungeAction()
        } else {
            menu.findItem(R.id.send_messages).isVisible = false
            menu.findItem(R.id.expunge).isVisible = false
        }

        menu.findItem(R.id.search).isVisible = !isManualSearch
        menu.findItem(R.id.search_remote).isVisible = !isRemoteSearch && isRemoteSearchAllowed
        menu.findItem(R.id.search_everywhere).isVisible = isManualSearch && !localSearch.searchAllAccounts()
    }

    private fun hideMenu(menu: Menu) {
        menu.findItem(R.id.compose).isVisible = false
        menu.findItem(R.id.search).isVisible = false
        menu.findItem(R.id.search_remote).isVisible = false
        menu.findItem(R.id.set_sort).isVisible = false
        menu.findItem(R.id.select_all).isVisible = false
        menu.findItem(R.id.mark_all_as_read).isVisible = false
        menu.findItem(R.id.send_messages).isVisible = false
        menu.findItem(R.id.empty_spam).isVisible = false
        menu.findItem(R.id.empty_trash).isVisible = false
        menu.findItem(R.id.expunge).isVisible = false
        menu.findItem(R.id.search_everywhere).isVisible = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.search_remote -> onRemoteSearch()
            R.id.compose -> onCompose()
            R.id.set_sort_date -> changeSort(SortType.SORT_DATE)
            R.id.set_sort_arrival -> changeSort(SortType.SORT_ARRIVAL)
            R.id.set_sort_subject -> changeSort(SortType.SORT_SUBJECT)
            R.id.set_sort_sender -> changeSort(SortType.SORT_SENDER)
            R.id.set_sort_flag -> changeSort(SortType.SORT_FLAGGED)
            R.id.set_sort_unread -> changeSort(SortType.SORT_UNREAD)
            R.id.set_sort_attach -> changeSort(SortType.SORT_ATTACHMENT)
            R.id.select_all -> selectAll()
            R.id.mark_all_as_read -> confirmMarkAllAsRead()
            R.id.send_messages -> onSendPendingMessages()
            R.id.empty_spam -> onEmptySpam()
            R.id.empty_trash -> onEmptyTrash()
            R.id.expunge -> onExpunge()
            R.id.search_everywhere -> onSearchEverywhere()
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    private fun onSearchEverywhere() {
        val searchQuery = requireActivity().intent.getStringExtra(SearchManager.QUERY)

        val searchIntent = Intent(requireContext(), Search::class.java).apply {
            action = Intent.ACTION_SEARCH
            putExtra(SearchManager.QUERY, searchQuery)

            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivity(searchIntent)
    }

    private fun onSendPendingMessages() {
        messagingController.sendPendingMessages(account, null)
    }

    private fun updateFooterText() {
        val currentFolder = this.currentFolder
        val account = this.account

        val footerText = if (initialMessageListLoad) {
            null
        } else if (localSearch.isManualSearch || currentFolder == null || account == null) {
            null
        } else if (currentFolder.loading) {
            getString(R.string.status_loading_more)
        } else if (!currentFolder.moreMessages) {
            null
        } else if (account.displayCount == 0) {
            getString(R.string.message_list_load_more_messages_action)
        } else {
            getString(R.string.load_more_messages_fmt, account.displayCount)
        }

        updateFooterText(footerText)
    }

    fun updateFooterText(text: String?) {
        adapter.footerText = text
    }

    private fun selectAll() {
        if (adapter.messages.isEmpty()) {
            // Nothing to do if there are no messages
            return
        }

        adapter.selectAll()

        if (actionMode == null) {
            startAndPrepareActionMode()
        }

        computeBatchDirection()
        updateActionMode()
    }

    private fun toggleMessageSelect(messageListItem: MessageListItem) {
        adapter.toggleSelection(messageListItem)
        updateAfterSelectionChange()
    }

    private fun selectMessage(messageListItem: MessageListItem) {
        adapter.selectMessage(messageListItem)
        updateAfterSelectionChange()
    }

    private fun deselectMessage(messageListItem: MessageListItem) {
        adapter.deselectMessage(messageListItem)
        updateAfterSelectionChange()
    }

    private fun isMessageSelected(messageListItem: MessageListItem): Boolean {
        return adapter.isSelected(messageListItem)
    }

    private fun updateAfterSelectionChange() {
        if (adapter.selectedCount == 0) {
            actionMode?.finish()
            actionMode = null
            return
        }

        if (actionMode == null) {
            startAndPrepareActionMode()
        }

        computeBatchDirection()
        updateActionMode()
    }

    override fun onToggleMessageSelection(item: MessageListItem) {
        toggleMessageSelect(item)
    }

    override fun onToggleMessageFlag(item: MessageListItem) {
        setFlag(item, Flag.FLAGGED, !item.isStarred)
    }

    private fun updateActionMode() {
        val actionMode = actionMode ?: error("actionMode == null")
        actionMode.title = getString(R.string.actionbar_selected, adapter.selectedCount)
        actionModeCallback.showSelectAll(!adapter.isAllSelected)

        actionMode.invalidate()
    }

    private fun computeBatchDirection() {
        val selectedMessages = adapter.selectedMessages
        val notAllRead = !selectedMessages.all { it.isRead }
        val notAllStarred = !selectedMessages.all { it.isStarred }

        actionModeCallback.showMarkAsRead(notAllRead)
        actionModeCallback.showFlag(notAllStarred)
    }

    private fun setFlag(messageListItem: MessageListItem, flag: Flag, newState: Boolean) {
        val account = messageListItem.account
        if (showingThreadedList && messageListItem.threadCount > 1) {
            val threadRootId = messageListItem.threadRoot
            messagingController.setFlagForThreads(account, listOf(threadRootId), flag, newState)
        } else {
            val messageId = messageListItem.databaseId
            messagingController.setFlag(account, listOf(messageId), flag, newState)
        }

        computeBatchDirection()
    }

    private fun setFlagForSelected(flag: Flag, newState: Boolean) {
        if (adapter.selected.isEmpty()) return

        val messageMap = mutableMapOf<Account, MutableList<Long>>()
        val threadMap = mutableMapOf<Account, MutableList<Long>>()
        val accounts = mutableSetOf<Account>()

        for (messageListItem in adapter.selectedMessages) {
            val account = messageListItem.account
            accounts.add(account)

            if (showingThreadedList && messageListItem.threadCount > 1) {
                val threadRootIdList = threadMap.getOrPut(account) { mutableListOf() }
                threadRootIdList.add(messageListItem.threadRoot)
            } else {
                val messageIdList = messageMap.getOrPut(account) { mutableListOf() }
                messageIdList.add(messageListItem.databaseId)
            }
        }

        for (account in accounts) {
            messageMap[account]?.let { messageIds ->
                messagingController.setFlag(account, messageIds, flag, newState)
            }

            threadMap[account]?.let { threadRootIds ->
                messagingController.setFlagForThreads(account, threadRootIds, flag, newState)
            }
        }

        computeBatchDirection()
    }

    private fun onMove(message: MessageReference) {
        onMove(listOf(message))
    }

    private fun onMove(messages: List<MessageReference>) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.MOVE)) return

        val folderId = when {
            isThreadDisplay -> messages.first().folderId
            isSingleFolderMode -> currentFolder!!.databaseId
            else -> null
        }

        displayFolderChoice(
            operation = FolderOperation.MOVE,
            requestCode = ACTIVITY_CHOOSE_FOLDER_MOVE,
            sourceFolderId = folderId,
            accountUuid = messages.first().accountUuid,
            lastSelectedFolderId = null,
            messages = messages,
        )
    }

    private fun onCopy(message: MessageReference) {
        onCopy(listOf(message))
    }

    private fun onCopy(messages: List<MessageReference>) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.COPY)) return

        val folderId = when {
            isThreadDisplay -> messages.first().folderId
            isSingleFolderMode -> currentFolder!!.databaseId
            else -> null
        }

        displayFolderChoice(
            operation = FolderOperation.COPY,
            requestCode = ACTIVITY_CHOOSE_FOLDER_COPY,
            sourceFolderId = folderId,
            accountUuid = messages.first().accountUuid,
            lastSelectedFolderId = null,
            messages = messages,
        )
    }

    private fun displayFolderChoice(
        operation: FolderOperation,
        requestCode: Int,
        sourceFolderId: Long?,
        accountUuid: String,
        lastSelectedFolderId: Long?,
        messages: List<MessageReference>,
    ) {
        val action = when (operation) {
            FolderOperation.COPY -> ChooseFolderActivity.Action.COPY
            FolderOperation.MOVE -> ChooseFolderActivity.Action.MOVE
        }
        val intent = ChooseFolderActivity.buildLaunchIntent(
            context = requireContext(),
            action = action,
            accountUuid = accountUuid,
            currentFolderId = sourceFolderId,
            scrollToFolderId = lastSelectedFolderId,
            messageReference = null,
        )

        // remember the selected messages for #onActivityResult
        activeMessages = messages

        startActivityForResult(intent, requestCode)
    }

    private fun onArchive(message: MessageReference) {
        onArchive(listOf(message))
    }

    private fun onArchive(messages: List<MessageReference>) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.MOVE)) return

        if (showingThreadedList) {
            messagingController.archiveThreads(messages)
        } else {
            messagingController.archiveMessages(messages)
        }
    }

    private fun groupMessagesByAccount(messages: List<MessageReference>): Map<Account, List<MessageReference>> {
        return messages.groupBy { accountManager.getAccount(it.accountUuid)!! }
    }

    private fun onSpam(messages: List<MessageReference>) {
        if (K9.isConfirmSpam) {
            // remember the message selection for #onCreateDialog(int)
            activeMessages = messages
            showDialog(R.id.dialog_confirm_spam)
        } else {
            onSpamConfirmed(messages)
        }
    }

    private fun onSpamConfirmed(messages: List<MessageReference>) {
        for ((account, messagesInAccount) in groupMessagesByAccount(messages)) {
            account.spamFolderId?.let { spamFolderId ->
                move(messagesInAccount, spamFolderId)
            }
        }
    }

    private fun checkCopyOrMovePossible(messages: List<MessageReference>, operation: FolderOperation): Boolean {
        if (messages.isEmpty()) return false

        val account = accountManager.getAccount(messages.first().accountUuid)
        if (operation == FolderOperation.MOVE && !messagingController.isMoveCapable(account) ||
            operation == FolderOperation.COPY && !messagingController.isCopyCapable(account)
        ) {
            return false
        }

        for (message in messages) {
            if (operation == FolderOperation.MOVE && !messagingController.isMoveCapable(message) ||
                operation == FolderOperation.COPY && !messagingController.isCopyCapable(message)
            ) {
                val toast = Toast.makeText(
                    activity,
                    R.string.move_copy_cannot_copy_unsynced_message,
                    Toast.LENGTH_LONG,
                )
                toast.show()
                return false
            }
        }

        return true
    }

    private fun copy(messages: List<MessageReference>, folderId: Long) {
        copyOrMove(messages, folderId, FolderOperation.COPY)
    }

    private fun move(messages: List<MessageReference>, folderId: Long) {
        copyOrMove(messages, folderId, FolderOperation.MOVE)
    }

    private fun copyOrMove(messages: List<MessageReference>, destinationFolderId: Long, operation: FolderOperation) {
        if (!checkCopyOrMovePossible(messages, operation)) return

        val folderMap = messages.asSequence()
            .filterNot { it.folderId == destinationFolderId }
            .groupBy { it.folderId }

        for ((folderId, messagesInFolder) in folderMap) {
            val account = accountManager.getAccount(messagesInFolder.first().accountUuid)

            if (operation == FolderOperation.MOVE) {
                if (showingThreadedList) {
                    messagingController.moveMessagesInThread(account, folderId, messagesInFolder, destinationFolderId)
                } else {
                    messagingController.moveMessages(account, folderId, messagesInFolder, destinationFolderId)
                }
            } else {
                if (showingThreadedList) {
                    messagingController.copyMessagesInThread(account, folderId, messagesInFolder, destinationFolderId)
                } else {
                    messagingController.copyMessages(account, folderId, messagesInFolder, destinationFolderId)
                }
            }
        }
    }

    private fun onMoveToDraftsFolder(messages: List<MessageReference>) {
        messagingController.moveToDraftsFolder(account, currentFolder!!.databaseId, messages)
        activeMessages = null
    }

    override fun doPositiveClick(dialogId: Int) {
        when (dialogId) {
            R.id.dialog_confirm_spam -> {
                onSpamConfirmed(activeMessages!!)
                activeMessages = null
            }

            R.id.dialog_confirm_delete -> {
                onDeleteConfirmed(activeMessages!!)
                activeMessage = null
                adapter.activeMessage = null
            }

            R.id.dialog_confirm_mark_all_as_read -> {
                markAllAsRead()
            }

            R.id.dialog_confirm_empty_spam -> {
                messagingController.emptySpam(account, null)
            }

            R.id.dialog_confirm_empty_trash -> {
                messagingController.emptyTrash(account, null)
            }
        }
    }

    override fun doNegativeClick(dialogId: Int) {
        if (dialogId == R.id.dialog_confirm_spam || dialogId == R.id.dialog_confirm_delete) {
            val activeMessages = this.activeMessages ?: return
            if (activeMessages.size == 1) {
                // List item might have been swiped and is still showing the "swipe action background"
                resetSwipedView(activeMessages.first())
            }

            this.activeMessages = null
        }
    }

    private fun resetSwipedView(messageReference: MessageReference) {
        val recyclerView = this.recyclerView ?: return
        val itemTouchHelper = this.itemTouchHelper ?: return

        adapter.getItem(messageReference)?.let { messageListItem ->
            recyclerView.findViewHolderForItemId(messageListItem.uniqueId)?.let { viewHolder ->
                itemTouchHelper.stopSwipe(viewHolder)
                notifyItemChanged(messageListItem)
            }
        }
    }

    override fun dialogCancelled(dialogId: Int) {
        doNegativeClick(dialogId)
    }

    private fun checkMail() {
        if (isSingleAccountMode && isSingleFolderMode) {
            val folderId = currentFolder!!.databaseId
            messagingController.synchronizeMailbox(account, folderId, false, activityListener)
            messagingController.sendPendingMessages(account, activityListener)
        } else if (allAccounts) {
            messagingController.checkMail(null, true, true, false, activityListener)
        } else {
            for (accountUuid in accountUuids) {
                val account = accountManager.getAccount(accountUuid)
                messagingController.checkMail(account, true, true, false, activityListener)
            }
        }
    }

    override fun onStop() {
        // If we represent a remote search, then kill that before going back.
        if (isRemoteSearch && remoteSearchFuture != null) {
            try {
                Timber.i("Remote search in progress, attempting to abort...")

                // Canceling the future stops any message fetches in progress.
                val cancelSuccess = remoteSearchFuture!!.cancel(true) // mayInterruptIfRunning = true
                if (!cancelSuccess) {
                    Timber.e("Could not cancel remote search future.")
                }

                // Closing the folder will kill off the connection if we're mid-search.
                val searchAccount = account!!

                // Send a remoteSearchFinished() message for good measure.
                activityListener.remoteSearchFinished(
                    currentFolder!!.databaseId,
                    0,
                    searchAccount.remoteSearchNumResults,
                    null,
                )
            } catch (e: Exception) {
                // Since the user is going back, log and squash any exceptions.
                Timber.e(e, "Could not abort remote search before going back")
            }
        }

        super.onStop()
    }

    fun openMessage(messageReference: MessageReference) {
        fragmentListener.openMessage(messageReference)
    }

    fun onReverseSort() {
        changeSort(sortType)
    }

    private val selectedMessage: MessageReference?
        get() = selectedMessageListItem?.messageReference

    private val selectedMessageListItem: MessageListItem?
        get() {
            val recyclerView = recyclerView ?: return null
            val focusedView = recyclerView.focusedChild ?: return null
            val viewHolder = recyclerView.findContainingViewHolder(focusedView) as? MessageViewHolder ?: return null
            return adapter.getItemById(viewHolder.uniqueId)
        }

    private val selectedMessages: List<MessageReference>
        get() = adapter.selectedMessages.map { it.messageReference }

    fun onDelete() {
        selectedMessage?.let { message ->
            onDelete(listOf(message))
        }
    }

    fun toggleMessageSelect() {
        selectedMessageListItem?.let { messageListItem ->
            toggleMessageSelect(messageListItem)
        }
    }

    fun onToggleFlagged() {
        selectedMessageListItem?.let { messageListItem ->
            setFlag(messageListItem, Flag.FLAGGED, !messageListItem.isStarred)
        }
    }

    fun onToggleRead() {
        selectedMessageListItem?.let { messageListItem ->
            setFlag(messageListItem, Flag.SEEN, !messageListItem.isRead)
        }
    }

    fun onMove() {
        selectedMessage?.let { message ->
            onMove(message)
        }
    }

    fun onArchive() {
        selectedMessage?.let { message ->
            onArchive(message)
        }
    }

    fun onCopy() {
        selectedMessage?.let { message ->
            onCopy(message)
        }
    }

    val isOutbox: Boolean
        get() = isSpecialFolder(account?.outboxFolderId)

    private val isInbox: Boolean
        get() = isSpecialFolder(account?.inboxFolderId)

    private val isArchiveFolder: Boolean
        get() = isSpecialFolder(account?.archiveFolderId)

    private val isSpamFolder: Boolean
        get() = isSpecialFolder(account?.spamFolderId)

    private fun isSpecialFolder(specialFolderId: Long?): Boolean {
        val folderId = specialFolderId ?: return false
        val currentFolder = currentFolder ?: return false
        return currentFolder.databaseId == folderId
    }

    private val isRemoteFolder: Boolean
        get() {
            if (localSearch.isManualSearch || isOutbox) return false

            return if (!messagingController.isMoveCapable(account)) {
                // For POP3 accounts only the Inbox is a remote folder.
                isInbox
            } else {
                true
            }
        }

    private val isManualSearch: Boolean
        get() = localSearch.isManualSearch

    private fun shouldShowExpungeAction(): Boolean {
        val account = this.account ?: return false
        return account.expungePolicy == Expunge.EXPUNGE_MANUALLY && messagingController.supportsExpunge(account)
    }

    private fun onRemoteSearch() {
        // Remote search is useless without the network.
        if (hasConnectivity == true) {
            onRemoteSearchRequested()
        } else {
            Toast.makeText(activity, getText(R.string.remote_search_unavailable_no_network), Toast.LENGTH_SHORT).show()
        }
    }

    private val isRemoteSearchAllowed: Boolean
        get() = isManualSearch && !isRemoteSearch && isSingleFolderMode && messagingController.isPushCapable(account)

    fun onSearchRequested(query: String): Boolean {
        val folderId = currentFolder?.databaseId
        return fragmentListener.startSearch(query, account, folderId)
    }

    private fun setMessageList(messageListInfo: MessageListInfo) {
        val messageListItems = messageListInfo.messageListItems
        if (isThreadDisplay && messageListItems.isEmpty()) {
            goBack()
            return
        }

        swipeRefreshLayout?.let { swipeRefreshLayout ->
            swipeRefreshLayout.isRefreshing = false
            swipeRefreshLayout.isEnabled = isPullToRefreshAllowed
        }

        if (isThreadDisplay) {
            if (messageListItems.isNotEmpty()) {
                val strippedSubject = messageListItems.first().subject?.let { Utility.stripSubject(it) }
                threadTitle = if (strippedSubject.isNullOrEmpty()) {
                    getString(R.string.general_no_subject)
                } else {
                    strippedSubject
                }
                updateTitle()
            } else {
                // TODO: empty thread view -> return to full message list
            }
        }

        adapter.messages = messageListItems

        rememberedSelected?.let {
            rememberedSelected = null
            adapter.restoreSelected(it)
        }

        resetActionMode()
        computeBatchDirection()

        invalidateMenu()

        initialMessageListLoad = false

        currentFolder?.let { currentFolder ->
            currentFolder.moreMessages = messageListInfo.hasMoreMessages
            updateFooterText()
        }
    }

    private fun resetActionMode() {
        if (!isResumed) return

        if (!isActive || adapter.selected.isEmpty()) {
            actionMode?.finish()
            actionMode = null
            return
        }

        if (actionMode == null) {
            startAndPrepareActionMode()
        }

        updateActionMode()
    }

    private fun startAndPrepareActionMode() {
        actionMode = fragmentListener.startSupportActionMode(actionModeCallback)
        actionMode?.invalidate()
    }

    fun finishActionMode() {
        actionMode?.finish()
    }

    fun remoteSearchFinished() {
        remoteSearchFuture = null
    }

    fun setActiveMessage(messageReference: MessageReference?) {
        activeMessage = messageReference

        rememberSortOverride(messageReference)

        // Reload message list with modified query that always includes the active message
        if (isAdded) {
            loadMessageList()
        }

        // Redraw list immediately
        if (::adapter.isInitialized) {
            adapter.activeMessage = activeMessage

            if (messageReference != null) {
                scrollToMessage(messageReference)
            }
        }
    }

    fun onFullyActive() {
        maybeShowFloatingActionButton()
    }

    private fun maybeShowFloatingActionButton() {
        floatingActionButton?.isVisible = true
    }

    private fun maybeHideFloatingActionButton() {
        floatingActionButton?.isGone = true
    }

    // For the last N displayed messages we remember the original 'read' and 'starred' state of the messages. We pass
    // this information to MessageListLoader so messages can be sorted according to these remembered values and not the
    // current state. This way messages, that are marked as read/unread or starred/not starred while being displayed,
    // won't immediately change position in the message list if the list is sorted by these fields.
    // The main benefit is that the swipe to next/previous message feature will work in a less surprising way.
    private fun rememberSortOverride(messageReference: MessageReference?) {
        val messageSortOverrides = viewModel.messageSortOverrides

        if (messageReference == null) {
            messageSortOverrides.clear()
            return
        }

        if (sortType != SortType.SORT_UNREAD && sortType != SortType.SORT_FLAGGED) return

        val messageListItem = adapter.getItem(messageReference) ?: return

        val existingEntry = messageSortOverrides.firstOrNull { it.first == messageReference }
        if (existingEntry != null) {
            messageSortOverrides.remove(existingEntry)
            messageSortOverrides.addLast(existingEntry)
        } else {
            messageSortOverrides.addLast(
                messageReference to MessageSortOverride(messageListItem.isRead, messageListItem.isStarred),
            )
            if (messageSortOverrides.size > MAXIMUM_MESSAGE_SORT_OVERRIDES) {
                messageSortOverrides.removeFirst()
            }
        }
    }

    private fun scrollToMessage(messageReference: MessageReference) {
        val recyclerView = recyclerView ?: return
        val messageListItem = adapter.getItem(messageReference) ?: return
        val position = adapter.getPosition(messageListItem) ?: return

        val linearLayoutManager = recyclerView.layoutManager as LinearLayoutManager
        val firstVisiblePosition = linearLayoutManager.findFirstCompletelyVisibleItemPosition()
        val lastVisiblePosition = linearLayoutManager.findLastCompletelyVisibleItemPosition()
        if (position !in firstVisiblePosition..lastVisiblePosition) {
            recyclerView.smoothScrollToPosition(position)
        }
    }

    private val isMarkAllAsReadSupported: Boolean
        get() = isSingleAccountMode && isSingleFolderMode && !isOutbox

    private fun confirmMarkAllAsRead() {
        if (K9.isConfirmMarkAllRead) {
            showDialog(R.id.dialog_confirm_mark_all_as_read)
        } else {
            markAllAsRead()
        }
    }

    private fun markAllAsRead() {
        if (isMarkAllAsReadSupported) {
            messagingController.markAllMessagesRead(account, currentFolder!!.databaseId)
        }
    }

    private fun invalidateMenu() {
        activity?.invalidateMenu()
    }

    private val isCheckMailSupported: Boolean
        get() = allAccounts || !isSingleAccountMode || !isSingleFolderMode || isRemoteFolder

    private val isCheckMailAllowed: Boolean
        get() = !isManualSearch && isCheckMailSupported

    private val isPullToRefreshAllowed: Boolean
        get() = isRemoteSearchAllowed || isCheckMailAllowed

    private var itemSelectedOnSwipeStart = false

    private val swipeListener = object : MessageListSwipeListener {
        override fun onSwipeStarted(item: MessageListItem, action: SwipeAction) {
            itemSelectedOnSwipeStart = isMessageSelected(item)
            if (itemSelectedOnSwipeStart && action != SwipeAction.ToggleSelection) {
                deselectMessage(item)
            }
        }

        override fun onSwipeActionChanged(item: MessageListItem, action: SwipeAction) {
            if (action == SwipeAction.ToggleSelection) {
                if (itemSelectedOnSwipeStart && !isMessageSelected(item)) {
                    selectMessage(item)
                }
            } else if (isMessageSelected(item)) {
                deselectMessage(item)
            }
        }

        override fun onSwipeAction(item: MessageListItem, action: SwipeAction) {
            if (action.removesItem || action == SwipeAction.ToggleSelection) {
                itemSelectedOnSwipeStart = false
            }

            when (action) {
                SwipeAction.None -> Unit
                SwipeAction.ToggleSelection -> {
                    toggleMessageSelect(item)
                }

                SwipeAction.ToggleRead -> {
                    setFlag(item, Flag.SEEN, !item.isRead)
                }

                SwipeAction.ToggleStar -> {
                    setFlag(item, Flag.FLAGGED, !item.isStarred)
                }

                SwipeAction.Archive -> {
                    onArchive(item.messageReference)
                }

                SwipeAction.Delete -> {
                    onDelete(listOf(item.messageReference))
                }

                SwipeAction.Spam -> {
                    onSpam(listOf(item.messageReference))
                }

                SwipeAction.Move -> {
                    val messageReference = item.messageReference
                    resetSwipedView(messageReference)
                    onMove(messageReference)
                }
            }
        }

        override fun onSwipeEnded(item: MessageListItem) {
            if (itemSelectedOnSwipeStart && !isMessageSelected(item)) {
                selectMessage(item)
            }
        }
    }

    private fun notifyItemChanged(item: MessageListItem) {
        val position = adapter.getPosition(item) ?: return
        adapter.notifyItemChanged(position)
    }

    private val swipeActionSupportProvider = SwipeActionSupportProvider { item, action ->
        when (action) {
            SwipeAction.None -> false
            SwipeAction.ToggleSelection -> true
            SwipeAction.ToggleRead -> !isOutbox
            SwipeAction.ToggleStar -> !isOutbox
            SwipeAction.Archive -> {
                !isOutbox && item.account.hasArchiveFolder() && item.folderId != item.account.archiveFolderId
            }

            SwipeAction.Delete -> true
            SwipeAction.Move -> !isOutbox && messagingController.isMoveCapable(item.account)
            SwipeAction.Spam -> !isOutbox && item.account.hasSpamFolder() && item.folderId != item.account.spamFolderId
        }
    }

    internal inner class MessageListActivityListener : SimpleMessagingListener() {
        private val lock = Any()

        @GuardedBy("lock")
        private var folderCompleted = 0

        @GuardedBy("lock")
        private var folderTotal = 0

        override fun remoteSearchFailed(folderServerId: String?, err: String?) {
            handler.post {
                activity?.let { activity ->
                    Toast.makeText(activity, R.string.remote_search_error, Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun remoteSearchStarted(folderId: Long) {
            handler.progress(true)
            handler.updateFooter(getString(R.string.remote_search_sending_query))
        }

        override fun enableProgressIndicator(enable: Boolean) {
            handler.progress(enable)
        }

        override fun remoteSearchFinished(
            folderId: Long,
            numResults: Int,
            maxResults: Int,
            extraResults: List<String>?,
        ) {
            handler.progress(false)
            handler.remoteSearchFinished()

            extraSearchResults = extraResults
            if (extraResults != null && extraResults.isNotEmpty()) {
                handler.updateFooter(String.format(getString(R.string.load_more_messages_fmt), maxResults))
            } else {
                handler.updateFooter(null)
            }
        }

        override fun remoteSearchServerQueryComplete(folderId: Long, numResults: Int, maxResults: Int) {
            handler.progress(true)

            val footerText = if (maxResults != 0 && numResults > maxResults) {
                resources.getQuantityString(
                    R.plurals.remote_search_downloading_limited,
                    maxResults,
                    maxResults,
                    numResults,
                )
            } else {
                resources.getQuantityString(R.plurals.remote_search_downloading, numResults, numResults)
            }

            handler.updateFooter(footerText)
            informUserOfStatus()
        }

        private fun informUserOfStatus() {
            handler.refreshTitle()
        }

        override fun synchronizeMailboxStarted(account: Account, folderId: Long) {
            if (updateForMe(account, folderId)) {
                handler.progress(true)
                handler.folderLoading(folderId, true)

                synchronized(lock) {
                    folderCompleted = 0
                    folderTotal = 0
                }

                informUserOfStatus()
            }
        }

        override fun synchronizeMailboxHeadersProgress(
            account: Account,
            folderServerId: String,
            completed: Int,
            total: Int,
        ) {
            synchronized(lock) {
                folderCompleted = completed
                folderTotal = total
            }

            informUserOfStatus()
        }

        override fun synchronizeMailboxHeadersFinished(
            account: Account,
            folderServerId: String,
            total: Int,
            completed: Int,
        ) {
            synchronized(lock) {
                folderCompleted = 0
                folderTotal = 0
            }

            informUserOfStatus()
        }

        override fun synchronizeMailboxProgress(account: Account, folderId: Long, completed: Int, total: Int) {
            synchronized(lock) {
                folderCompleted = completed
                folderTotal = total
            }

            informUserOfStatus()
        }

        override fun synchronizeMailboxFinished(account: Account, folderId: Long) {
            if (updateForMe(account, folderId)) {
                handler.progress(false)
                handler.folderLoading(folderId, false)
            }
        }

        override fun synchronizeMailboxFailed(account: Account, folderId: Long, message: String) {
            if (updateForMe(account, folderId)) {
                handler.progress(false)
                handler.folderLoading(folderId, false)
            }
        }

        override fun checkMailFinished(context: Context?, account: Account?) {
            handler.progress(false)
        }

        private fun updateForMe(account: Account?, folderId: Long): Boolean {
            if (account == null || account.uuid !in accountUuids) return false

            val folderIds = localSearch.folderIds
            return folderIds.isEmpty() || folderId in folderIds
        }

        fun getFolderCompleted(): Int {
            synchronized(lock) {
                return folderCompleted
            }
        }

        fun getFolderTotal(): Int {
            synchronized(lock) {
                return folderTotal
            }
        }
    }

    internal inner class ActionModeCallback : ActionMode.Callback {
        private var selectAll: MenuItem? = null
        private var markAsRead: MenuItem? = null
        private var markAsUnread: MenuItem? = null
        private var flag: MenuItem? = null
        private var unflag: MenuItem? = null
        private var disableMarkAsRead = false
        private var disableFlag = false

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            selectAll = menu.findItem(R.id.select_all)
            markAsRead = menu.findItem(R.id.mark_as_read)
            markAsUnread = menu.findItem(R.id.mark_as_unread)
            flag = menu.findItem(R.id.flag)
            unflag = menu.findItem(R.id.unflag)

            // we don't support cross account actions atm
            if (!isSingleAccountMode) {
                val accounts = accountUuidsForSelected.mapNotNull { accountUuid ->
                    accountManager.getAccount(accountUuid)
                }

                menu.findItem(R.id.move).isVisible = true
                menu.findItem(R.id.copy).isVisible = true

                // Disable archive/spam options here and maybe enable below when checking account capabilities
                menu.findItem(R.id.archive).isVisible = false
                menu.findItem(R.id.spam).isVisible = false

                for (account in accounts) {
                    setContextCapabilities(account, menu)
                }
            }

            return true
        }

        private val accountUuidsForSelected: Set<String>
            get() = adapter.selectedMessages.mapToSet { it.account.uuid }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            selectAll = null
            markAsRead = null
            markAsUnread = null
            flag = null
            unflag = null

            adapter.clearSelected()
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.message_list_context_menu, menu)

            setContextCapabilities(account, menu)
            return true
        }

        private fun setContextCapabilities(account: Account?, menu: Menu) {
            if (!isSingleAccountMode || account == null) {
                // We don't support cross-account copy/move operations right now
                menu.findItem(R.id.move).isVisible = false
                menu.findItem(R.id.copy).isVisible = false

                if (account?.hasArchiveFolder() == true) {
                    menu.findItem(R.id.archive).isVisible = true
                }

                if (account?.hasSpamFolder() == true) {
                    menu.findItem(R.id.spam).isVisible = true
                }
            } else if (isOutbox) {
                menu.findItem(R.id.mark_as_read).isVisible = false
                menu.findItem(R.id.mark_as_unread).isVisible = false
                menu.findItem(R.id.archive).isVisible = false
                menu.findItem(R.id.copy).isVisible = false
                menu.findItem(R.id.flag).isVisible = false
                menu.findItem(R.id.unflag).isVisible = false
                menu.findItem(R.id.spam).isVisible = false
                menu.findItem(R.id.move).isVisible = false

                disableMarkAsRead = true
                disableFlag = true

                if (account.hasDraftsFolder()) {
                    menu.findItem(R.id.move_to_drafts).isVisible = true
                }
            } else {
                if (!messagingController.isCopyCapable(account)) {
                    menu.findItem(R.id.copy).isVisible = false
                }

                if (!messagingController.isMoveCapable(account)) {
                    menu.findItem(R.id.move).isVisible = false
                    menu.findItem(R.id.archive).isVisible = false
                    menu.findItem(R.id.spam).isVisible = false
                } else {
                    if (!account.hasArchiveFolder() || isArchiveFolder) {
                        menu.findItem(R.id.archive).isVisible = false
                    }

                    if (!account.hasSpamFolder() || isSpamFolder) {
                        menu.findItem(R.id.spam).isVisible = false
                    }
                }
            }
        }

        fun showSelectAll(show: Boolean) {
            selectAll?.isVisible = show
        }

        fun showMarkAsRead(show: Boolean) {
            if (!disableMarkAsRead) {
                markAsRead?.isVisible = show
                markAsUnread?.isVisible = !show
            }
        }

        fun showFlag(show: Boolean) {
            if (!disableFlag) {
                flag?.isVisible = show
                unflag?.isVisible = !show
            }
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            // In the following we assume that we can't move or copy mails to the same folder. Also that spam isn't
            // available if we are in the spam folder, same for archive.

            val endSelectionMode = when (item.itemId) {
                R.id.delete -> {
                    onDelete(selectedMessages)
                    true
                }

                R.id.mark_as_read -> {
                    setFlagForSelected(Flag.SEEN, true)
                    false
                }

                R.id.mark_as_unread -> {
                    setFlagForSelected(Flag.SEEN, false)
                    false
                }

                R.id.flag -> {
                    setFlagForSelected(Flag.FLAGGED, true)
                    false
                }

                R.id.unflag -> {
                    setFlagForSelected(Flag.FLAGGED, false)
                    false
                }

                R.id.select_all -> {
                    selectAll()
                    false
                }

                R.id.archive -> {
                    onArchive(selectedMessages)
                    // TODO: Only finish action mode if all messages have been moved.
                    true
                }

                R.id.spam -> {
                    onSpam(selectedMessages)
                    // TODO: Only finish action mode if all messages have been moved.
                    true
                }

                R.id.move -> {
                    onMove(selectedMessages)
                    true
                }

                R.id.move_to_drafts -> {
                    onMoveToDraftsFolder(selectedMessages)
                    true
                }

                R.id.copy -> {
                    onCopy(selectedMessages)
                    true
                }

                else -> return false
            }

            if (endSelectionMode) {
                mode.finish()
            }

            return true
        }
    }

    private enum class FolderOperation {
        COPY,
        MOVE,
    }

    private enum class Error(@StringRes val errorText: Int) {
        FolderNotFound(R.string.message_list_error_folder_not_found),
    }

    interface MessageListFragmentListener {
        fun setMessageListProgressEnabled(enable: Boolean)
        fun setMessageListProgress(level: Int)
        fun showThread(account: Account, threadRootId: Long)
        fun openMessage(messageReference: MessageReference)
        fun setMessageListTitle(title: String, subtitle: String? = null)
        fun onCompose(account: Account?)
        fun startSearch(query: String, account: Account?, folderId: Long?): Boolean
        fun startSupportActionMode(callback: ActionMode.Callback): ActionMode?
        fun goBack()

        companion object {
            const val MAX_PROGRESS = 10000
        }
    }

    companion object {
        private const val ACTIVITY_CHOOSE_FOLDER_MOVE = 1
        private const val ACTIVITY_CHOOSE_FOLDER_COPY = 2

        private const val ARG_SEARCH = "searchObject"
        private const val ARG_THREADED_LIST = "showingThreadedList"
        private const val ARG_IS_THREAD_DISPLAY = "isThreadedDisplay"

        private const val STATE_SELECTED_MESSAGES = "selectedMessages"
        private const val STATE_ACTIVE_MESSAGES = "activeMessages"
        private const val STATE_ACTIVE_MESSAGE = "activeMessage"
        private const val STATE_REMOTE_SEARCH_PERFORMED = "remoteSearchPerformed"

        fun newInstance(search: LocalSearch, isThreadDisplay: Boolean, threadedList: Boolean): MessageListFragment {
            return MessageListFragment().apply {
                arguments = bundleOf(
                    ARG_SEARCH to search,
                    ARG_IS_THREAD_DISPLAY to isThreadDisplay,
                    ARG_THREADED_LIST to threadedList,
                )
            }
        }
    }
}
