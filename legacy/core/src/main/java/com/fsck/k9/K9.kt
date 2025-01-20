package com.fsck.k9

import android.content.Context
import android.content.SharedPreferences
import app.k9mail.feature.telemetry.api.TelemetryManager
import app.k9mail.legacy.account.Account
import app.k9mail.legacy.account.Account.SortType
import app.k9mail.legacy.di.DI
import com.fsck.k9.core.BuildConfig
import com.fsck.k9.mail.K9MailLib
import com.fsck.k9.mailstore.LocalStore
import com.fsck.k9.preferences.RealGeneralSettingsManager
import com.fsck.k9.preferences.Storage
import com.fsck.k9.preferences.StorageEditor
import kotlinx.datetime.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import timber.log.Timber.DebugTree

// TODO "Use GeneralSettingsManager and GeneralSettings instead"
object K9 : KoinComponent {
    private val generalSettingsManager: RealGeneralSettingsManager by inject()
    private val telemetryManager: TelemetryManager by inject()

    /**
     * If this is `true`, various development settings will be enabled.
     */
    @JvmField
    val DEVELOPER_MODE = BuildConfig.DEBUG

    /**
     * Name of the [SharedPreferences] file used to store the last known version of the
     * accounts' databases.
     *
     * See `UpgradeDatabases` for a detailed explanation of the database upgrade process.
     */
    private const val DATABASE_VERSION_CACHE = "database_version_cache"

    /**
     * Key used to store the last known database version of the accounts' databases.
     *
     * @see DATABASE_VERSION_CACHE
     */
    private const val KEY_LAST_ACCOUNT_DATABASE_VERSION = "last_account_database_version"

    /**
     * A reference to the [SharedPreferences] used for caching the last known database version.
     *
     * @see checkCachedDatabaseVersion
     * @see setDatabasesUpToDate
     */
    private var databaseVersionCache: SharedPreferences? = null

    /**
     * @see areDatabasesUpToDate
     */
    private var databasesUpToDate = false

    /**
     * Check if we already know whether all databases are using the current database schema.
     *
     * This method is only used for optimizations. If it returns `true` we can be certain that getting a [LocalStore]
     * instance won't trigger a schema upgrade.
     *
     * @return `true`, if we know that all databases are using the current database schema. `false`, otherwise.
     */
    @Synchronized
    @JvmStatic
    fun areDatabasesUpToDate(): Boolean {
        return databasesUpToDate
    }

    /**
     * Remember that all account databases are using the most recent database schema.
     *
     * @param save
     * Whether or not to write the current database version to the
     * `SharedPreferences` [.DATABASE_VERSION_CACHE].
     *
     * @see .areDatabasesUpToDate
     */
    @Synchronized
    @JvmStatic
    fun setDatabasesUpToDate(save: Boolean) {
        databasesUpToDate = true

        if (save) {
            val editor = databaseVersionCache!!.edit()
            editor.putInt(KEY_LAST_ACCOUNT_DATABASE_VERSION, LocalStore.getDbVersion())
            editor.apply()
        }
    }

    /**
     * Loads the last known database version of the accounts' databases from a `SharedPreference`.
     *
     * If the stored version matches [LocalStore.getDbVersion] we know that the databases are up to date.
     * Using `SharedPreferences` should be a lot faster than opening all SQLite databases to get the current database
     * version.
     *
     * See the class `UpgradeDatabases` for a detailed explanation of the database upgrade process.
     *
     * @see areDatabasesUpToDate
     */
    private fun checkCachedDatabaseVersion(context: Context) {
        databaseVersionCache = context.getSharedPreferences(DATABASE_VERSION_CACHE, Context.MODE_PRIVATE)

        val cachedVersion = databaseVersionCache!!.getInt(KEY_LAST_ACCOUNT_DATABASE_VERSION, 0)

        if (cachedVersion >= LocalStore.getDbVersion()) {
            setDatabasesUpToDate(false)
        }
    }

