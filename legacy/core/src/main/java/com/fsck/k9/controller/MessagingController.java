package com.fsck.k9.controller;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import app.k9mail.core.featureflag.FeatureFlagProvider;
import app.k9mail.legacy.account.Account;
import app.k9mail.legacy.account.Account.DeletePolicy;
import app.k9mail.legacy.di.DI;
import app.k9mail.legacy.message.controller.MessageReference;
import app.k9mail.legacy.message.controller.MessagingControllerMailChecker;
import app.k9mail.legacy.message.controller.MessagingControllerRegistry;
import app.k9mail.legacy.message.controller.MessagingListener;
import app.k9mail.legacy.message.controller.SimpleMessagingListener;
import com.fsck.k9.K9;
import com.fsck.k9.Preferences;
import com.fsck.k9.backend.BackendManager;
import com.fsck.k9.backend.api.Backend;
import com.fsck.k9.backend.api.SyncConfig;
import com.fsck.k9.backend.api.SyncListener;
import com.fsck.k9.controller.ControllerExtension.ControllerInternals;
import com.fsck.k9.controller.MessagingControllerCommands.PendingAppend;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.controller.MessagingControllerCommands.PendingDelete;
import com.fsck.k9.controller.MessagingControllerCommands.PendingEmptySpam;
import com.fsck.k9.controller.MessagingControllerCommands.PendingEmptyTrash;
import com.fsck.k9.controller.MessagingControllerCommands.PendingExpunge;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMarkAllAsRead;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMoveAndMarkAsRead;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMoveOrCopy;
import com.fsck.k9.controller.MessagingControllerCommands.PendingReplace;
import com.fsck.k9.controller.MessagingControllerCommands.PendingSetFlag;
import com.fsck.k9.controller.ProgressBodyFactory.ProgressListener;
import com.fsck.k9.core.BuildConfig;
import com.fsck.k9.helper.MutableBoolean;
import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageDownloadState;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.ServerSettings;
import com.fsck.k9.mail.power.PowerManager;
import com.fsck.k9.mail.power.WakeLock;
import app.k9mail.legacy.mailstore.FolderDetailsAccessor;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.mailstore.LocalStoreProvider;
import com.fsck.k9.mailstore.MessageListCache;
import app.k9mail.legacy.mailstore.MessageStore;
import app.k9mail.legacy.mailstore.MessageStoreManager;
import com.fsck.k9.mailstore.OutboxState;
import com.fsck.k9.mailstore.OutboxStateRepository;
import app.k9mail.legacy.mailstore.SaveMessageData;
import com.fsck.k9.mailstore.SaveMessageDataCreator;
import com.fsck.k9.mailstore.SendState;
import com.fsck.k9.mailstore.SpecialLocalFoldersCreator;
import com.fsck.k9.notification.NotificationController;
import com.fsck.k9.notification.NotificationStrategy;
import app.k9mail.legacy.search.LocalSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import timber.log.Timber;

import static com.fsck.k9.K9.MAX_SEND_ATTEMPTS;
import static com.fsck.k9.controller.Preconditions.requireNotNull;
import static com.fsck.k9.helper.ExceptionHelper.getRootCauseMessage;
import static com.fsck.k9.mail.Flag.X_REMOTE_COPY_STARTED;


/**
 * Starts a long running (application) Thread that will run through commands
 * that require remote mailbox access. This class is used to serialize and
 * prioritize these commands. Each method that will submit a command requires a
 * MessagingListener instance to be provided. It is expected that that listener
 * has also been added as a registered listener using addListener(). When a
 * command is to be executed, if the listener that was provided with the command
 * is no longer registered the command is skipped. The design idea for the above
 * is that when an Activity starts it registers as a listener. When it is paused
 * it removes itself. Thus, any commands that that activity submitted are
 * removed from the queue once the activity is no longer active.
 */
public class MessagingController implements MessagingControllerRegistry, MessagingControllerMailChecker {
    public static final Set<Flag> SYNC_FLAGS = EnumSet.of(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED);

    private static final long FOLDER_LIST_STALENESS_THRESHOLD = 30 * 60 * 1000L;

    private final Context context;
    private final NotificationController notificationController;
    private final NotificationStrategy notificationStrategy;
    private final LocalStoreProvider localStoreProvider;
    private final BackendManager backendManager;
    private final Preferences preferences;
    private final MessageStoreManager messageStoreManager;
    private final SaveMessageDataCreator saveMessageDataCreator;
    private final SpecialLocalFoldersCreator specialLocalFoldersCreator;
    private final LocalDeleteOperationDecider localDeleteOperationDecider;

    private final Thread controllerThread;

    private final BlockingQueue<Command> queuedCommands = new PriorityBlockingQueue<>();
    private final Set<MessagingListener> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private final MemorizingMessagingListener memorizingMessagingListener = new MemorizingMessagingListener();
    private final DraftOperations draftOperations;
    private final NotificationOperations notificationOperations;
    private final ArchiveOperations archiveOperations;


    private volatile boolean stopped = false;


    public static MessagingController getInstance(Context context) {
        return DI.get(MessagingController.class);
    }