    @JvmStatic
    var isDebugLoggingEnabled: Boolean = DEVELOPER_MODE
        set(debug) {
            field = debug
            updateLoggingStatus()
        }

    @JvmStatic
    var isSensitiveDebugLoggingEnabled: Boolean = false

    @JvmStatic
    var k9Language = ""

    @JvmStatic
    val fontSizes = FontSizes()

    @JvmStatic
    var backgroundOps = BACKGROUND_OPS.ALWAYS

    @JvmStatic
    var isShowAnimations = true

    @JvmStatic
    var isConfirmDelete = false

    @JvmStatic
    var isConfirmDiscardMessage = true

    @JvmStatic
    var isConfirmDeleteStarred = false

    @JvmStatic
    var isConfirmSpam = false

    @JvmStatic
    var isConfirmDeleteFromNotification = true

    @JvmStatic
    var isConfirmMarkAllRead = true

    @JvmStatic
    var notificationQuickDeleteBehaviour = NotificationQuickDelete.ALWAYS

    @JvmStatic
    var lockScreenNotificationVisibility = LockScreenNotificationVisibility.MESSAGE_COUNT

    @JvmStatic
    var messageListDensity: UiDensity = UiDensity.Default

    @JvmStatic
    var isShowMessageListStars = true

    @JvmStatic
    var messageListPreviewLines = 2

    @JvmStatic
    var isShowCorrespondentNames = true

    @JvmStatic
    var isMessageListSenderAboveSubject = false

    @JvmStatic
    var isShowContactName = false

    @JvmStatic
    var isChangeContactNameColor = false

    @JvmStatic
    var contactNameColor = 0xFF1093F5.toInt()

    @JvmStatic
    var isShowContactPicture = true

    @JvmStatic
    var isUseMessageViewFixedWidthFont = false

    var messageViewPostRemoveNavigation: PostRemoveNavigation = PostRemoveNavigation.ReturnToMessageList

    var messageViewPostMarkAsUnreadNavigation: PostMarkAsUnreadNavigation =
        PostMarkAsUnreadNavigation.ReturnToMessageList

    @JvmStatic
    var isUseVolumeKeysForNavigation = false

    @JvmStatic
    var isShowUnifiedInbox = false

    @JvmStatic
    var isShowAccountSelector = true

    @JvmStatic
    var isShowStarredCount = false

    @JvmStatic
    var isAutoFitWidth: Boolean = false

    var isQuietTimeEnabled = false
    var isNotificationDuringQuietTimeEnabled = true
    var quietTimeStarts: String? = null
    var quietTimeEnds: String? = null

    @JvmStatic
    var isHideUserAgent = false

    @JvmStatic
    var isHideTimeZone = false

    @get:Synchronized
    @set:Synchronized
    @JvmStatic
    var sortType: SortType = Account.DEFAULT_SORT_TYPE
    private val sortAscending = mutableMapOf<SortType, Boolean>()

    @JvmStatic
    var isUseBackgroundAsUnreadIndicator = false

    @get:Synchronized
    @set:Synchronized
    var isShowComposeButtonOnMessageList = true

    @get:Synchronized
    @set:Synchronized
    @JvmStatic
    var isThreadedViewEnabled = true

    @get:Synchronized
    @set:Synchronized
    @JvmStatic
    var splitViewMode = SplitViewMode.NEVER

    var isColorizeMissingContactPictures = true

    @JvmStatic
    var isMessageViewArchiveActionVisible = false

    @JvmStatic
    var isMessageViewDeleteActionVisible = true

    @JvmStatic
    var isMessageViewMoveActionVisible = false

    @JvmStatic
    var isMessageViewCopyActionVisible = false

    @JvmStatic
    var isMessageViewSpamActionVisible = false

    @JvmStatic
    var pgpInlineDialogCounter: Int = 0

    @JvmStatic
    var pgpSignOnlyDialogCounter: Int = 0

    @JvmStatic
    var swipeRightAction: SwipeAction = SwipeAction.ToggleSelection

    @JvmStatic
    var swipeLeftAction: SwipeAction = SwipeAction.ToggleRead

    // TODO: This is a feature-specific setting that doesn't need to be available to apps that don't include the
    //  feature. Extract `Storage` and `StorageEditor` to a separate module so feature modules can retrieve and store
    //  their own settings.
    var isTelemetryEnabled = false

    // TODO: These are feature-specific settings that don't need to be available to apps that don't include the
    //  feature.
    var fundingReminderReferenceTimestamp: Long = 0
    var fundingReminderShownTimestamp: Long = 0
    var fundingActivityCounterInMillis: Long = 0

    val isQuietTime: Boolean
        get() {
            if (!isQuietTimeEnabled) {
                return false
            }

            val clock = DI.get<Clock>()
            val quietTimeChecker = QuietTimeChecker(clock, quietTimeStarts, quietTimeEnds)
            return quietTimeChecker.isQuietTime
        }

    @Synchronized
    @JvmStatic
    fun isSortAscending(sortType: SortType): Boolean {
        if (sortAscending[sortType] == null) {
            sortAscending[sortType] = sortType.isDefaultAscending
        }
        return sortAscending[sortType]!!
    }

    @Synchronized
    @JvmStatic
    fun setSortAscending(sortType: SortType, sortAscending: Boolean) {
        K9.sortAscending[sortType] = sortAscending
    }

    fun init(context: Context) {
        K9MailLib.setDebugStatus(
            object : K9MailLib.DebugStatus {
                override fun enabled(): Boolean = isDebugLoggingEnabled

                override fun debugSensitive(): Boolean = isSensitiveDebugLoggingEnabled
            },
        )
        com.fsck.k9.logging.Timber.logger = TimberLogger()

        checkCachedDatabaseVersion(context)

        loadPrefs(generalSettingsManager.storage)
    }