    MessagingController(Context context, NotificationController notificationController,
        NotificationStrategy notificationStrategy, LocalStoreProvider localStoreProvider,
        BackendManager backendManager, Preferences preferences, MessageStoreManager messageStoreManager,
        SaveMessageDataCreator saveMessageDataCreator, SpecialLocalFoldersCreator specialLocalFoldersCreator,
        LocalDeleteOperationDecider localDeleteOperationDecider, List<ControllerExtension> controllerExtensions,
        FeatureFlagProvider featureFlagProvider
    ) {
        this.context = context;
        this.notificationController = notificationController;
        this.notificationStrategy = notificationStrategy;
        this.localStoreProvider = localStoreProvider;
        this.backendManager = backendManager;
        this.preferences = preferences;
        this.messageStoreManager = messageStoreManager;
        this.saveMessageDataCreator = saveMessageDataCreator;
        this.specialLocalFoldersCreator = specialLocalFoldersCreator;
        this.localDeleteOperationDecider = localDeleteOperationDecider;

        controllerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runInBackground();
            }
        });
        controllerThread.setName("MessagingController");
        controllerThread.start();
        addListener(memorizingMessagingListener);

        initializeControllerExtensions(controllerExtensions);

        draftOperations = new DraftOperations(this, messageStoreManager, saveMessageDataCreator);
        notificationOperations = new NotificationOperations(notificationController, preferences, messageStoreManager);
        archiveOperations = new ArchiveOperations(this, featureFlagProvider);
    }

    private void initializeControllerExtensions(List<ControllerExtension> controllerExtensions) {
        if (controllerExtensions.isEmpty()) {
            return;
        }

        ControllerInternals internals = new ControllerInternals() {
            @Override
            public void put(@NotNull String description, @Nullable MessagingListener listener,
                    @NotNull Runnable runnable) {
                MessagingController.this.put(description, listener, runnable);
            }

            @Override
            public void putBackground(@NotNull String description, @Nullable MessagingListener listener,
                    @NotNull Runnable runnable) {
                MessagingController.this.putBackground(description, listener, runnable);
            }
        };

        for (ControllerExtension extension : controllerExtensions) {
            extension.init(this, backendManager, internals);
        }
    }

    @VisibleForTesting
    void stop() throws InterruptedException {
        stopped = true;
        controllerThread.interrupt();
        controllerThread.join(1000L);
    }

    private void runInBackground() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (!stopped) {
            String commandDescription = null;
            try {
                final Command command = queuedCommands.take();

                if (command != null) {
                    commandDescription = command.description;

                    Timber.i("Running command '%s', seq = %s (%s priority)",
                            command.description,
                            command.sequence,
                            command.isForegroundPriority ? "foreground" : "background");

                    command.runnable.run();

                    Timber.i(" Command '%s' completed", command.description);
                }
            } catch (Exception e) {
                Timber.e(e, "Error running command '%s'", commandDescription);
            }
        }
    }

    private void put(String description, MessagingListener listener, Runnable runnable) {
        putCommand(queuedCommands, description, listener, runnable, true);
    }

    void putBackground(String description, MessagingListener listener, Runnable runnable) {
        putCommand(queuedCommands, description, listener, runnable, false);
    }

    private void putCommand(BlockingQueue<Command> queue, String description, MessagingListener listener,
            Runnable runnable, boolean isForeground) {
        int retries = 10;
        Exception e = null;
        while (retries-- > 0) {
            try {
                Command command = new Command();
                command.listener = listener;
                command.runnable = runnable;
                command.description = description;
                command.isForegroundPriority = isForeground;
                queue.put(command);
                return;
            } catch (InterruptedException ie) {
                SystemClock.sleep(200);
                e = ie;
            }
        }
        throw new Error(e);
    }

    Backend getBackend(Account account) {
        return backendManager.getBackend(account);
    }

    LocalStore getLocalStoreOrThrow(Account account) {
        try {
            return localStoreProvider.getInstance(account);
        } catch (MessagingException e) {
            throw new IllegalStateException("Couldn't get LocalStore for account " + account);
        }
    }

    private String getFolderServerId(Account account, long folderId) {
        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        String folderServerId = messageStore.getFolderServerId(folderId);
        if (folderServerId == null) {
            throw new IllegalStateException("Folder not found (ID: " + folderId + ")");
        }
        return folderServerId;
    }

    private long getFolderId(Account account, String folderServerId) {
        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        Long folderId = messageStore.getFolderId(folderServerId);
        if (folderId == null) {
            throw new IllegalStateException("Folder not found (server ID: " + folderServerId + ")");
        }
        return folderId;
    }

    public void addListener(@NonNull MessagingListener listener) {
        listeners.add(listener);
        refreshListener(listener);
    }

    public void refreshListener(MessagingListener listener) {
        if (listener != null) {
            memorizingMessagingListener.refreshOther(listener);
        }
    }

    public void removeListener(@NonNull MessagingListener listener) {
        listeners.remove(listener);
    }

    public Set<MessagingListener> getListeners() {
        return listeners;
    }


    public Set<MessagingListener> getListeners(MessagingListener listener) {
        if (listener == null) {
            return listeners;
        }

        Set<MessagingListener> listeners = new HashSet<>(this.listeners);
        listeners.add(listener);
        return listeners;

    }


    void suppressMessages(Account account, List<LocalMessage> messages) {
        MessageListCache cache = MessageListCache.getCache(account.getUuid());
        cache.hideMessages(messages);
    }

    private void unsuppressMessages(Account account, List<LocalMessage> messages) {
        MessageListCache cache = MessageListCache.getCache(account.getUuid());
        cache.unhideMessages(messages);
    }

    public boolean isMessageSuppressed(LocalMessage message) {
        long messageId = message.getDatabaseId();
        long folderId = message.getFolder().getDatabaseId();

        MessageListCache cache = MessageListCache.getCache(message.getFolder().getAccountUuid());
        return cache.isMessageHidden(messageId, folderId);
    }

    private void setFlagInCache(final Account account, final List<Long> messageIds,
            final Flag flag, final boolean newState) {

        MessageListCache cache = MessageListCache.getCache(account.getUuid());
        cache.setFlagForMessages(messageIds, flag, newState);
    }

    private void removeFlagFromCache(final Account account, final List<Long> messageIds,
            final Flag flag) {

        MessageListCache cache = MessageListCache.getCache(account.getUuid());
        cache.removeFlagForMessages(messageIds, flag);
    }

    private void setFlagForThreadsInCache(final Account account, final List<Long> threadRootIds,
            final Flag flag, final boolean newState) {

        MessageListCache cache = MessageListCache.getCache(account.getUuid());
        cache.setValueForThreads(threadRootIds, flag, newState);
    }

    private void removeFlagForThreadsFromCache(final Account account, final List<Long> messageIds,
            final Flag flag) {

        MessageListCache cache = MessageListCache.getCache(account.getUuid());
        cache.removeFlagForThreads(messageIds, flag);
    }

    public void refreshFolderList(final Account account) {
        put("refreshFolderList", null, () -> refreshFolderListSynchronous(account));
    }

    public void refreshFolderListBlocking(Account account) {
        final CountDownLatch latch = new CountDownLatch(1);
        putBackground("refreshFolderListBlocking", null, () -> {
            try {
                refreshFolderListSynchronous(account);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (Exception e) {
            Timber.e(e, "Interrupted while awaiting latch release");
        }
    }

    void refreshFolderListSynchronous(Account account) {
        try {
            if (isAuthenticationProblem(account, true)) {
                Timber.d("Authentication will fail. Skip refreshing the folder list.");
                handleAuthenticationFailure(account, true);
                return;
            }

            Backend backend = getBackend(account);
            backend.refreshFolderList();

            long now = System.currentTimeMillis();
            Timber.d("Folder list successfully refreshed @ %tc", now);

            account.setLastFolderListRefreshTime(now);
            preferences.saveAccount(account);
        } catch (Exception e) {
            Timber.e(e);
            handleException(account, e);
        }
    }

    public Future<?> searchRemoteMessages(String acctUuid, long folderId, String query, Set<Flag> requiredFlags,
            Set<Flag> forbiddenFlags, MessagingListener listener) {
        Timber.i("searchRemoteMessages (acct = %s, folderId = %d, query = %s)", acctUuid, folderId, query);

        return threadPool.submit(() ->
                searchRemoteMessagesSynchronous(acctUuid, folderId, query, requiredFlags, forbiddenFlags, listener)
        );
    }

    @VisibleForTesting
    void searchRemoteMessagesSynchronous(String acctUuid, long folderId, String query, Set<Flag> requiredFlags,
            Set<Flag> forbiddenFlags, MessagingListener listener) {

        Account account = preferences.getAccount(acctUuid);

        if (listener != null) {
            listener.remoteSearchStarted(folderId);
        }

        List<String> extraResults = new ArrayList<>();
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);

            LocalFolder localFolder = localStore.getFolder(folderId);
            if (!localFolder.exists()) {
                throw new MessagingException("Folder not found");
            }

            localFolder.open();
            String folderServerId = localFolder.getServerId();

            Backend backend = getBackend(account);

            boolean performFullTextSearch = account.isRemoteSearchFullText();
            List<String> messageServerIds = backend.search(folderServerId, query, requiredFlags, forbiddenFlags,
                    performFullTextSearch);

            Timber.i("Remote search got %d results", messageServerIds.size());

            // There's no need to fetch messages already completely downloaded
            messageServerIds = localFolder.extractNewMessages(messageServerIds);

            if (listener != null) {
                listener.remoteSearchServerQueryComplete(folderId, messageServerIds.size(),
                        account.getRemoteSearchNumResults());
            }

            int resultLimit = account.getRemoteSearchNumResults();
            if (resultLimit > 0 && messageServerIds.size() > resultLimit) {
                extraResults = messageServerIds.subList(resultLimit, messageServerIds.size());
                messageServerIds = messageServerIds.subList(0, resultLimit);
            }

            loadSearchResultsSynchronous(account, messageServerIds, localFolder);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Timber.i(e, "Caught exception on aborted remote search; safe to ignore.");
            } else {
                Timber.e(e, "Could not complete remote search");
                if (listener != null) {
                    listener.remoteSearchFailed(null, e.getMessage());
                }
                Timber.e(e);
            }
        } finally {
            if (listener != null) {
                listener.remoteSearchFinished(folderId, 0, account.getRemoteSearchNumResults(), extraResults);
            }
        }

    }

    public void loadSearchResults(Account account, long folderId, List<String> messageServerIds,
            MessagingListener listener) {
        threadPool.execute(() -> {
            if (listener != null) {
                listener.enableProgressIndicator(true);
            }

            try {
                LocalStore localStore = localStoreProvider.getInstance(account);
                LocalFolder localFolder = localStore.getFolder(folderId);
                if (!localFolder.exists()) {
                    throw new MessagingException("Folder not found");
                }

                localFolder.open();

                loadSearchResultsSynchronous(account, messageServerIds, localFolder);
            } catch (MessagingException e) {
                Timber.e(e, "Exception in loadSearchResults");
            } finally {
                if (listener != null) {
                    listener.enableProgressIndicator(false);
                }
            }
        });
    }

    private void loadSearchResultsSynchronous(Account account, List<String> messageServerIds, LocalFolder localFolder)
            throws MessagingException {

        Backend backend = getBackend(account);
        String folderServerId = localFolder.getServerId();

        for (String messageServerId : messageServerIds) {
            LocalMessage localMessage = localFolder.getMessage(messageServerId);

            if (localMessage == null) {
                backend.downloadMessageStructure(folderServerId, messageServerId);
            }
        }
    }

    public void loadMoreMessages(Account account, long folderId) {
        putBackground("loadMoreMessages", null, () -> loadMoreMessagesSynchronous(account, folderId));
    }

    public void loadMoreMessagesSynchronous(Account account, long folderId) {
        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        Integer visibleLimit = messageStore.getFolder(folderId, FolderDetailsAccessor::getVisibleLimit);
        if (visibleLimit == null) {
            Timber.v("loadMoreMessages(%s, %d): Folder not found", account, folderId);
            return;
        }

        if (visibleLimit > 0) {
            int newVisibleLimit = visibleLimit + account.getDisplayCount();
            messageStore.setVisibleLimit(folderId, newVisibleLimit);
        }

        synchronizeMailboxSynchronous(account, folderId, false, null, new NotificationState());
    }

    /**
     * Start background synchronization of the specified folder.
     */
    public void synchronizeMailbox(Account account, long folderId, boolean notify, MessagingListener listener) {
        putBackground("synchronizeMailbox", listener, () ->
                synchronizeMailboxSynchronous(account, folderId, notify, listener, new NotificationState())
        );
    }

    public void synchronizeMailboxBlocking(Account account, String folderServerId) {
        long folderId = getFolderId(account, folderServerId);

        final CountDownLatch latch = new CountDownLatch(1);
        putBackground("synchronizeMailbox", null, () -> {
            try {
                synchronizeMailboxSynchronous(account, folderId, true, null, new NotificationState());
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (Exception e) {
            Timber.e(e, "Interrupted while awaiting latch release");
        }
    }

    private void synchronizeMailboxSynchronous(Account account, long folderId, boolean notify,
            MessagingListener listener, NotificationState notificationState) {
        refreshFolderListIfStale(account);

        Backend backend = getBackend(account);
        syncFolder(account, folderId, notify, listener, backend, notificationState);
    }

    private void refreshFolderListIfStale(Account account) {
        long lastFolderListRefresh = account.getLastFolderListRefreshTime();
        long now = System.currentTimeMillis();

        if (lastFolderListRefresh > now || lastFolderListRefresh + FOLDER_LIST_STALENESS_THRESHOLD <= now) {
            Timber.d("Last folder list refresh @ %tc. Refreshing now…", lastFolderListRefresh);
            refreshFolderListSynchronous(account);
        } else {
            Timber.d("Last folder list refresh @ %tc. Not refreshing now.", lastFolderListRefresh);
        }
    }

    private void syncFolder(Account account, long folderId, boolean notify, MessagingListener listener, Backend backend,
            NotificationState notificationState) {
        if (isAuthenticationProblem(account, true)) {
            Timber.d("Authentication will fail. Skip synchronizing folder %d.", folderId);
            handleAuthenticationFailure(account, true);
            return;
        }

        Exception commandException = null;
        try {
            processPendingCommandsSynchronous(account);
        } catch (Exception e) {
            Timber.e(e, "Failure processing command, but allow message sync attempt");
            commandException = e;
        }

        LocalFolder localFolder;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localFolder = localStore.getFolder(folderId);
            localFolder.open();
        } catch (MessagingException e) {
            Timber.e(e, "syncFolder: Couldn't load local folder %d", folderId);
            return;
        }

        // We can't sync local folders
        if (localFolder.isLocalOnly()) {
            return;
        }

        final boolean suppressNotifications;
        if (notify) {
            MessageStore messageStore = messageStoreManager.getMessageStore(account);
            Long lastChecked = messageStore.getFolder(folderId, FolderDetailsAccessor::getLastChecked);
            suppressNotifications = lastChecked == null;
        } else {
            suppressNotifications = true;
        }

        String folderServerId = localFolder.getServerId();
        SyncConfig syncConfig = createSyncConfig(account);
        ControllerSyncListener syncListener =
                new ControllerSyncListener(account, listener, suppressNotifications, notificationState);

        backend.sync(folderServerId, syncConfig, syncListener);

        if (commandException != null && !syncListener.syncFailed) {
            String rootMessage = getRootCauseMessage(commandException);
            Timber.e("Root cause failure in %s:%s was '%s'", account, folderServerId, rootMessage);
            updateFolderStatus(account, folderId, rootMessage);
            listener.synchronizeMailboxFailed(account, folderId, rootMessage);
        }
    }

    private SyncConfig createSyncConfig(Account account) {
        return new SyncConfig(
                    account.getExpungePolicy().toBackendExpungePolicy(),
                    account.getEarliestPollDate(),
                    account.isSyncRemoteDeletions(),
                    account.getMaximumAutoDownloadMessageSize(),
                    K9.DEFAULT_VISIBLE_LIMIT,
                    SYNC_FLAGS);
    }

    private void updateFolderStatus(Account account, long folderId, String status) {
        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        messageStore.setStatus(folderId, status);
    }

    public void handleAuthenticationFailure(Account account, boolean incoming) {
        if (account.shouldMigrateToOAuth()) {
            migrateAccountToOAuth(account);
        }

        notificationController.showAuthenticationErrorNotification(account, incoming);
    }

    private void migrateAccountToOAuth(Account account) {
        account.setIncomingServerSettings(account.getIncomingServerSettings().newAuthenticationType(AuthType.XOAUTH2));
        account.setOutgoingServerSettings(account.getOutgoingServerSettings().newAuthenticationType(AuthType.XOAUTH2));
        account.setShouldMigrateToOAuth(false);

        preferences.saveAccount(account);
    }

    public void handleException(Account account, Exception exception) {
        if (exception instanceof AuthenticationFailedException) {
            handleAuthenticationFailure(account, true);
        } else {
            notifyUserIfCertificateProblem(account, exception, true);
        }
    }

    void queuePendingCommand(Account account, PendingCommand command) {
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localStore.addPendingCommand(command);
        } catch (Exception e) {
            throw new RuntimeException("Unable to enqueue pending command", e);
        }
    }

    void processPendingCommands(final Account account) {
        putBackground("processPendingCommands", null, new Runnable() {
            @Override
            public void run() {
                try {
                    processPendingCommandsSynchronous(account);
                } catch (MessagingException me) {
                    Timber.e(me, "processPendingCommands");

                    /*
                     * Ignore any exceptions from the commands. Commands will be processed
                     * on the next round.
                     */
                }
            }
        });
    }

    public void processPendingCommandsSynchronous(Account account) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        List<PendingCommand> commands = localStore.getPendingCommands();

        PendingCommand processingCommand = null;
        try {
            for (PendingCommand command : commands) {
                processingCommand = command;
                String commandName = command.getCommandName();
                Timber.d("Processing pending command '%s'", commandName);

                /*
                 * We specifically do not catch any exceptions here. If a command fails it is
                 * most likely due to a server or IO error and it must be retried before any
                 * other command processes. This maintains the order of the commands.
                 */
                try {
                    command.execute(this, account);

                    localStore.removePendingCommand(command);

                    Timber.d("Done processing pending command '%s'", commandName);
                } catch (MessagingException me) {
                    if (me.isPermanentFailure()) {
                        Timber.e(me, "Failure of command '%s' was permanent, removing command from queue", commandName);
                        localStore.removePendingCommand(processingCommand);
                    } else {
                        throw me;
                    }
                } catch (Exception e) {
                    Timber.e(e, "Unexpected exception with command '%s', removing command from queue", commandName);
                    localStore.removePendingCommand(processingCommand);

                    if (K9.DEVELOPER_MODE) {
                        throw new AssertionError("Unexpected exception while processing pending command", e);
                    }
                }

                // TODO: When removing a pending command due to an error the local changes should be reverted. Pending
                //  commands that depend on this command should be canceled and local changes be reverted. In most cases
                //  the user should be notified about the failure as well.
            }
        } catch (MessagingException me) {
            notifyUserIfCertificateProblem(account, me, true);
            Timber.e(me, "Could not process command '%s'", processingCommand);
            throw me;
        }
    }

    /**
     * Process a pending append message command. This command uploads a local message to the
     * server, first checking to be sure that the server message is not newer than
     * the local message. Once the local message is successfully processed it is deleted so
     * that the server message will be synchronized down without an additional copy being
     * created.
     */
    void processPendingAppend(PendingAppend command, Account account) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        long folderId = command.folderId;
        LocalFolder localFolder = localStore.getFolder(folderId);
        localFolder.open();

        String folderServerId = localFolder.getServerId();
        String uid = command.uid;

        LocalMessage localMessage = localFolder.getMessage(uid);
        if (localMessage == null) {
            return;
        }

        if (!localMessage.getUid().startsWith(K9.LOCAL_UID_PREFIX)) {
            //FIXME: This should never happen. Throw in debug builds.
            return;
        }

        Backend backend = getBackend(account);

        if (localMessage.isSet(Flag.X_REMOTE_COPY_STARTED)) {
            Timber.w("Local message with uid %s has flag %s  already set, checking for remote message with " +
                    "same message id", localMessage.getUid(), X_REMOTE_COPY_STARTED);

            String messageServerId = backend.findByMessageId(folderServerId, localMessage.getMessageId());
            if (messageServerId != null) {
                Timber.w("Local message has flag %s already set, and there is a remote message with uid %s, " +
                        "assuming message was already copied and aborting this copy",
                        X_REMOTE_COPY_STARTED, messageServerId);

                String oldUid = localMessage.getUid();
                localMessage.setUid(messageServerId);
                localFolder.changeUid(localMessage);

                for (MessagingListener l : getListeners()) {
                    l.messageUidChanged(account, folderId, oldUid, localMessage.getUid());
                }

                return;
            } else {
                Timber.w("No remote message with message-id found, proceeding with append");
            }
        }

        /*
         * If the message does not exist remotely we just upload it and then
         * update our local copy with the new uid.
         */
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        localFolder.fetch(Collections.singletonList(localMessage), fp, null);
        String oldUid = localMessage.getUid();
        localMessage.setFlag(Flag.X_REMOTE_COPY_STARTED, true);

        String messageServerId = backend.uploadMessage(folderServerId, localMessage);

        if (messageServerId == null) {
            // We didn't get the server UID of the uploaded message. Remove the local message now. The uploaded
            // version will be downloaded during the next sync.
            localFolder.destroyMessages(Collections.singletonList(localMessage));
        } else {
            localMessage.setUid(messageServerId);
            localFolder.changeUid(localMessage);

            for (MessagingListener l : getListeners()) {
                l.messageUidChanged(account, folderId, oldUid, localMessage.getUid());
            }
        }
    }

    void processPendingReplace(PendingReplace pendingReplace, Account account) {
        draftOperations.processPendingReplace(pendingReplace, account);
    }

    private void queueMoveOrCopy(Account account, long srcFolderId, long destFolderId, MoveOrCopyFlavor operation,
            Map<String, String> uidMap) {
        PendingCommand command;
        switch (operation) {
            case MOVE:
                command = PendingMoveOrCopy.create(srcFolderId, destFolderId, false, uidMap);
                break;
            case COPY:
                command = PendingMoveOrCopy.create(srcFolderId, destFolderId, true, uidMap);
                break;
            case MOVE_AND_MARK_AS_READ:
                command = PendingMoveAndMarkAsRead.create(srcFolderId, destFolderId, uidMap);
                break;
            default:
                return;
        }
        queuePendingCommand(account, command);
    }

    void processPendingMoveOrCopy(PendingMoveOrCopy command, Account account) throws MessagingException {
        long srcFolder = command.srcFolderId;
        long destFolder = command.destFolderId;
        MoveOrCopyFlavor operation = command.isCopy ? MoveOrCopyFlavor.COPY : MoveOrCopyFlavor.MOVE;

        Map<String, String> newUidMap = command.newUidMap;
        List<String> uids = newUidMap != null ? new ArrayList<>(newUidMap.keySet()) : command.uids;

        processPendingMoveOrCopy(account, srcFolder, destFolder, uids, operation, newUidMap);
    }

    void processPendingMoveAndRead(PendingMoveAndMarkAsRead command, Account account) throws MessagingException {
        long srcFolder = command.srcFolderId;
        long destFolder = command.destFolderId;
        Map<String, String> newUidMap = command.newUidMap;
        List<String> uids = new ArrayList<>(newUidMap.keySet());

        processPendingMoveOrCopy(account, srcFolder, destFolder, uids,
                MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ, newUidMap);
    }

    @VisibleForTesting
    void processPendingMoveOrCopy(Account account, long srcFolderId, long destFolderId, List<String> uids,
                                  MoveOrCopyFlavor operation, Map<String, String> newUidMap) throws MessagingException {
        requireNotNull(newUidMap);

        LocalStore localStore = localStoreProvider.getInstance(account);

        LocalFolder localSourceFolder = localStore.getFolder(srcFolderId);
        localSourceFolder.open();
        String srcFolderServerId = localSourceFolder.getServerId();

        LocalFolder localDestFolder = localStore.getFolder(destFolderId);
        localDestFolder.open();
        String destFolderServerId = localDestFolder.getServerId();

        Backend backend = getBackend(account);

        Map<String, String> remoteUidMap;
        switch (operation) {
            case COPY:
                remoteUidMap = backend.copyMessages(srcFolderServerId, destFolderServerId, uids);
                break;
            case MOVE:
                remoteUidMap = backend.moveMessages(srcFolderServerId, destFolderServerId, uids);
                break;
            case MOVE_AND_MARK_AS_READ:
                remoteUidMap = backend.moveMessagesAndMarkAsRead(srcFolderServerId, destFolderServerId, uids);
                break;
            default:
                throw new RuntimeException("Unsupported messaging operation");
        }

        if (operation != MoveOrCopyFlavor.COPY) {
            destroyPlaceholderMessages(localSourceFolder, uids);
        }

        // TODO: Change Backend interface to ensure we never receive null for remoteUidMap
        if (remoteUidMap == null) {
            remoteUidMap = Collections.emptyMap();
        }

        // Update local messages (that currently have local UIDs) with new server IDs
        for (String uid : uids) {
            String localUid = newUidMap.get(uid);
            String newUid = remoteUidMap.get(uid);

            LocalMessage localMessage = localDestFolder.getMessage(localUid);
            if (localMessage == null) {
                // Local message no longer exists
                continue;
            }

            if (newUid != null) {
                // Update local message with new server ID
                localMessage.setUid(newUid);
                localDestFolder.changeUid(localMessage);
                for (MessagingListener l : getListeners()) {
                    l.messageUidChanged(account, destFolderId, localUid, newUid);
                }
            } else {
                // New server ID wasn't provided. Remove local message.
                localMessage.destroy();
            }
        }
    }

    void destroyPlaceholderMessages(LocalFolder localFolder, List<String> uids) throws MessagingException {
        for (String uid : uids) {
            LocalMessage placeholderMessage = localFolder.getMessage(uid);
            if (placeholderMessage == null) {
                continue;
            }

            if (placeholderMessage.isSet(Flag.DELETED)) {
                placeholderMessage.destroy();
            } else {
                Timber.w("Expected local message %s in folder %s to be a placeholder, but DELETE flag wasn't set",
                        uid, localFolder.getServerId());

                if (BuildConfig.DEBUG) {
                    throw new AssertionError("Placeholder message must have the DELETED flag set");
                }
            }
        }
    }

    private void queueSetFlag(Account account, long folderId, boolean newState, Flag flag, List<String> uids) {
        PendingCommand command = PendingSetFlag.create(folderId, newState, flag, uids);
        queuePendingCommand(account, command);
    }

    /**
     * Processes a pending mark read or unread command.
     */
    void processPendingSetFlag(PendingSetFlag command, Account account) throws MessagingException {
        Backend backend = getBackend(account);
        String folderServerId = getFolderServerId(account, command.folderId);
        backend.setFlag(folderServerId, command.uids, command.flag, command.newState);
    }

    private void queueDelete(Account account, long folderId, List<String> uids) {
        PendingCommand command = PendingDelete.create(folderId, uids);
        queuePendingCommand(account, command);
    }

    void processPendingDelete(PendingDelete command, Account account) throws MessagingException {
        long folderId = command.folderId;
        List<String> uids = command.uids;

        Backend backend = getBackend(account);
        String folderServerId = getFolderServerId(account, folderId);
        backend.deleteMessages(folderServerId, uids);

        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderId);
        localFolder.open();
        destroyPlaceholderMessages(localFolder, uids);
    }

    private void queueExpunge(Account account, long folderId) {
        PendingCommand command = PendingExpunge.create(folderId);
        queuePendingCommand(account, command);
    }

    void processPendingExpunge(PendingExpunge command, Account account) throws MessagingException {
        Backend backend = getBackend(account);
        String folderServerId = getFolderServerId(account, command.folderId);
        backend.expunge(folderServerId);
    }

    void processPendingMarkAllAsRead(PendingMarkAllAsRead command, Account account) throws MessagingException {
        long folderId = command.folderId;
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderId);

        localFolder.open();
        String folderServerId = localFolder.getServerId();

        Timber.i("Marking all messages in %s:%s as read", account, folderServerId);

        // TODO: Make this one database UPDATE operation
        List<LocalMessage> messages = localFolder.getMessages(false);
        for (Message message : messages) {
            if (!message.isSet(Flag.SEEN)) {
                message.setFlag(Flag.SEEN, true);
            }
        }

        for (MessagingListener l : getListeners()) {
            l.folderStatusChanged(account, folderId);
        }

        Backend backend = getBackend(account);
        if (backend.getSupportsFlags()) {
            backend.markAllAsRead(folderServerId);
        }
    }

    public void markAllMessagesRead(Account account, long folderId) {
        PendingCommand command = PendingMarkAllAsRead.create(folderId);
        queuePendingCommand(account, command);
        processPendingCommands(account);
    }

    public void setFlag(final Account account, final List<Long> messageIds, final Flag flag,
            final boolean newState) {

        setFlagInCache(account, messageIds, flag, newState);

        putBackground("setFlag", null, () ->
            setFlagSynchronous(account, messageIds, flag, newState, false)
        );
    }

    public void setFlagForThreads(final Account account, final List<Long> threadRootIds,
            final Flag flag, final boolean newState) {

        setFlagForThreadsInCache(account, threadRootIds, flag, newState);

        putBackground("setFlagForThreads", null, () ->
            setFlagSynchronous(account, threadRootIds, flag, newState, true)
        );
    }

    private void setFlagSynchronous(final Account account, final List<Long> ids,
            final Flag flag, final boolean newState, final boolean threadedList) {

        LocalStore localStore;
        try {
            localStore = localStoreProvider.getInstance(account);
        } catch (MessagingException e) {
            Timber.e(e, "Couldn't get LocalStore instance");
            return;
        }

        // Update affected messages in the database. This should be as fast as possible so the UI
        // can be updated with the new state.
        try {
            if (threadedList) {
                localStore.setFlagForThreads(ids, flag, newState);
                removeFlagForThreadsFromCache(account, ids, flag);
            } else {
                localStore.setFlag(ids, flag, newState);
                removeFlagFromCache(account, ids, flag);
            }
        } catch (MessagingException e) {
            Timber.e(e, "Couldn't set flags in local database");
        }

        // Read folder ID and UID of messages from the database
        Map<Long, List<String>> folderMap;
        try {
            folderMap = localStore.getFolderIdsAndUids(ids, threadedList);
        } catch (MessagingException e) {
            Timber.e(e, "Couldn't get folder name and UID of messages");
            return;
        }

        boolean accountSupportsFlags = supportsFlags(account);

        // Loop over all folders
        for (Entry<Long, List<String>> entry : folderMap.entrySet()) {
            long folderId = entry.getKey();
            List<String> uids = entry.getValue();

            // Notify listeners of changed folder status
            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folderId);
            }

            if (flag == Flag.SEEN && newState) {
                cancelNotificationsForMessages(account, folderId, uids);
            }

            if (accountSupportsFlags) {
                LocalFolder localFolder = localStore.getFolder(folderId);
                try {
                    localFolder.open();
                    if (!localFolder.isLocalOnly()) {
                        // Send flag change to server
                        queueSetFlag(account, folderId, newState, flag, uids);
                        processPendingCommands(account);
                    }
                } catch (MessagingException e) {
                    Timber.e(e, "Couldn't open folder. Account: %s, folder ID: %d", account, folderId);
                }
            }
        }
    }

    private void cancelNotificationsForMessages(Account account, long folderId, List<String> uids) {
        for (String uid : uids) {
            MessageReference messageReference = new MessageReference(account.getUuid(), folderId, uid);
            notificationController.removeNewMailNotification(account, messageReference);
        }
    }

    /**
     * Set or remove a flag for a set of messages in a specific folder.
     * <p>
     * The {@link Message} objects passed in are updated to reflect the new flag state.
     * </p>
     */
    public void setFlag(Account account, long folderId, List<LocalMessage> messages, Flag flag, boolean newState) {
        // TODO: Put this into the background, but right now some callers depend on the message
        //       objects being modified right after this method returns.
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(folderId);
            localFolder.open();

            // Update the messages in the local store
            localFolder.setFlags(messages, Collections.singleton(flag), newState);

            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folderId);
            }

            // Handle the remote side
            if (supportsFlags(account) && !localFolder.isLocalOnly()) {
                List<String> uids = getUidsFromMessages(messages);
                queueSetFlag(account, folderId, newState, flag, uids);
                processPendingCommands(account);
            }
        } catch (MessagingException me) {
            throw new RuntimeException(me);
        }
    }

    /**
     * Set or remove a flag for a message referenced by message UID.
     */
    public void setFlag(Account account, long folderId, String uid, Flag flag, boolean newState) {
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(folderId);
            localFolder.open();

            LocalMessage message = localFolder.getMessage(uid);
            if (message != null) {
                setFlag(account, folderId, Collections.singletonList(message), flag, newState);
            }
        } catch (MessagingException me) {
            throw new RuntimeException(me);
        }
    }

    public void loadMessageRemotePartial(Account account, long folderId, String uid, MessagingListener listener) {
        put("loadMessageRemotePartial", listener, () ->
            loadMessageRemoteSynchronous(account, folderId, uid, listener, true)
        );
    }

    //TODO: Fix the callback mess. See GH-782
    public void loadMessageRemote(Account account, long folderId, String uid, MessagingListener listener) {
        put("loadMessageRemote", listener, () ->
            loadMessageRemoteSynchronous(account, folderId, uid, listener, false)
        );
    }

    private void loadMessageRemoteSynchronous(Account account, long folderId, String messageServerId,
            MessagingListener listener, boolean loadPartialFromSearch) {
        try {
            if (messageServerId.startsWith(K9.LOCAL_UID_PREFIX)) {
                throw new IllegalArgumentException("Must not be called with a local UID");
            }

            MessageStore messageStore = messageStoreManager.getMessageStore(account);

            String folderServerId = messageStore.getFolderServerId(folderId);
            if (folderServerId == null) {
                throw new IllegalStateException("Folder not found (ID: " + folderId + ")");
            }

            Backend backend = getBackend(account);

            if (loadPartialFromSearch) {
                SyncConfig syncConfig = createSyncConfig(account);
                backend.downloadMessage(syncConfig, folderServerId, messageServerId);
            } else {
                backend.downloadCompleteMessage(folderServerId, messageServerId);
            }

            for (MessagingListener l : getListeners(listener)) {
                l.loadMessageRemoteFinished(account, folderId, messageServerId);
            }
        } catch (Exception e) {
            for (MessagingListener l : getListeners(listener)) {
                l.loadMessageRemoteFailed(account, folderId, messageServerId, e);
            }

            notifyUserIfCertificateProblem(account, e, true);
            Timber.e(e, "Error while loading remote message");
        }
    }

    public LocalMessage loadMessage(Account account, long folderId, String uid) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderId);
        localFolder.open();

        LocalMessage message = localFolder.getMessage(uid);
        if (message == null || message.getDatabaseId() == 0) {
            String folderName = localFolder.getName();
            throw new IllegalArgumentException("Message not found: folder=" + folderName + ", uid=" + uid);
        }

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        localFolder.fetch(Collections.singletonList(message), fp, null);

        return message;
    }

    public LocalMessage loadMessageMetadata(Account account, long folderId, String uid) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderId);
        localFolder.open();

        LocalMessage message = localFolder.getMessage(uid);
        if (message == null || message.getDatabaseId() == 0) {
            String folderName = localFolder.getName();
            throw new IllegalArgumentException("Message not found: folder=" + folderName + ", uid=" + uid);
        }

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        localFolder.fetch(Collections.singletonList(message), fp, null);

        return message;
    }

    public void markMessageAsOpened(Account account, LocalMessage message) {
        threadPool.execute(() ->
            notificationController.removeNewMailNotification(account, message.makeMessageReference())
        );

        if (message.isSet(Flag.SEEN)) {
            // Nothing to do if the message is already marked as read
            return;
        }

        boolean markMessageAsRead = account.isMarkMessageAsReadOnView();
        if (markMessageAsRead) {
            // Mark the message itself as read right away
            try {
                message.setFlagInternal(Flag.SEEN, true);
            } catch (MessagingException e) {
                Timber.e(e, "Error while marking message as read");
            }

            // Also mark the message as read in the cache
            List<Long> messageIds = Collections.singletonList(message.getDatabaseId());
            setFlagInCache(account, messageIds, Flag.SEEN, true);
        }

        putBackground("markMessageAsOpened", null, () -> {
            markMessageAsOpenedBlocking(account, message, markMessageAsRead);
        });
    }

    private void markMessageAsOpenedBlocking(Account account, LocalMessage message, boolean markMessageAsRead) {
        if (markMessageAsRead) {
            markMessageAsRead(account, message);
        } else {
            // Marking a message as read will automatically mark it as "not new". But if we don't mark the message
            // as read on opening, we have to manually mark it as "not new".
            markMessageAsNotNew(account, message);
        }
    }

    private void markMessageAsRead(Account account, LocalMessage message) {
        List<Long> messageIds = Collections.singletonList(message.getDatabaseId());
        setFlagSynchronous(account, messageIds, Flag.SEEN, true, false);
    }

    private void markMessageAsNotNew(Account account, LocalMessage message) {
        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        long folderId = message.getFolder().getDatabaseId();
        String messageServerId = message.getUid();
        messageStore.setNewMessageState(folderId, messageServerId, false);
    }

    public void clearNewMessages(Account account) {
        put("clearNewMessages", null, () -> clearNewMessagesBlocking(account));
    }

    private void clearNewMessagesBlocking(Account account) {
        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        messageStore.clearNewMessageState();
    }

    public void loadAttachment(final Account account, final LocalMessage message, final Part part,
            final MessagingListener listener) {

        put("loadAttachment", listener, new Runnable() {
            @Override
            public void run() {
                try {
                    String folderServerId = message.getFolder().getServerId();

                    LocalStore localStore = localStoreProvider.getInstance(account);
                    LocalFolder localFolder = localStore.getFolder(folderServerId);

                    ProgressBodyFactory bodyFactory = new ProgressBodyFactory(new ProgressListener() {
                        @Override
                        public void updateProgress(int progress) {
                            for (MessagingListener listener : getListeners()) {
                                listener.updateProgress(progress);
                            }
                        }
                    });

                    Backend backend = getBackend(account);
                    backend.fetchPart(folderServerId, message.getUid(), part, bodyFactory);

                    localFolder.addPartToMessage(message, part);

                    for (MessagingListener l : getListeners(listener)) {
                        l.loadAttachmentFinished(account, message, part);
                    }
                } catch (MessagingException me) {
                    Timber.v(me, "Exception loading attachment");

                    for (MessagingListener l : getListeners(listener)) {
                        l.loadAttachmentFailed(account, message, part, me.getMessage());
                    }
                    notifyUserIfCertificateProblem(account, me, true);
                }
            }
        });
    }

    /**
     * Stores the given message in the Outbox and starts a sendPendingMessages command to attempt to send the message.
     */
    public void sendMessage(Account account, Message message, String plaintextSubject, MessagingListener listener) {
        try {
            Long outboxFolderId = account.getOutboxFolderId();
            if (outboxFolderId == null) {
                if (BuildConfig.DEBUG) {
                    throw new AssertionError("Outbox does not exist");
                }

                Timber.w("Outbox does not exist");

                outboxFolderId = specialLocalFoldersCreator.createOutbox(account);
            }

            message.setFlag(Flag.SEEN, true);

            MessageStore messageStore = messageStoreManager.getMessageStore(account);
            SaveMessageData messageData = saveMessageDataCreator.createSaveMessageData(
                    message, MessageDownloadState.FULL, plaintextSubject);
            long messageId = messageStore.saveLocalMessage(outboxFolderId, messageData, null);

            LocalStore localStore = localStoreProvider.getInstance(account);
            OutboxStateRepository outboxStateRepository = localStore.getOutboxStateRepository();
            outboxStateRepository.initializeOutboxState(messageId);

            sendPendingMessages(account, listener);
        } catch (Exception e) {
            Timber.e(e, "Error sending message");
        }
    }

    public void sendMessageBlocking(Account account, Message message) throws MessagingException {
        Backend backend = getBackend(account);
        backend.sendMessage(message);
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     */
    public void sendPendingMessages(final Account account,
            MessagingListener listener) {
        putBackground("sendPendingMessages", listener, new Runnable() {
            @Override
            public void run() {
                if (messagesPendingSend(account)) {

                    showSendingNotificationIfNecessary(account);

                    try {
                        sendPendingMessagesSynchronous(account);
                    } finally {
                        clearSendingNotificationIfNecessary(account);
                    }
                }
            }
        });
    }

    private void showSendingNotificationIfNecessary(Account account) {
        if (account.isNotifySync()) {
            notificationController.showSendingNotification(account);
        }
    }

    private void clearSendingNotificationIfNecessary(Account account) {
        if (account.isNotifySync()) {
            notificationController.clearSendingNotification(account);
        }
    }

    private boolean messagesPendingSend(final Account account) {
        Long outboxFolderId = account.getOutboxFolderId();
        if (outboxFolderId == null) {
            Timber.w("Could not get Outbox folder ID from Account");
            return false;
        }

        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        return messageStore.getMessageCount(outboxFolderId) > 0;
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     */
    @VisibleForTesting
    protected void sendPendingMessagesSynchronous(final Account account) {
        Exception lastFailure = null;
        try {
            if (isAuthenticationProblem(account, false)) {
                Timber.d("Authentication will fail. Skip sending messages.");
                handleAuthenticationFailure(account, false);
                return;
            }

            LocalStore localStore = localStoreProvider.getInstance(account);
            OutboxStateRepository outboxStateRepository = localStore.getOutboxStateRepository();
            LocalFolder localFolder = localStore.getFolder(account.getOutboxFolderId());
            if (!localFolder.exists()) {
                Timber.w("Outbox does not exist");
                return;
            }

            localFolder.open();

            long outboxFolderId = localFolder.getDatabaseId();

            List<LocalMessage> localMessages = localFolder.getMessages();
            int progress = 0;
            int todo = localMessages.size();
            for (MessagingListener l : getListeners()) {
                l.synchronizeMailboxProgress(account, outboxFolderId, progress, todo);
            }
            /*
             * The profile we will use to pull all of the content
             * for a given local message into memory for sending.
             */
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.BODY);

            Timber.i("Scanning Outbox folder for messages to send");

            Backend backend = getBackend(account);

            for (LocalMessage message : localMessages) {
                if (message.isSet(Flag.DELETED)) {
                    //FIXME: When uploading a message to the remote Sent folder the move code creates a placeholder
                    // message in the Outbox. This code gets rid of these messages. It'd be preferable if the
                    // placeholder message was never created, though.
                    message.destroy();
                    continue;
                }
                try {
                    long messageId = message.getDatabaseId();
                    OutboxState outboxState = outboxStateRepository.getOutboxState(messageId);

                    SendState sendState = outboxState.getSendState();
                    if (sendState != SendState.READY) {
                        Timber.v("Skipping sending message %s (reason: %s - %s)", message.getUid(),
                                sendState.getDatabaseName(), outboxState.getSendError());

                        if (sendState == SendState.RETRIES_EXCEEDED) {
                            lastFailure = new MessagingException("Retries exceeded", true);
                        } else {
                            lastFailure = new MessagingException(outboxState.getSendError(), true);
                        }
                        continue;
                    }

                    Timber.i("Send count for message %s is %d", message.getUid(),
                            outboxState.getNumberOfSendAttempts());

                    localFolder.fetch(Collections.singletonList(message), fp, null);
                    try {
                        if (message.getHeader(K9.IDENTITY_HEADER).length > 0 || message.isSet(Flag.DRAFT)) {
                            Timber.v("The user has set the Outbox and Drafts folder to the same thing. " +
                                    "This message appears to be a draft, so K-9 will not send it");
                            continue;
                        }

                        outboxStateRepository.incrementSendAttempts(messageId);
                        message.setFlag(Flag.X_SEND_IN_PROGRESS, true);

                        Timber.i("Sending message with UID %s", message.getUid());
                        backend.sendMessage(message);

                        message.setFlag(Flag.X_SEND_IN_PROGRESS, false);
                        message.setFlag(Flag.SEEN, true);
                        progress++;
                        for (MessagingListener l : getListeners()) {
                            l.synchronizeMailboxProgress(account, outboxFolderId, progress, todo);
                        }
                        moveOrDeleteSentMessage(account, localStore, message);

                        outboxStateRepository.removeOutboxState(messageId);
                    } catch (AuthenticationFailedException e) {
                        outboxStateRepository.decrementSendAttempts(messageId);
                        lastFailure = e;

                        handleAuthenticationFailure(account, false);
                        handleSendFailure(account, localFolder, message, e);
                    } catch (CertificateValidationException e) {
                        outboxStateRepository.decrementSendAttempts(messageId);
                        lastFailure = e;

                        notifyUserIfCertificateProblem(account, e, false);
                        handleSendFailure(account, localFolder, message, e);
                    } catch (MessagingException e) {
                        lastFailure = e;

                        if (e.isPermanentFailure()) {
                            String errorMessage = e.getMessage();
                            outboxStateRepository.setSendAttemptError(messageId, errorMessage);
                        } else if (outboxState.getNumberOfSendAttempts() + 1 >= MAX_SEND_ATTEMPTS) {
                            outboxStateRepository.setSendAttemptsExceeded(messageId);
                        }

                        handleSendFailure(account, localFolder, message, e);
                    } catch (Exception e) {
                        lastFailure = e;

                        handleSendFailure(account, localFolder, message, e);
                    }
                } catch (Exception e) {
                    lastFailure = e;

                    Timber.e(e, "Failed to fetch message for sending");
                    notifySynchronizeMailboxFailed(account, localFolder, e);
                }
            }

            if (lastFailure != null) {
                notificationController.showSendFailedNotification(account, lastFailure);
            }
        } catch (Exception e) {
            Timber.v(e, "Failed to send pending messages");
        } finally {
            if (lastFailure == null) {
                notificationController.clearSendFailedNotification(account);
            }
        }
    }

    private void moveOrDeleteSentMessage(Account account, LocalStore localStore, LocalMessage message)
            throws MessagingException {
        if (!account.hasSentFolder() || !account.isUploadSentMessages()) {
            Timber.i("Not uploading sent message; deleting local message");
            message.destroy();
        } else {
            long sentFolderId = account.getSentFolderId();
            LocalFolder sentFolder = localStore.getFolder(sentFolderId);
            sentFolder.open();
            String sentFolderServerId = sentFolder.getServerId();
            Timber.i("Moving sent message to folder '%s' (%d)", sentFolderServerId, sentFolderId);

            MessageStore messageStore = messageStoreManager.getMessageStore(account);
            long destinationMessageId = messageStore.moveMessage(message.getDatabaseId(), sentFolderId);

            Timber.i("Moved sent message to folder '%s' (%d)", sentFolderServerId, sentFolderId);

            if (!sentFolder.isLocalOnly()) {
                String destinationUid = messageStore.getMessageServerId(destinationMessageId);
                if (destinationUid != null) {
                    PendingCommand command = PendingAppend.create(sentFolderId, destinationUid);
                    queuePendingCommand(account, command);
                    processPendingCommands(account);
                }
            }
        }

        for (MessagingListener listener : getListeners()) {
            listener.folderStatusChanged(account, account.getOutboxFolderId());
        }
    }

    private void handleSendFailure(Account account, LocalFolder localFolder, Message message, Exception exception)
            throws MessagingException {

        Timber.e(exception, "Failed to send message");
        message.setFlag(Flag.X_SEND_FAILED, true);

        notifySynchronizeMailboxFailed(account, localFolder, exception);
    }

    private void notifySynchronizeMailboxFailed(Account account, LocalFolder localFolder, Exception exception) {
        long folderId = localFolder.getDatabaseId();
        String errorMessage = getRootCauseMessage(exception);
        for (MessagingListener listener : getListeners()) {
            listener.synchronizeMailboxFailed(account, folderId, errorMessage);
        }
    }

    public boolean isMoveCapable(MessageReference messageReference) {
        return !messageReference.getUid().startsWith(K9.LOCAL_UID_PREFIX);
    }

    public boolean isCopyCapable(MessageReference message) {
        return isMoveCapable(message);
    }

    public boolean isMoveCapable(final Account account) {
        return getBackend(account).getSupportsMove();
    }

    public boolean isCopyCapable(final Account account) {
        return getBackend(account).getSupportsCopy();
    }

    public boolean isPushCapable(Account account) {
        return getBackend(account).isPushCapable();
    }

    public boolean supportsFlags(Account account) {
        return getBackend(account).getSupportsFlags();
    }

    public boolean supportsExpunge(Account account) {
        return getBackend(account).getSupportsExpunge();
    }

    public boolean supportsSearchByDate(Account account) {
        return getBackend(account).getSupportsSearchByDate();
    }

    public boolean supportsUpload(Account account) {
        return getBackend(account).getSupportsUpload();
    }

    public boolean supportsFolderSubscriptions(Account account) {
        return getBackend(account).getSupportsFolderSubscriptions();
    }

    public void moveMessages(Account srcAccount, long srcFolderId,
            List<MessageReference> messageReferences, long destFolderId) {
        actOnMessageGroup(srcAccount, srcFolderId, messageReferences, (account, messageFolder, messages) -> {
            suppressMessages(account, messages);

            putBackground("moveMessages", null, () ->
                    moveOrCopyMessageSynchronous(account, srcFolderId, messages, destFolderId, MoveOrCopyFlavor.MOVE)
            );
        });
    }

    public void moveMessagesInThread(Account srcAccount, long srcFolderId,
            List<MessageReference> messageReferences, long destFolderId) {
        actOnMessageGroup(srcAccount, srcFolderId, messageReferences, (account, messageFolder, messages) -> {
            suppressMessages(account, messages);

            putBackground("moveMessagesInThread", null, () -> {
                try {
                    List<LocalMessage> messagesInThreads = collectMessagesInThreads(account, messages);
                    moveOrCopyMessageSynchronous(account, srcFolderId, messagesInThreads, destFolderId,
                            MoveOrCopyFlavor.MOVE);
                } catch (MessagingException e) {
                    Timber.e(e, "Exception while moving messages");
                }
            });
        });
    }

    public void moveMessage(Account account, long srcFolderId, MessageReference message, long destFolderId) {
        moveMessages(account, srcFolderId, Collections.singletonList(message), destFolderId);
    }

    public void copyMessages(Account srcAccount, long srcFolderId,
            List<MessageReference> messageReferences, long destFolderId) {
        actOnMessageGroup(srcAccount, srcFolderId, messageReferences, (account, messageFolder, messages) -> {
            putBackground("copyMessages", null, () ->
                    moveOrCopyMessageSynchronous(srcAccount, srcFolderId, messages, destFolderId, MoveOrCopyFlavor.COPY)
            );
        });
    }

    public void copyMessagesInThread(Account srcAccount, long srcFolderId,
            final List<MessageReference> messageReferences, long destFolderId) {
        actOnMessageGroup(srcAccount, srcFolderId, messageReferences, (account, messageFolder, messages) -> {
            putBackground("copyMessagesInThread", null, () -> {
                try {
                    List<LocalMessage> messagesInThreads = collectMessagesInThreads(account, messages);
                    moveOrCopyMessageSynchronous(account, srcFolderId, messagesInThreads, destFolderId,
                            MoveOrCopyFlavor.COPY);
                } catch (MessagingException e) {
                    Timber.e(e, "Exception while copying messages");
                }
            });
        });
    }

    public void copyMessage(Account account, long srcFolderId, MessageReference message, long destFolderId) {
        copyMessages(account, srcFolderId, Collections.singletonList(message), destFolderId);
    }

    void moveOrCopyMessageSynchronous(Account account, long srcFolderId, List<LocalMessage> inMessages,
            long destFolderId, MoveOrCopyFlavor operation) {

        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            if (operation == MoveOrCopyFlavor.MOVE && !isMoveCapable(account)) {
                return;
            }
            if (operation == MoveOrCopyFlavor.COPY && !isCopyCapable(account)) {
                return;
            }

            LocalFolder localSrcFolder = localStore.getFolder(srcFolderId);
            localSrcFolder.open();

            LocalFolder localDestFolder = localStore.getFolder(destFolderId);
            localDestFolder.open();

            boolean unreadCountAffected = false;
            List<String> uids = new LinkedList<>();
            for (Message message : inMessages) {
                String uid = message.getUid();
                if (!uid.startsWith(K9.LOCAL_UID_PREFIX)) {
                    uids.add(uid);
                }

                if (operation == MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ) {
                    if (!message.isSet(Flag.SEEN)) {
                        unreadCountAffected = true;
                        message.setFlag(Flag.SEEN, true);
                    }
                } else {
                    if (!unreadCountAffected && !message.isSet(Flag.SEEN)) {
                        unreadCountAffected = true;
                    }
                }
            }

            List<LocalMessage> messages = localSrcFolder.getMessagesByUids(uids);
            if (messages.size() > 0) {
                Timber.i("moveOrCopyMessageSynchronous: source folder = %s, %d messages, destination folder = %s, " +
                        "operation = %s", srcFolderId, messages.size(), destFolderId, operation.name());

                MessageStore messageStore = messageStoreManager.getMessageStore(account);

                List<Long> messageIds = new ArrayList<>();
                Map<Long, String> messageIdToUidMapping = new HashMap<>();
                for (LocalMessage message : messages) {
                    long messageId = message.getDatabaseId();
                    messageIds.add(messageId);
                    messageIdToUidMapping.put(messageId, message.getUid());
                }

                Map<Long, Long> resultIdMapping;
                if (operation == MoveOrCopyFlavor.COPY) {
                    resultIdMapping = messageStore.copyMessages(messageIds, destFolderId);

                    if (unreadCountAffected) {
                        // If this copy operation changes the unread count in the destination
                        // folder, notify the listeners.
                        for (MessagingListener l : getListeners()) {
                            l.folderStatusChanged(account, destFolderId);
                        }
                    }
                } else {
                    resultIdMapping = messageStore.moveMessages(messageIds, destFolderId);

                    unsuppressMessages(account, messages);

                    if (unreadCountAffected) {
                        // If this move operation changes the unread count, notify the listeners
                        // that the unread count changed in both the source and destination folder.
                        for (MessagingListener l : getListeners()) {
                            l.folderStatusChanged(account, srcFolderId);
                            l.folderStatusChanged(account, destFolderId);
                        }
                    }
                }

                Map<Long, String> destinationMapping = messageStore.getMessageServerIds(resultIdMapping.values());

                Map<String, String> uidMap = new HashMap<>();
                for (Entry<Long, Long> entry : resultIdMapping.entrySet()) {
                    long sourceMessageId = entry.getKey();
                    long destinationMessageId = entry.getValue();

                    String sourceUid = messageIdToUidMapping.get(sourceMessageId);
                    String destinationUid = destinationMapping.get(destinationMessageId);
                    uidMap.put(sourceUid, destinationUid);
                }

                queueMoveOrCopy(account, localSrcFolder.getDatabaseId(), localDestFolder.getDatabaseId(),
                        operation, uidMap);
            }

            processPendingCommands(account);
        } catch (MessagingException me) {
            throw new RuntimeException("Error moving message", me);
        }
    }

    public void moveToDraftsFolder(Account account, long folderId, List<MessageReference> messages){
        putBackground("moveToDrafts", null, () -> moveToDraftsFolderInBackground(account, folderId, messages));
    }

    private void moveToDraftsFolderInBackground(Account account, long folderId, List<MessageReference> messages) {
        for (MessageReference messageReference : messages) {
            try {
                Message message = loadMessage(account, folderId, messageReference.getUid());
                Long draftMessageId = saveDraft(account, message, null, message.getSubject());

                boolean draftSavedSuccessfully = draftMessageId != null;
                if (draftSavedSuccessfully) {
                    message.destroy();
                }

                for (MessagingListener listener : getListeners()) {
                    listener.folderStatusChanged(account, folderId);
                }
            } catch (MessagingException e) {
                Timber.e(e, "Error loading message. Draft was not saved.");
            }
        }
    }

    public void archiveThreads(List<MessageReference> messages) {
        archiveOperations.archiveThreads(messages);
    }

    public void archiveMessages(List<MessageReference> messages) {
        archiveOperations.archiveMessages(messages);
    }

    public void archiveMessage(MessageReference message) {
        archiveOperations.archiveMessage(message);
    }

    public void expunge(Account account, long folderId) {
        putBackground("expunge", null, () -> {
            queueExpunge(account, folderId);
            processPendingCommands(account);
        });
    }

    public void deleteDraftSkippingTrashFolder(Account account, long messageId) {
        deleteDraft(account, messageId, true);
    }

    public void deleteDraft(Account account, long messageId) {
        deleteDraft(account, messageId, false);
    }

    private void deleteDraft(Account account, long messageId, boolean skipTrashFolder) {
        Long folderId = account.getDraftsFolderId();
        if (folderId == null) {
            Timber.w("No Drafts folder configured. Can't delete draft.");
            return;
        }

        MessageStore messageStore = messageStoreManager.getMessageStore(account);
        String messageServerId = messageStore.getMessageServerId(messageId);
        if (messageServerId != null) {
            MessageReference messageReference = new MessageReference(account.getUuid(), folderId, messageServerId);
            deleteMessages(Collections.singletonList(messageReference), skipTrashFolder);
        }
    }

    public void deleteThreads(final List<MessageReference> messages) {
        actOnMessagesGroupedByAccountAndFolder(messages, (account, messageFolder, accountMessages) -> {
            suppressMessages(account, accountMessages);
            putBackground("deleteThreads", null, () ->
                deleteThreadsSynchronous(account, messageFolder.getDatabaseId(), accountMessages, false)
            );
        });
    }

    private void deleteThreadsSynchronous(Account account, long folderId, List<LocalMessage> messages, boolean skipTrashFolder) {
        try {
            List<LocalMessage> messagesToDelete = collectMessagesInThreads(account, messages);
            deleteMessagesSynchronous(account, folderId, messagesToDelete, skipTrashFolder);
        } catch (MessagingException e) {
            Timber.e(e, "Something went wrong while deleting threads");
        }
    }

    List<LocalMessage> collectMessagesInThreads(Account account, List<LocalMessage> messages)
            throws MessagingException {

        LocalStore localStore = localStoreProvider.getInstance(account);

        List<LocalMessage> messagesInThreads = new ArrayList<>();
        for (LocalMessage localMessage : messages) {
            long rootId = localMessage.getRootId();
            long threadId = (rootId == -1) ? localMessage.getThreadId() : rootId;

            List<LocalMessage> messagesInThread = localStore.getMessagesInThread(threadId);

            messagesInThreads.addAll(messagesInThread);
        }

        return messagesInThreads;
    }

    public void deleteMessage(MessageReference message) {
        deleteMessages(Collections.singletonList(message), false);
    }

    public void deleteMessages(List<MessageReference> messages) {
        deleteMessages(messages, false);
    }

    private void deleteMessages(List<MessageReference> messages, boolean skipTrashFolder) {
        actOnMessagesGroupedByAccountAndFolder(messages, (account, messageFolder, accountMessages) -> {
            suppressMessages(account, accountMessages);
            putBackground("deleteMessages", null, () ->
                deleteMessagesSynchronous(account, messageFolder.getDatabaseId(), accountMessages, skipTrashFolder)
            );
        });
    }

    private void deleteMessagesSynchronous(Account account, long folderId, List<LocalMessage> messages, boolean skipTrashFolder) {
        try {
            List<LocalMessage> localOnlyMessages = new ArrayList<>();
            List<LocalMessage> syncedMessages = new ArrayList<>();
            List<String> syncedMessageUids = new ArrayList<>();
            for (LocalMessage message : messages) {
                notificationController.removeNewMailNotification(account, message.makeMessageReference());

                String uid = message.getUid();
                if (uid.startsWith(K9.LOCAL_UID_PREFIX)) {
                    localOnlyMessages.add(message);
                } else {
                    syncedMessages.add(message);
                    syncedMessageUids.add(uid);
                }
            }

            Backend backend = getBackend(account);

            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(folderId);
            localFolder.open();

            Map<String, String> uidMap = null;
            Long trashFolderId = account.getTrashFolderId();
            boolean doNotMoveToTrashFolder = skipTrashFolder ||
                localDeleteOperationDecider.isDeleteImmediately(account, folderId);

            LocalFolder localTrashFolder = null;
            if (doNotMoveToTrashFolder) {
                Timber.d("Not moving deleted messages to local Trash folder. Removing local copies.");

                if (!localOnlyMessages.isEmpty()) {
                    localFolder.destroyMessages(localOnlyMessages);
                }
                if (!syncedMessages.isEmpty()) {
                    localFolder.setFlags(syncedMessages, Collections.singleton(Flag.DELETED), true);
                }
            } else {
                Timber.d("Deleting messages in normal folder, moving");
                localTrashFolder = localStore.getFolder(trashFolderId);

                MessageStore messageStore = messageStoreManager.getMessageStore(account);

                List<Long> messageIds = new ArrayList<>();
                Map<Long, String> messageIdToUidMapping = new HashMap<>();
                for (LocalMessage message : messages) {
                    long messageId = message.getDatabaseId();
                    messageIds.add(messageId);
                    messageIdToUidMapping.put(messageId, message.getUid());
                }

                Map<Long, Long> moveMessageIdMapping = messageStore.moveMessages(messageIds, trashFolderId);

                Map<Long, String> destinationMapping = messageStore.getMessageServerIds(moveMessageIdMapping.values());
                uidMap = new HashMap<>();
                for (Entry<Long, Long> entry : moveMessageIdMapping.entrySet()) {
                    long sourceMessageId = entry.getKey();
                    long destinationMessageId = entry.getValue();

                    String sourceUid = messageIdToUidMapping.get(sourceMessageId);
                    String destinationUid = destinationMapping.get(destinationMessageId);
                    uidMap.put(sourceUid, destinationUid);
                }

                if (account.isMarkMessageAsReadOnDelete()) {
                    Collection<Long> destinationMessageIds = moveMessageIdMapping.values();
                    messageStore.setFlag(destinationMessageIds, Flag.SEEN, true);
                }
            }

            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folderId);
                if (localTrashFolder != null) {
                    l.folderStatusChanged(account, trashFolderId);
                }
            }

            Timber.d("Delete policy for account %s is %s", account, account.getDeletePolicy());

            Long outboxFolderId = account.getOutboxFolderId();
            if (outboxFolderId != null && folderId == outboxFolderId && supportsUpload(account)) {
                for (String destinationUid : uidMap.values()) {
                    // If the message was in the Outbox, then it has been copied to local Trash, and has
                    // to be copied to remote trash
                    PendingCommand command = PendingAppend.create(trashFolderId, destinationUid);
                    queuePendingCommand(account, command);
                }
                processPendingCommands(account);
            } else if (localFolder.isLocalOnly()) {
                // Nothing to do on the remote side
            } else if (!syncedMessageUids.isEmpty()) {
                if (account.getDeletePolicy() == DeletePolicy.ON_DELETE) {
                    if (doNotMoveToTrashFolder || !backend.getSupportsTrashFolder()) {
                        queueDelete(account, folderId, syncedMessageUids);
                    } else if (account.isMarkMessageAsReadOnDelete()) {
                        queueMoveOrCopy(account, folderId, trashFolderId,
                                MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ, uidMap);
                    } else {
                        queueMoveOrCopy(account, folderId, trashFolderId,
                                MoveOrCopyFlavor.MOVE, uidMap);
                    }
                    processPendingCommands(account);
                } else if (account.getDeletePolicy() == DeletePolicy.MARK_AS_READ) {
                    queueSetFlag(account, localFolder.getDatabaseId(), true, Flag.SEEN, syncedMessageUids);
                    processPendingCommands(account);
                } else {
                    Timber.d("Delete policy %s prevents delete from server", account.getDeletePolicy());
                }
            }

            unsuppressMessages(account, messages);
        } catch (MessagingException me) {
            throw new RuntimeException("Error deleting message from local store.", me);
        }
    }

    private static List<String> getUidsFromMessages(List<LocalMessage> messages) {
        List<String> uids = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            uids.add(messages.get(i).getUid());
        }
        return uids;
    }

    void processPendingEmptySpam(Account account) throws MessagingException {
        if (!account.hasSpamFolder()) {
            return;
        }

        long spamFolderId = account.getSpamFolderId();
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder folder = localStore.getFolder(spamFolderId);
        folder.open();
        String spamFolderServerId = folder.getServerId();

        Backend backend = getBackend(account);
        backend.deleteAllMessages(spamFolderServerId);

        // Remove all messages marked as deleted
        folder.destroyDeletedMessages();

        compact(account);
    }

    public void emptySpam(final Account account, MessagingListener listener) {
        putBackground("emptySpam", listener, new Runnable() {
            @Override
            public void run() {
                try {
                    Long spamFolderId = account.getSpamFolderId();
                    if (spamFolderId == null) {
                        Timber.w("No Spam folder configured. Can't empty spam.");
                        return;
                    }

                    LocalStore localStore = localStoreProvider.getInstance(account);
                    LocalFolder localFolder = localStore.getFolder(spamFolderId);
                    localFolder.open();

                    localFolder.destroyLocalOnlyMessages();
                    localFolder.setFlags(Collections.singleton(Flag.DELETED), true);

                    for (MessagingListener l : getListeners()) {
                        l.folderStatusChanged(account, spamFolderId);
                    }

                    PendingCommand command = PendingEmptySpam.create();
                    queuePendingCommand(account, command);
                    processPendingCommands(account);
                } catch (Exception e) {
                    Timber.e(e, "emptySpam failed");
                }
            }
        });
    }

    void processPendingEmptyTrash(Account account) throws MessagingException {
        if (!account.hasTrashFolder()) {
            return;
        }

        long trashFolderId = account.getTrashFolderId();
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder folder = localStore.getFolder(trashFolderId);
        folder.open();
        String trashFolderServerId = folder.getServerId();

        Backend backend = getBackend(account);
        backend.deleteAllMessages(trashFolderServerId);

        // Remove all messages marked as deleted
        folder.destroyDeletedMessages();

        compact(account);
    }

    public void emptyTrash(final Account account, MessagingListener listener) {
        putBackground("emptyTrash", listener, new Runnable() {
            @Override
            public void run() {
                try {
                    Long trashFolderId = account.getTrashFolderId();
                    if (trashFolderId == null) {
                        Timber.w("No Trash folder configured. Can't empty trash.");
                        return;
                    }

                    LocalStore localStore = localStoreProvider.getInstance(account);
                    LocalFolder localFolder = localStore.getFolder(trashFolderId);
                    localFolder.open();

                    boolean isTrashLocalOnly = isTrashLocalOnly(account);
                    if (isTrashLocalOnly) {
                        localFolder.clearAllMessages();
                    } else {
                        localFolder.destroyLocalOnlyMessages();
                        localFolder.setFlags(Collections.singleton(Flag.DELETED), true);
                    }

                    for (MessagingListener l : getListeners()) {
                        l.folderStatusChanged(account, trashFolderId);
                    }

                    if (!isTrashLocalOnly) {
                        PendingCommand command = PendingEmptyTrash.create();
                        queuePendingCommand(account, command);
                        processPendingCommands(account);
                    }
                } catch (Exception e) {
                    Timber.e(e, "emptyTrash failed");
                }
            }
        });
    }

    public void clearFolder(Account account, long folderId) {
        putBackground("clearFolder", null, () ->
                clearFolderSynchronous(account, folderId)
        );
    }

    @VisibleForTesting
    protected void clearFolderSynchronous(Account account, long folderId) {
        try {
            LocalFolder localFolder = localStoreProvider.getInstance(account).getFolder(folderId);
            localFolder.open();
            localFolder.clearAllMessages();
        } catch (Exception e) {
            Timber.e(e, "clearFolder failed");
        }
    }


    /**
     * Find out whether the account type only supports a local Trash folder.
     * <p>
     * <p>Note: Currently this is only the case for POP3 accounts.</p>
     *
     * @param account
     *         The account to check.
     *
     * @return {@code true} if the account only has a local Trash folder that is not synchronized
     * with a folder on the server. {@code false} otherwise.
     */
    private boolean isTrashLocalOnly(Account account) {
        Backend backend = getBackend(account);
        return !backend.getSupportsTrashFolder();
    }

    public boolean performPeriodicMailSync(Account account) {
        final CountDownLatch latch = new CountDownLatch(1);
        MutableBoolean syncError = new MutableBoolean(false);
        checkMail(account, false, false, true, new SimpleMessagingListener() {
            @Override
            public void checkMailFinished(Context context, Account account) {
                latch.countDown();
            }

            @Override
            public void synchronizeMailboxFailed(Account account, long folderId, String message) {
                syncError.setValue(true);
            }
        });

        Timber.v("performPeriodicMailSync(%s) about to await latch release", account);

        try {
            latch.await();
            Timber.v("performPeriodicMailSync(%s) got latch release", account);
        } catch (Exception e) {
            Timber.e(e, "Interrupted while awaiting latch release");
        }

        boolean success = !syncError.getValue();
        if (success) {
            long now = System.currentTimeMillis();
            Timber.v("Account %s successfully synced @ %tc", account, now);
            account.setLastSyncTime(now);
            preferences.saveAccount(account);
        }

        return success;
    }

    /**
     * Checks mail for one or multiple accounts. If account is null all accounts
     * are checked.
     */
    public void checkMail(Account account, boolean ignoreLastCheckedTime, boolean useManualWakeLock, boolean notify,
            MessagingListener listener) {

        final WakeLock wakeLock;
        if (useManualWakeLock) {
            PowerManager pm = DI.get(PowerManager.class);

            wakeLock = pm.newWakeLock("K9 MessagingController.checkMail");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(K9.MANUAL_WAKE_LOCK_TIMEOUT);
        } else {
            wakeLock = null;
        }

        for (MessagingListener l : getListeners(listener)) {
            l.checkMailStarted(context, account);
        }
        putBackground("checkMail", listener, new Runnable() {
            @Override
            public void run() {

                try {
                    Timber.i("Starting mail check");

                    Collection<Account> accounts;
                    if (account != null) {
                        accounts = new ArrayList<>(1);
                        accounts.add(account);
                    } else {
                        accounts = preferences.getAccounts();
                    }

                    for (final Account account : accounts) {
                        checkMailForAccount(account, ignoreLastCheckedTime, notify, listener);
                    }

                } catch (Exception e) {
                    Timber.e(e, "Unable to synchronize mail");
                }
                putBackground("finalize sync", null, new Runnable() {
                            @Override
                            public void run() {

                                Timber.i("Finished mail sync");

                                if (wakeLock != null) {
                                    wakeLock.release();
                                }
                                for (MessagingListener l : getListeners(listener)) {
                                    l.checkMailFinished(context, account);
                                }

                            }
                        }
                );
            }
        });
    }


    private void checkMailForAccount(Account account, boolean ignoreLastCheckedTime, boolean notify,
            MessagingListener listener) {
        Timber.i("Synchronizing account %s", account);

        NotificationState notificationState = new NotificationState();

        sendPendingMessages(account, listener);

        refreshFolderListIfStale(account);

        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            for (final LocalFolder folder : localStore.getPersonalNamespaces(false)) {
                folder.open();

                if (!folder.isVisible()) {
                    // Never sync a folder that isn't displayed
                    continue;
                }

                if (!folder.isSyncEnabled()) {
                    // Do not sync folders that are not enabled for sync.
                    continue;
                }
                synchronizeFolder(account, folder, ignoreLastCheckedTime, notify, listener, notificationState);
            }
        } catch (MessagingException e) {
            Timber.e(e, "Unable to synchronize account %s", account);
        } finally {
            putBackground("clear notification flag for " + account, null, new Runnable() {
                        @Override
                        public void run() {
                            Timber.v("Clearing notification flag for %s", account);

                            clearFetchingMailNotification(account);
                        }
                    }
            );
        }


    }

    private void synchronizeFolder(Account account, LocalFolder folder, boolean ignoreLastCheckedTime,
            boolean notify, MessagingListener listener, NotificationState notificationState) {
        putBackground("sync" + folder.getServerId(), null, () -> {
            synchronizeFolderInBackground(account, folder, ignoreLastCheckedTime, notify, listener, notificationState);
        });
    }

    private void synchronizeFolderInBackground(Account account, LocalFolder folder, boolean ignoreLastCheckedTime,
            boolean notify, MessagingListener listener, NotificationState notificationState) {
        Timber.v("Folder %s was last synced @ %tc", folder.getServerId(), folder.getLastChecked());

        if (!ignoreLastCheckedTime) {
            long lastCheckedTime = folder.getLastChecked();
            long now = System.currentTimeMillis();

            if (lastCheckedTime > now) {
                // The time this folder was last checked lies in the future. We better ignore this and sync now.
            } else {
                long syncInterval = account.getAutomaticCheckIntervalMinutes() * 60L * 1000L;
                long nextSyncTime = lastCheckedTime + syncInterval;
                if (nextSyncTime > now) {
                    Timber.v("Not syncing folder %s, previously synced @ %tc which would be too recent for the " +
                            "account sync interval", folder.getServerId(), lastCheckedTime);
                    return;
                }
            }
        }

        try {
            showFetchingMailNotificationIfNecessary(account, folder);
            try {
                synchronizeMailboxSynchronous(account, folder.getDatabaseId(), notify, listener, notificationState);
            } finally {
                showEmptyFetchingMailNotificationIfNecessary(account);
            }
        } catch (Exception e) {
            Timber.e(e, "Exception while processing folder %s:%s", account, folder.getServerId());
        }
    }

    private void showFetchingMailNotificationIfNecessary(Account account, LocalFolder folder) {
        if (account.isNotifySync()) {
            notificationController.showFetchingMailNotification(account, folder);
        }
    }

    private void showEmptyFetchingMailNotificationIfNecessary(Account account) {
        if (account.isNotifySync()) {
            notificationController.showEmptyFetchingMailNotification(account);
        }
    }

    private void clearFetchingMailNotification(Account account) {
        notificationController.clearFetchingMailNotification(account);
    }

    public void compact(Account account) {
        putBackground("compact:" + account, null, () -> {
            try {
                MessageStore messageStore = messageStoreManager.getMessageStore(account);
                messageStore.compact();
            } catch (Exception e) {
                Timber.e(e, "Failed to compact account %s", account);
            }
        });
    }

    public void deleteAccount(Account account) {
        notificationController.clearNewMailNotifications(account, false);
        memorizingMessagingListener.removeAccount(account);
    }

    /**
     * Save a draft message.
     */
    public Long saveDraft(Account account, Message message, Long existingDraftId, String plaintextSubject) {
        return draftOperations.saveDraft(account, message, existingDraftId, plaintextSubject);
    }

    public Long getId(Message message) {
        if (message instanceof LocalMessage) {
            return ((LocalMessage) message).getDatabaseId();
        } else {
            Timber.w("MessagingController.getId() called without a LocalMessage");
            return null;
        }
    }

    private static AtomicInteger sequencing = new AtomicInteger(0);

    private static class Command implements Comparable<Command> {
        public Runnable runnable;
        public MessagingListener listener;
        public String description;
        boolean isForegroundPriority;

        int sequence = sequencing.getAndIncrement();

        @Override
        public int compareTo(@NonNull Command other) {
            if (other.isForegroundPriority && !isForegroundPriority) {
                return 1;
            } else if (!other.isForegroundPriority && isForegroundPriority) {
                return -1;
            } else {
                return (sequence - other.sequence);
            }
        }
    }

    public void clearNotifications(LocalSearch search) {
        put("clearNotifications", null, () -> {
            notificationOperations.clearNotifications(search);
        });
    }

    public void cancelNotificationsForAccount(Account account) {
        notificationController.clearNewMailNotifications(account, true);
    }

    public void cancelNotificationForMessage(Account account, MessageReference messageReference) {
        notificationController.removeNewMailNotification(account, messageReference);
    }

    @Deprecated
    public void clearCertificateErrorNotifications(Account account, boolean incoming) {
        notificationController.clearCertificateErrorNotifications(account, incoming);
    }

    public void notifyUserIfCertificateProblem(Account account, Exception exception, boolean incoming) {
        if (exception instanceof CertificateValidationException) {
            notificationController.showCertificateErrorNotification(account, incoming);
        }
    }

    private boolean isAuthenticationProblem(Account account, boolean incoming) {
        ServerSettings serverSettings = incoming ?
                account.getIncomingServerSettings() : account.getOutgoingServerSettings();

        return serverSettings.isMissingCredentials() ||
                serverSettings.authenticationType == AuthType.XOAUTH2 && account.getOAuthState() == null;
    }

    void actOnMessagesGroupedByAccountAndFolder(List<MessageReference> messages, MessageActor actor) {
        Map<String, Map<Long, List<MessageReference>>> accountMap = groupMessagesByAccountAndFolder(messages);

        for (Map.Entry<String, Map<Long, List<MessageReference>>> entry : accountMap.entrySet()) {
            String accountUuid = entry.getKey();
            Account account = preferences.getAccount(accountUuid);

            Map<Long, List<MessageReference>> folderMap = entry.getValue();
            for (Map.Entry<Long, List<MessageReference>> folderEntry : folderMap.entrySet()) {
                long folderId = folderEntry.getKey();
                List<MessageReference> messageList = folderEntry.getValue();
                actOnMessageGroup(account, folderId, messageList, actor);
            }
        }
    }

    @NonNull
    private Map<String, Map<Long, List<MessageReference>>> groupMessagesByAccountAndFolder(
            List<MessageReference> messages) {
        Map<String, Map<Long, List<MessageReference>>> accountMap = new HashMap<>();

        for (MessageReference message : messages) {
            if (message == null) {
                continue;
            }
            String accountUuid = message.getAccountUuid();
            long folderId = message.getFolderId();

            Map<Long, List<MessageReference>> folderMap = accountMap.get(accountUuid);
            if (folderMap == null) {
                folderMap = new HashMap<>();
                accountMap.put(accountUuid, folderMap);
            }
            List<MessageReference> messageList = folderMap.get(folderId);
            if (messageList == null) {
                messageList = new LinkedList<>();
                folderMap.put(folderId, messageList);
            }

            messageList.add(message);
        }
        return accountMap;
    }

    private void actOnMessageGroup(
            Account account, long folderId, List<MessageReference> messageReferences, MessageActor actor) {
        try {
            LocalFolder messageFolder = localStoreProvider.getInstance(account).getFolder(folderId);
            List<LocalMessage> localMessages = messageFolder.getMessagesByReference(messageReferences);
            actor.act(account, messageFolder, localMessages);
        } catch (MessagingException e) {
            Timber.e(e, "Error loading account?!");
        }

    }

    interface MessageActor {
        void act(Account account, LocalFolder messageFolder, List<LocalMessage> messages);
    }

    class ControllerSyncListener implements SyncListener {
        private final Account account;
        private final MessagingListener listener;
        private final LocalStore localStore;
        private final boolean suppressNotifications;
        private final NotificationState notificationState;
        boolean syncFailed = false;


        ControllerSyncListener(Account account, MessagingListener listener, boolean suppressNotifications,
                NotificationState notificationState) {
            this.account = account;
            this.listener = listener;
            this.suppressNotifications = suppressNotifications;
            this.notificationState = notificationState;
            this.localStore = getLocalStoreOrThrow(account);
        }

        @Override
        public void syncStarted(@NotNull String folderServerId) {
            long folderId = getFolderId(account, folderServerId);
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxStarted(account, folderId);
            }
        }

        @Override
        public void syncAuthenticationSuccess() {
            notificationController.clearAuthenticationErrorNotification(account, true);
        }

        @Override
        public void syncHeadersStarted(@NotNull String folderServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxHeadersStarted(account, folderServerId);
            }
        }

        @Override
        public void syncHeadersProgress(@NotNull String folderServerId, int completed, int total) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxHeadersProgress(account, folderServerId, completed, total);
            }
        }

        @Override
        public void syncHeadersFinished(@NotNull String folderServerId, int totalMessagesInMailbox,
                int numNewMessages) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxHeadersFinished(account, folderServerId, totalMessagesInMailbox,
                        numNewMessages);
            }
        }

        @Override
        public void syncProgress(@NotNull String folderServerId, int completed, int total) {
            long folderId = getFolderId(account, folderServerId);
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxProgress(account, folderId, completed, total);
            }
        }

        @Override
        public void syncNewMessage(@NotNull String folderServerId, @NotNull String messageServerId,
                boolean isOldMessage) {

            // Send a notification of this message
            LocalMessage message = loadMessage(folderServerId, messageServerId);
            LocalFolder localFolder = message.getFolder();
            if (!suppressNotifications &&
                    notificationStrategy.shouldNotifyForMessage(account, localFolder, message, isOldMessage)) {
                // Notify with the localMessage so that we don't have to recalculate the content preview.
                boolean silent = notificationState.wasNotified();
                notificationController.addNewMailNotification(account, message, silent);
                notificationState.setWasNotified(true);
            }

            if (!message.isSet(Flag.SEEN)) {
                for (MessagingListener messagingListener : getListeners(listener)) {
                    messagingListener.synchronizeMailboxNewMessage(account, folderServerId, message);
                }
            }
        }

        @Override
        public void syncRemovedMessage(@NotNull String folderServerId, @NotNull String messageServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxRemovedMessage(account, folderServerId, messageServerId);
            }

            String accountUuid = account.getUuid();
            long folderId = getFolderId(account, folderServerId);
            MessageReference messageReference = new MessageReference(accountUuid, folderId, messageServerId);
            notificationController.removeNewMailNotification(account, messageReference);
        }

        @Override
        public void syncFlagChanged(@NotNull String folderServerId, @NotNull String messageServerId) {
            boolean shouldBeNotifiedOf = false;
            LocalMessage message = loadMessage(folderServerId, messageServerId);
            if (message.isSet(Flag.DELETED) || isMessageSuppressed(message)) {
                syncRemovedMessage(folderServerId, message.getUid());
            } else {
                LocalFolder localFolder = message.getFolder();
                if (notificationStrategy.shouldNotifyForMessage(account, localFolder, message, false)) {
                    shouldBeNotifiedOf = true;
                }
            }

            // we're only interested in messages that need removing
            if (!shouldBeNotifiedOf) {
                MessageReference messageReference = message.makeMessageReference();
                notificationController.removeNewMailNotification(account, messageReference);
            }
        }

        @Override
        public void syncFinished(@NotNull String folderServerId) {
            long folderId = getFolderId(account, folderServerId);
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxFinished(account, folderId);
            }
        }

        @Override
        public void syncFailed(@NotNull String folderServerId, @NotNull String message, Exception exception) {
            syncFailed = true;

            if (exception instanceof AuthenticationFailedException) {
                handleAuthenticationFailure(account, true);
            } else {
                notifyUserIfCertificateProblem(account, exception, true);
            }

            long folderId = getFolderId(account, folderServerId);
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxFailed(account, folderId, message);
            }
        }

        @Override
        public void folderStatusChanged(@NotNull String folderServerId) {
            long folderId = getFolderId(account, folderServerId);
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.folderStatusChanged(account, folderId);
            }
        }

        private LocalMessage loadMessage(String folderServerId, String messageServerId) {
            try {
                LocalFolder localFolder = localStore.getFolder(folderServerId);
                localFolder.open();
                return localFolder.getMessage(messageServerId);
            } catch (MessagingException e) {
                throw new RuntimeException("Couldn't load message (" + folderServerId + ":" + messageServerId + ")", e);
            }
        }
    }

    enum MoveOrCopyFlavor {
        MOVE, COPY, MOVE_AND_MARK_AS_READ
    }
}