    @JvmStatic
    @Suppress("LongMethod")
    fun loadPrefs(storage: Storage) {
        isDebugLoggingEnabled = storage.getBoolean("enableDebugLogging", DEVELOPER_MODE)
        isSensitiveDebugLoggingEnabled = storage.getBoolean("enableSensitiveLogging", false)
        isShowAnimations = storage.getBoolean("animations", true)
        isUseVolumeKeysForNavigation = storage.getBoolean("useVolumeKeysForNavigation", false)
        isShowUnifiedInbox = storage.getBoolean("showUnifiedInbox", false)
        isShowAccountSelector = storage.getBoolean("showAccountSelector", true)
        isShowStarredCount = storage.getBoolean("showStarredCount", false)
        isMessageListSenderAboveSubject = storage.getBoolean("messageListSenderAboveSubject", false)
        isShowMessageListStars = storage.getBoolean("messageListStars", true)
        messageListPreviewLines = storage.getInt("messageListPreviewLines", 2)

        isAutoFitWidth = storage.getBoolean("autofitWidth", true)

        isQuietTimeEnabled = storage.getBoolean("quietTimeEnabled", false)
        isNotificationDuringQuietTimeEnabled = storage.getBoolean("notificationDuringQuietTimeEnabled", true)
        quietTimeStarts = storage.getString("quietTimeStarts", "21:00")
        quietTimeEnds = storage.getString("quietTimeEnds", "7:00")

        messageListDensity = storage.getEnum("messageListDensity", UiDensity.Default)
        isShowCorrespondentNames = storage.getBoolean("showCorrespondentNames", true)
        isShowContactName = storage.getBoolean("showContactName", false)
        isShowContactPicture = storage.getBoolean("showContactPicture", true)
        isChangeContactNameColor = storage.getBoolean("changeRegisteredNameColor", false)
        contactNameColor = storage.getInt("registeredNameColor", 0xFF1093F5.toInt())
        isUseMessageViewFixedWidthFont = storage.getBoolean("messageViewFixedWidthFont", false)
        messageViewPostRemoveNavigation =
            storage.getEnum("messageViewPostDeleteAction", PostRemoveNavigation.ReturnToMessageList)
        messageViewPostMarkAsUnreadNavigation =
            storage.getEnum("messageViewPostMarkAsUnreadAction", PostMarkAsUnreadNavigation.ReturnToMessageList)
        isHideUserAgent = storage.getBoolean("hideUserAgent", false)
        isHideTimeZone = storage.getBoolean("hideTimeZone", false)

        isConfirmDelete = storage.getBoolean("confirmDelete", false)
        isConfirmDiscardMessage = storage.getBoolean("confirmDiscardMessage", true)
        isConfirmDeleteStarred = storage.getBoolean("confirmDeleteStarred", false)
        isConfirmSpam = storage.getBoolean("confirmSpam", false)
        isConfirmDeleteFromNotification = storage.getBoolean("confirmDeleteFromNotification", true)
        isConfirmMarkAllRead = storage.getBoolean("confirmMarkAllRead", true)

        sortType = storage.getEnum("sortTypeEnum", Account.DEFAULT_SORT_TYPE)

        val sortAscendingSetting = storage.getBoolean("sortAscending", Account.DEFAULT_SORT_ASCENDING)
        sortAscending[sortType] = sortAscendingSetting

        notificationQuickDeleteBehaviour = storage.getEnum("notificationQuickDelete", NotificationQuickDelete.ALWAYS)

        lockScreenNotificationVisibility = storage.getEnum(
            "lockScreenNotificationVisibility",
            LockScreenNotificationVisibility.MESSAGE_COUNT,
        )

        splitViewMode = storage.getEnum("splitViewMode", SplitViewMode.NEVER)

        isUseBackgroundAsUnreadIndicator = storage.getBoolean("useBackgroundAsUnreadIndicator", false)
        isShowComposeButtonOnMessageList = storage.getBoolean("showComposeButtonOnMessageList", true)
        isThreadedViewEnabled = storage.getBoolean("threadedView", true)
        fontSizes.load(storage)

        backgroundOps = storage.getEnum("backgroundOperations", BACKGROUND_OPS.ALWAYS)

        isColorizeMissingContactPictures = storage.getBoolean("colorizeMissingContactPictures", true)

        isMessageViewArchiveActionVisible = storage.getBoolean("messageViewArchiveActionVisible", false)
        isMessageViewDeleteActionVisible = storage.getBoolean("messageViewDeleteActionVisible", true)
        isMessageViewMoveActionVisible = storage.getBoolean("messageViewMoveActionVisible", false)
        isMessageViewCopyActionVisible = storage.getBoolean("messageViewCopyActionVisible", false)
        isMessageViewSpamActionVisible = storage.getBoolean("messageViewSpamActionVisible", false)

        pgpInlineDialogCounter = storage.getInt("pgpInlineDialogCounter", 0)
        pgpSignOnlyDialogCounter = storage.getInt("pgpSignOnlyDialogCounter", 0)

        k9Language = storage.getString("language", "")

        swipeRightAction = storage.getEnum("swipeRightAction", SwipeAction.ToggleSelection)
        swipeLeftAction = storage.getEnum("swipeLeftAction", SwipeAction.ToggleRead)

        if (telemetryManager.isTelemetryFeatureIncluded()) {
            isTelemetryEnabled = storage.getBoolean("enableTelemetry", true)
        }

        fundingReminderReferenceTimestamp = storage.getLong("fundingReminderReferenceTimestamp", 0)
        fundingReminderShownTimestamp = storage.getLong("fundingReminderShownTimestamp", 0)
        fundingActivityCounterInMillis = storage.getLong("fundingActivityCounterInMillis", 0)
    }

    @Suppress("LongMethod")
    internal fun save(editor: StorageEditor) {
        editor.putBoolean("enableDebugLogging", isDebugLoggingEnabled)
        editor.putBoolean("enableSensitiveLogging", isSensitiveDebugLoggingEnabled)
        editor.putEnum("backgroundOperations", backgroundOps)
        editor.putBoolean("animations", isShowAnimations)
        editor.putBoolean("useVolumeKeysForNavigation", isUseVolumeKeysForNavigation)
        editor.putBoolean("autofitWidth", isAutoFitWidth)
        editor.putBoolean("quietTimeEnabled", isQuietTimeEnabled)
        editor.putBoolean("notificationDuringQuietTimeEnabled", isNotificationDuringQuietTimeEnabled)
        editor.putString("quietTimeStarts", quietTimeStarts)
        editor.putString("quietTimeEnds", quietTimeEnds)

        editor.putEnum("messageListDensity", messageListDensity)
        editor.putBoolean("messageListSenderAboveSubject", isMessageListSenderAboveSubject)
        editor.putBoolean("showUnifiedInbox", isShowUnifiedInbox)
        editor.putBoolean("showAccountSelector", isShowAccountSelector)
        editor.putBoolean("showStarredCount", isShowStarredCount)
        editor.putBoolean("messageListStars", isShowMessageListStars)
        editor.putInt("messageListPreviewLines", messageListPreviewLines)
        editor.putBoolean("showCorrespondentNames", isShowCorrespondentNames)
        editor.putBoolean("showContactName", isShowContactName)
        editor.putBoolean("showContactPicture", isShowContactPicture)
        editor.putBoolean("changeRegisteredNameColor", isChangeContactNameColor)
        editor.putInt("registeredNameColor", contactNameColor)
        editor.putBoolean("messageViewFixedWidthFont", isUseMessageViewFixedWidthFont)
        editor.putEnum("messageViewPostDeleteAction", messageViewPostRemoveNavigation)
        editor.putEnum("messageViewPostMarkAsUnreadAction", messageViewPostMarkAsUnreadNavigation)
        editor.putBoolean("hideUserAgent", isHideUserAgent)
        editor.putBoolean("hideTimeZone", isHideTimeZone)

        editor.putString("language", k9Language)

        editor.putBoolean("confirmDelete", isConfirmDelete)
        editor.putBoolean("confirmDiscardMessage", isConfirmDiscardMessage)
        editor.putBoolean("confirmDeleteStarred", isConfirmDeleteStarred)
        editor.putBoolean("confirmSpam", isConfirmSpam)
        editor.putBoolean("confirmDeleteFromNotification", isConfirmDeleteFromNotification)
        editor.putBoolean("confirmMarkAllRead", isConfirmMarkAllRead)

        editor.putEnum("sortTypeEnum", sortType)
        editor.putBoolean("sortAscending", sortAscending[sortType] ?: false)

        editor.putString("notificationQuickDelete", notificationQuickDeleteBehaviour.toString())
        editor.putString("lockScreenNotificationVisibility", lockScreenNotificationVisibility.toString())

        editor.putBoolean("useBackgroundAsUnreadIndicator", isUseBackgroundAsUnreadIndicator)
        editor.putBoolean("showComposeButtonOnMessageList", isShowComposeButtonOnMessageList)
        editor.putBoolean("threadedView", isThreadedViewEnabled)
        editor.putEnum("splitViewMode", splitViewMode)
        editor.putBoolean("colorizeMissingContactPictures", isColorizeMissingContactPictures)

        editor.putBoolean("messageViewArchiveActionVisible", isMessageViewArchiveActionVisible)
        editor.putBoolean("messageViewDeleteActionVisible", isMessageViewDeleteActionVisible)
        editor.putBoolean("messageViewMoveActionVisible", isMessageViewMoveActionVisible)
        editor.putBoolean("messageViewCopyActionVisible", isMessageViewCopyActionVisible)
        editor.putBoolean("messageViewSpamActionVisible", isMessageViewSpamActionVisible)

        editor.putInt("pgpInlineDialogCounter", pgpInlineDialogCounter)
        editor.putInt("pgpSignOnlyDialogCounter", pgpSignOnlyDialogCounter)

        editor.putEnum("swipeRightAction", swipeRightAction)
        editor.putEnum("swipeLeftAction", swipeLeftAction)

        if (telemetryManager.isTelemetryFeatureIncluded()) {
            editor.putBoolean("enableTelemetry", isTelemetryEnabled)
        }

        editor.putLong("fundingReminderReferenceTimestamp", fundingReminderReferenceTimestamp)
        editor.putLong("fundingReminderShownTimestamp", fundingReminderShownTimestamp)
        editor.putLong("fundingActivityCounterInMillis", fundingActivityCounterInMillis)

        fontSizes.save(editor)
    }

    private fun updateLoggingStatus() {
        Timber.uprootAll()
        if (isDebugLoggingEnabled) {
            Timber.plant(DebugTree())
        }
    }

    @JvmStatic
    fun saveSettingsAsync() {
        generalSettingsManager.saveSettingsAsync()
    }

    private inline fun <reified T : Enum<T>> Storage.getEnum(key: String, defaultValue: T): T {
        return try {
            val value = getString(key, null)
            if (value != null) {
                enumValueOf(value)
            } else {
                defaultValue
            }
        } catch (e: Exception) {
            Timber.e("Couldn't read setting '%s'. Using default value instead.", key)
            defaultValue
        }
    }

    private fun <T : Enum<T>> StorageEditor.putEnum(key: String, value: T) {
        putString(key, value.name)
    }

    const val LOCAL_UID_PREFIX = "K9LOCAL:"

    const val IDENTITY_HEADER = K9MailLib.IDENTITY_HEADER

    /**
     * Specifies how many messages will be shown in a folder by default. This number is set
     * on each new folder and can be incremented with "Load more messages..." by the
     * VISIBLE_LIMIT_INCREMENT
     */
    const val DEFAULT_VISIBLE_LIMIT = 25

    /**
     * The maximum size of an attachment we're willing to download (either View or Save)
     * Attachments that are base64 encoded (most) will be about 1.375x their actual size
     * so we should probably factor that in. A 5MB attachment will generally be around
     * 6.8MB downloaded but only 5MB saved.
     */
    const val MAX_ATTACHMENT_DOWNLOAD_SIZE = 128 * 1024 * 1024

    /**
     * How many times should K-9 try to deliver a message before giving up until the app is killed and restarted
     */
    const val MAX_SEND_ATTEMPTS = 5

    const val MANUAL_WAKE_LOCK_TIMEOUT = 120000
    const val PUSH_WAKE_LOCK_TIMEOUT = K9MailLib.PUSH_WAKE_LOCK_TIMEOUT
    const val MAIL_SERVICE_WAKE_LOCK_TIMEOUT = 60000
    const val BOOT_RECEIVER_WAKE_LOCK_TIMEOUT = 60000

    @Suppress("ktlint:standard:class-naming")
    enum class BACKGROUND_OPS {
        ALWAYS,
        NEVER,
        WHEN_CHECKED_AUTO_SYNC,
    }

    /**
     * Controls behaviour of delete button in notifications.
     */
    enum class NotificationQuickDelete {
        ALWAYS,
        FOR_SINGLE_MSG,
        NEVER,
    }

    enum class LockScreenNotificationVisibility {
        EVERYTHING,
        SENDERS,
        MESSAGE_COUNT,
        APP_NAME,
        NOTHING,
    }

    /**
     * Controls when to use the message list split view.
     */
    enum class SplitViewMode {
        ALWAYS,
        NEVER,
        WHEN_IN_LANDSCAPE,
    }

    /**
     * The navigation actions that can be to performed after the user has deleted or moved a message from the message
     * view screen.
     */
    enum class PostRemoveNavigation {
        ReturnToMessageList,
        ShowPreviousMessage,
        ShowNextMessage,
    }

    /**
     * The navigation actions that can be to performed after the user has marked a message as unread from the message
     * view screen.
     */
    enum class PostMarkAsUnreadNavigation {
        StayOnCurrentMessage,
        ReturnToMessageList,
    }
}
