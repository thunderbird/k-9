package com.fsck.k9.activity.compose

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.view.Menu
import androidx.core.content.ContextCompat
import androidx.loader.app.LoaderManager
import app.k9mail.legacy.account.Account
import com.fsck.k9.K9
import com.fsck.k9.activity.compose.ComposeCryptoStatus.AttachErrorState
import com.fsck.k9.activity.compose.ComposeCryptoStatus.SendErrorState
import com.fsck.k9.autocrypt.AutocryptDraftStateHeader
import com.fsck.k9.autocrypt.AutocryptDraftStateHeaderParser
import com.fsck.k9.contact.ContactIntentHelper
import com.fsck.k9.helper.MailTo
import com.fsck.k9.helper.ReplyToParser
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Flag
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.message.AutocryptStatusInteractor
import com.fsck.k9.message.AutocryptStatusInteractor.RecipientAutocryptStatus
import com.fsck.k9.message.ComposePgpEnableByDefaultDecider
import com.fsck.k9.message.ComposePgpInlineDecider
import com.fsck.k9.message.MessageBuilder
import com.fsck.k9.message.PgpMessageBuilder
import com.fsck.k9.ui.R
import com.fsck.k9.view.RecipientSelectView.Recipient
import org.openintents.openpgp.OpenPgpApiManager
import org.openintents.openpgp.OpenPgpApiManager.OpenPgpApiManagerCallback
import org.openintents.openpgp.OpenPgpApiManager.OpenPgpProviderError
import org.openintents.openpgp.OpenPgpApiManager.OpenPgpProviderState
import timber.log.Timber

private const val STATE_KEY_CC_SHOWN = "state:ccShown"
private const val STATE_KEY_BCC_SHOWN = "state:bccShown"
private const val STATE_KEY_LAST_FOCUSED_TYPE = "state:lastFocusedType"
private const val STATE_KEY_CURRENT_CRYPTO_MODE = "state:currentCryptoMode"
private const val STATE_KEY_CRYPTO_ENABLE_PGP_INLINE = "state:cryptoEnablePgpInline"

private const val CONTACT_PICKER_TO = 1
private const val CONTACT_PICKER_CC = 2
private const val CONTACT_PICKER_BCC = 3
private const val OPENPGP_USER_INTERACTION = 4
private const val REQUEST_CODE_AUTOCRYPT = 5

private const val PGP_DIALOG_DISPLAY_THRESHOLD = 2

class RecipientPresenter(
    private val context: Context,
    loaderManager: LoaderManager,
    private val openPgpApiManager: OpenPgpApiManager,
    private val recipientMvpView: RecipientMvpView,
    account: Account,
    private val composePgpInlineDecider: ComposePgpInlineDecider,
    private val composePgpEnableByDefaultDecider: ComposePgpEnableByDefaultDecider,
    private val autocryptStatusInteractor: AutocryptStatusInteractor,
    private val replyToParser: ReplyToParser,
    private val draftStateHeaderParser: AutocryptDraftStateHeaderParser,
) {
    private var isToAddressAdded: Boolean = false
    private lateinit var account: Account
    private var alwaysBccAddresses: Array<Address>? = null
    private var hasContactPicker: Boolean? = null
    private var isReplyToEncryptedMessage = false

    private var lastFocusedType = RecipientType.TO
    private var currentCryptoMode = CryptoMode.NO_CHOICE

    var isForceTextMessageFormat = false
        private set

    var currentCachedCryptoStatus: ComposeCryptoStatus? = null
        private set

    val toAddresses: List<Address>
        get() = recipientMvpView.toAddresses

    val ccAddresses: List<Address>
        get() = recipientMvpView.ccAddresses

    val bccAddresses: List<Address>
        get() = recipientMvpView.bccAddresses

    private val allRecipients: List<Recipient>
        get() = with(recipientMvpView) { toRecipients + ccRecipients + bccRecipients }

    private val openPgpCallback = object : OpenPgpApiManagerCallback {
        override fun onOpenPgpProviderStatusChanged() {
            if (openPgpApiManager.openPgpProviderState == OpenPgpProviderState.UI_REQUIRED) {
                recipientMvpView.showErrorOpenPgpUserInteractionRequired()
            }
            asyncUpdateCryptoStatus()
        }

        override fun onOpenPgpProviderError(error: OpenPgpProviderError) {
            when (error) {
                OpenPgpProviderError.ConnectionLost -> openPgpApiManager.refreshConnection()
                OpenPgpProviderError.VersionIncompatible -> recipientMvpView.showErrorOpenPgpIncompatible()
                OpenPgpProviderError.ConnectionFailed -> recipientMvpView.showErrorOpenPgpConnection()
                else -> recipientMvpView.showErrorOpenPgpConnection()
            }
        }
    }

    init {
        recipientMvpView.setPresenter(this)
        recipientMvpView.setLoaderManager(loaderManager)

        onSwitchAccount(account)
    }

    fun checkRecipientsOkForSending(): Boolean {
        recipientMvpView.recipientToTryPerformCompletion()
        recipientMvpView.recipientCcTryPerformCompletion()
        recipientMvpView.recipientBccTryPerformCompletion()

        if (recipientMvpView.recipientToHasUncompletedText()) {
            recipientMvpView.showToUncompletedError()
            return true
        }

        if (recipientMvpView.recipientCcHasUncompletedText()) {
            recipientMvpView.showCcUncompletedError()
            return true
        }

        if (recipientMvpView.recipientBccHasUncompletedText()) {
            recipientMvpView.showBccUncompletedError()
            return true
        }

        if (toAddresses.isEmpty() && ccAddresses.isEmpty() && bccAddresses.isEmpty()) {
            recipientMvpView.showNoRecipientsError()
            return true
        }

        return false
    }

    fun initFromReplyToMessage(message: Message?, isReplyAll: Boolean) {
        val replyToAddresses = if (isReplyAll) {
            replyToParser.getRecipientsToReplyAllTo(message, account)
        } else {
            replyToParser.getRecipientsToReplyTo(message, account)
        }

        addToAddresses(*replyToAddresses.to)
        addCcAddresses(*replyToAddresses.cc)

        val shouldSendAsPgpInline = composePgpInlineDecider.shouldReplyInline(message)
        if (shouldSendAsPgpInline) {
            isForceTextMessageFormat = true
        }

        isReplyToEncryptedMessage = composePgpEnableByDefaultDecider.shouldEncryptByDefault(message)
    }

    fun initFromTrustIdAction(trustId: String?) {
        addToAddresses(*Address.parse(trustId))
        currentCryptoMode = CryptoMode.CHOICE_ENABLED
    }

    fun initFromMailto(mailTo: MailTo) {
        addToAddresses(*mailTo.to)
        addCcAddresses(*mailTo.cc)
        addBccAddresses(*mailTo.bcc)
    }

    fun initFromSendOrViewIntent(intent: Intent) {
        val toAddresses = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toAddressArray()
        val ccAddresses = intent.getStringArrayExtra(Intent.EXTRA_CC)?.toAddressArray()
        val bccAddresses = intent.getStringArrayExtra(Intent.EXTRA_BCC)?.toAddressArray()

        if (toAddresses != null) {
            addToAddresses(*toAddresses)
        }

        if (ccAddresses != null) {
            addCcAddresses(*ccAddresses)
        }

        if (bccAddresses != null) {
            addBccAddresses(*bccAddresses)
        }
    }

    fun onRestoreInstanceState(savedInstanceState: Bundle) {
        recipientMvpView.setCcVisibility(savedInstanceState.getBoolean(STATE_KEY_CC_SHOWN))
        recipientMvpView.setBccVisibility(savedInstanceState.getBoolean(STATE_KEY_BCC_SHOWN))
        lastFocusedType = RecipientType.valueOf(savedInstanceState.getString(STATE_KEY_LAST_FOCUSED_TYPE)!!)
        currentCryptoMode = CryptoMode.valueOf(savedInstanceState.getString(STATE_KEY_CURRENT_CRYPTO_MODE)!!)
        isForceTextMessageFormat = savedInstanceState.getBoolean(STATE_KEY_CRYPTO_ENABLE_PGP_INLINE)

        updateRecipientExpanderVisibility()
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_KEY_CC_SHOWN, recipientMvpView.isCcVisible)
        outState.putBoolean(STATE_KEY_BCC_SHOWN, recipientMvpView.isBccVisible)
        outState.putString(STATE_KEY_LAST_FOCUSED_TYPE, lastFocusedType.toString())
        outState.putString(STATE_KEY_CURRENT_CRYPTO_MODE, currentCryptoMode.toString())
        outState.putBoolean(STATE_KEY_CRYPTO_ENABLE_PGP_INLINE, isForceTextMessageFormat)
    }

    fun initFromDraftMessage(message: Message) {
        initRecipientsFromDraftMessage(message)

        val draftStateHeader = message.getHeader(AutocryptDraftStateHeader.AUTOCRYPT_DRAFT_STATE_HEADER)
        if (draftStateHeader.size == 1) {
            initEncryptionStateFromDraftStateHeader(draftStateHeader.first())
        } else {
            initPgpInlineFromDraftMessage(message)
        }
    }

    private fun initEncryptionStateFromDraftStateHeader(headerValue: String) {
        val autocryptDraftStateHeader = draftStateHeaderParser.parseAutocryptDraftStateHeader(headerValue)
        if (autocryptDraftStateHeader != null) {
            initEncryptionStateFromDraftStateHeader(autocryptDraftStateHeader)
        }
    }

    private fun initRecipientsFromDraftMessage(message: Message) {
        addToAddresses(*message.getRecipients(RecipientType.TO))
        addCcAddresses(*message.getRecipients(RecipientType.CC))
        addBccAddresses(*message.getRecipients(RecipientType.BCC))
    }

    private fun initEncryptionStateFromDraftStateHeader(draftState: AutocryptDraftStateHeader) {
        isForceTextMessageFormat = draftState.isPgpInline
        isReplyToEncryptedMessage = draftState.isReply

        if (!draftState.isByChoice) {
            // TODO if it's not by choice, we're going with our defaults. should we do something here if those differ?
            return
        }

        currentCryptoMode = when {
            draftState.isSignOnly -> CryptoMode.SIGN_ONLY
            draftState.isEncrypt -> CryptoMode.CHOICE_ENABLED
            else -> CryptoMode.CHOICE_DISABLED
        }
    }

    private fun initPgpInlineFromDraftMessage(message: Message) {
        isForceTextMessageFormat = message.isSet(Flag.X_DRAFT_OPENPGP_INLINE)
    }

    private fun addToAddresses(vararg toAddresses: Address) {
        addRecipientsFromAddresses(RecipientType.TO, *toAddresses)
        isToAddressAdded = true
    }

    fun isToAddressAdded() = isToAddressAdded

    private fun addCcAddresses(vararg ccAddresses: Address) {
        if (ccAddresses.isNotEmpty()) {
            addRecipientsFromAddresses(RecipientType.CC, *ccAddresses)
            recipientMvpView.setCcVisibility(true)
            updateRecipientExpanderVisibility()
        }
    }

    private fun addBccAddresses(vararg bccRecipients: Address) {
        if (bccRecipients.isNotEmpty()) {
            addRecipientsFromAddresses(RecipientType.BCC, *bccRecipients)
            recipientMvpView.setBccVisibility(true)
            updateRecipientExpanderVisibility()
        }
    }

    private fun addAlwaysBcc() {
        val alwaysBccAddresses = Address.parse(account.alwaysBcc)
        this.alwaysBccAddresses = alwaysBccAddresses
        if (alwaysBccAddresses.isEmpty()) return

        object : RecipientLoader(context, account.openPgpProvider, *alwaysBccAddresses) {
            override fun deliverResult(result: List<Recipient>?) {
                val recipientArray = result!!.toTypedArray()
                recipientMvpView.silentlyAddBccAddresses(*recipientArray)

                stopLoading()
                abandon()
            }
        }.startLoading()
    }

    private fun removeAlwaysBcc() {
        alwaysBccAddresses?.let { alwaysBccAddresses ->
            recipientMvpView.silentlyRemoveBccAddresses(alwaysBccAddresses)
        }
    }

    fun onPrepareOptionsMenu(menu: Menu) {
        val currentCryptoStatus = currentCachedCryptoStatus

        if (currentCryptoStatus != null && currentCryptoStatus.isProviderStateOk()) {
            val isEncrypting = currentCryptoStatus.isEncryptionEnabled
            menu.findItem(R.id.openpgp_encrypt_enable).isVisible = !isEncrypting
            menu.findItem(R.id.openpgp_encrypt_disable).isVisible = isEncrypting

            val showSignOnly = !account.isOpenPgpHideSignOnly
            val isSignOnly = currentCryptoStatus.isSignOnly
            menu.findItem(R.id.openpgp_sign_only).isVisible = showSignOnly && !isSignOnly
            menu.findItem(R.id.openpgp_sign_only_disable).isVisible = showSignOnly && isSignOnly

            val pgpInlineModeEnabled = currentCryptoStatus.isPgpInlineModeEnabled
            val showPgpInlineEnable = (isEncrypting || isSignOnly) && !pgpInlineModeEnabled
            menu.findItem(R.id.openpgp_inline_enable).isVisible = showPgpInlineEnable
            menu.findItem(R.id.openpgp_inline_disable).isVisible = pgpInlineModeEnabled
        } else {
            menu.findItem(R.id.openpgp_inline_enable).isVisible = false
            menu.findItem(R.id.openpgp_inline_disable).isVisible = false
            menu.findItem(R.id.openpgp_encrypt_enable).isVisible = false
            menu.findItem(R.id.openpgp_encrypt_disable).isVisible = false
            menu.findItem(R.id.openpgp_sign_only).isVisible = false
            menu.findItem(R.id.openpgp_sign_only_disable).isVisible = false
        }

        menu.findItem(R.id.add_from_contacts).isVisible = hasContactPermission() && hasContactPicker()
    }

    fun onSwitchAccount(account: Account) {
        this.account = account

        if (account.isAlwaysShowCcBcc) {
            recipientMvpView.setCcVisibility(true)
            recipientMvpView.setBccVisibility(true)
            updateRecipientExpanderVisibility()
        }

        removeAlwaysBcc()
        addAlwaysBcc()

        val openPgpProvider = account.openPgpProvider
        recipientMvpView.setCryptoProvider(openPgpProvider)
        openPgpApiManager.setOpenPgpProvider(openPgpProvider, openPgpCallback)
    }

    fun onSwitchIdentity() {
        // TODO decide what actually to do on identity switch?
        asyncUpdateCryptoStatus()
    }

    fun onClickToLabel() {
        recipientMvpView.requestFocusOnToField()
    }

    fun onClickCcLabel() {
        recipientMvpView.requestFocusOnCcField()
    }

    fun onClickBccLabel() {
        recipientMvpView.requestFocusOnBccField()
    }

    fun onClickRecipientExpander() {
        recipientMvpView.setCcVisibility(true)
        recipientMvpView.setBccVisibility(true)
        updateRecipientExpanderVisibility()
    }

    private fun hideEmptyExtendedRecipientFields() {
        if (recipientMvpView.ccAddresses.isEmpty() && recipientMvpView.isCcTextEmpty) {
            recipientMvpView.setCcVisibility(false)
            if (lastFocusedType == RecipientType.CC) {
                lastFocusedType = RecipientType.TO
            }
        }

        if (recipientMvpView.bccAddresses.isEmpty() && recipientMvpView.isBccTextEmpty) {
            recipientMvpView.setBccVisibility(false)
            if (lastFocusedType == RecipientType.BCC) {
                lastFocusedType = RecipientType.TO
            }
        }

        updateRecipientExpanderVisibility()
    }

    private fun updateRecipientExpanderVisibility() {
        val notBothAreVisible = !(recipientMvpView.isCcVisible && recipientMvpView.isBccVisible)
        recipientMvpView.setRecipientExpanderVisibility(notBothAreVisible)
    }

    fun asyncUpdateCryptoStatus() {
        currentCachedCryptoStatus = null

        val openPgpProviderState = openPgpApiManager.openPgpProviderState
        var accountCryptoKey: Long? = account.openPgpKey
        if (accountCryptoKey == Account.NO_OPENPGP_KEY) {
            accountCryptoKey = null
        }

        val composeCryptoStatus = ComposeCryptoStatus(
            openPgpProviderState = openPgpProviderState,
            openPgpKeyId = accountCryptoKey,
            recipientAddresses = allRecipients,
            isPgpInlineModeEnabled = isForceTextMessageFormat,
            isSenderPreferEncryptMutual = account.autocryptPreferEncryptMutual,
            isReplyToEncrypted = isReplyToEncryptedMessage,
            isEncryptAllDrafts = account.isOpenPgpEncryptAllDrafts,
            isEncryptSubject = account.isOpenPgpEncryptSubject,
            cryptoMode = currentCryptoMode,
        )

        if (openPgpProviderState != OpenPgpProviderState.OK) {
            currentCachedCryptoStatus = composeCryptoStatus
            redrawCachedCryptoStatusIcon()
            return
        }

        val recipientAddresses = composeCryptoStatus.recipientAddressesAsArray
        object : AsyncTask<Void?, Void?, RecipientAutocryptStatus?>() {
            override fun doInBackground(vararg params: Void?): RecipientAutocryptStatus? {
                val openPgpApi = openPgpApiManager.openPgpApi ?: return null
                return autocryptStatusInteractor.retrieveCryptoProviderRecipientStatus(openPgpApi, recipientAddresses)
            }

            override fun onPostExecute(recipientAutocryptStatus: RecipientAutocryptStatus?) {
                currentCachedCryptoStatus = if (recipientAutocryptStatus != null) {
                    composeCryptoStatus.withRecipientAutocryptStatus(recipientAutocryptStatus)
                } else {
                    composeCryptoStatus
                }

                redrawCachedCryptoStatusIcon()
            }
        }.execute()
    }

    private fun redrawCachedCryptoStatusIcon() {
        val cryptoStatus = checkNotNull(currentCachedCryptoStatus) { "must have cached crypto status to redraw it!" }

        recipientMvpView.setRecipientTokensShowCryptoEnabled(cryptoStatus.isEncryptionEnabled)
        recipientMvpView.showCryptoStatus(cryptoStatus.displayType)
        recipientMvpView.showCryptoSpecialMode(cryptoStatus.specialModeDisplayType)
    }

    fun onToTokenAdded() {
        asyncUpdateCryptoStatus()
    }

    fun onToTokenRemoved() {
        asyncUpdateCryptoStatus()
    }

    fun onToTokenChanged() {
        asyncUpdateCryptoStatus()
    }

    fun onCcTokenAdded() {
        asyncUpdateCryptoStatus()
    }

    fun onCcTokenRemoved() {
        asyncUpdateCryptoStatus()
    }

    fun onCcTokenChanged() {
        asyncUpdateCryptoStatus()
    }

    fun onBccTokenAdded() {
        asyncUpdateCryptoStatus()
    }

    fun onBccTokenRemoved() {
        asyncUpdateCryptoStatus()
    }

    fun onBccTokenChanged() {
        asyncUpdateCryptoStatus()
    }

    fun onCryptoModeChanged(cryptoMode: CryptoMode) {
        currentCryptoMode = cryptoMode
        asyncUpdateCryptoStatus()
    }

    fun onCryptoPgpInlineChanged(enablePgpInline: Boolean) {
        isForceTextMessageFormat = enablePgpInline
        asyncUpdateCryptoStatus()
    }

    private fun addRecipientsFromAddresses(recipientType: RecipientType, vararg addresses: Address) {
        object : RecipientLoader(context, account.openPgpProvider, *addresses) {
            override fun deliverResult(result: List<Recipient>?) {
                val recipientArray = result!!.toTypedArray()
                recipientMvpView.silentlyAddRecipients(recipientType, *recipientArray)

                stopLoading()
                abandon()
            }
        }.startLoading()
    }

    private fun addRecipientFromContactUri(recipientType: RecipientType, uri: Uri?) {
        object : RecipientLoader(context, account.openPgpProvider, uri, false) {
            override fun deliverResult(result: List<Recipient>?) {
                // TODO handle multiple available mail addresses for a contact?
                if (result!!.isEmpty()) {
                    recipientMvpView.showErrorContactNoAddress()
                    return
                }

                val recipient = result[0]
                recipientMvpView.addRecipients(recipientType, recipient)

                stopLoading()
                abandon()
            }
        }.startLoading()
    }

    fun onToFocused() {
        lastFocusedType = RecipientType.TO
    }

    fun onCcFocused() {
        lastFocusedType = RecipientType.CC
    }

    fun onBccFocused() {
        lastFocusedType = RecipientType.BCC
    }

    fun onMenuAddFromContacts() {
        val requestCode = lastFocusedType.toRequestCode()
        recipientMvpView.showContactPicker(requestCode)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            CONTACT_PICKER_TO, CONTACT_PICKER_CC, CONTACT_PICKER_BCC -> {
                if (resultCode != Activity.RESULT_OK || data == null) return

                val recipientType = requestCode.toRecipientType()
                addRecipientFromContactUri(recipientType, data.data)
            }
            OPENPGP_USER_INTERACTION -> {
                openPgpApiManager.onUserInteractionResult()
            }
            REQUEST_CODE_AUTOCRYPT -> {
                asyncUpdateCryptoStatus()
            }
        }
    }

    fun onNonRecipientFieldFocused() {
        if (!account.isAlwaysShowCcBcc) {
            hideEmptyExtendedRecipientFields()
        }
    }

    fun onClickCryptoStatus() {
        when (openPgpApiManager.openPgpProviderState) {
            OpenPgpProviderState.UNCONFIGURED -> {
                Timber.e("click on crypto status while unconfigured - this should not really happen?!")
            }
            OpenPgpProviderState.OK -> {
                toggleEncryptionState(false)
            }
            OpenPgpProviderState.UI_REQUIRED -> {
                // TODO show openpgp settings
                val pendingIntent = openPgpApiManager.userInteractionPendingIntent
                recipientMvpView.launchUserInteractionPendingIntent(pendingIntent, OPENPGP_USER_INTERACTION)
            }
            OpenPgpProviderState.UNINITIALIZED, OpenPgpProviderState.ERROR -> {
                openPgpApiManager.refreshConnection()
            }
        }
    }

    private fun toggleEncryptionState(showGotIt: Boolean) {
        val currentCryptoStatus = currentCachedCryptoStatus
        if (currentCryptoStatus == null) {
            Timber.e("click on crypto status while crypto status not available - should not really happen?!")
            return
        }

        if (currentCryptoStatus.isEncryptionEnabled && !currentCryptoStatus.allRecipientsCanEncrypt()) {
            recipientMvpView.showOpenPgpEnabledErrorDialog(false)
            return
        }

        if (currentCryptoMode == CryptoMode.SIGN_ONLY) {
            recipientMvpView.showErrorIsSignOnly()
            return
        }

        val isEncryptOnNoChoice = currentCryptoStatus.canEncryptAndIsMutualDefault() ||
            currentCryptoStatus.isReplyToEncrypted

        if (currentCryptoMode == CryptoMode.NO_CHOICE) {
            if (currentCryptoStatus.hasAutocryptPendingIntent()) {
                recipientMvpView.launchUserInteractionPendingIntent(
                    currentCryptoStatus.autocryptPendingIntent,
                    REQUEST_CODE_AUTOCRYPT,
                )
            } else if (isEncryptOnNoChoice) {
                // TODO warning dialog if we override, especially from reply!
                onCryptoModeChanged(CryptoMode.CHOICE_DISABLED)
            } else {
                onCryptoModeChanged(CryptoMode.CHOICE_ENABLED)
                if (showGotIt) {
                    recipientMvpView.showOpenPgpEncryptExplanationDialog()
                }
            }
        } else if (currentCryptoMode == CryptoMode.CHOICE_DISABLED && !isEncryptOnNoChoice) {
            onCryptoModeChanged(CryptoMode.CHOICE_ENABLED)
        } else {
            onCryptoModeChanged(CryptoMode.NO_CHOICE)
        }
    }

    /**
     * Does the device actually have a Contacts application suitable for picking a contact.
     * As hard as it is to believe, some vendors ship without it.
     */
    private fun hasContactPicker(): Boolean {
        return hasContactPicker ?: isContactPickerAvailable().also { hasContactPicker = it }
    }

    private fun isContactPickerAvailable(): Boolean {
        val resolveInfoList =
            context.packageManager.queryIntentActivities(ContactIntentHelper.getContactPickerIntent(), 0)
        return resolveInfoList.isNotEmpty()
    }

    private fun hasContactPermission(): Boolean {
        val permissionState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        return permissionState == PackageManager.PERMISSION_GRANTED
    }

    fun showPgpSendError(sendErrorState: SendErrorState) {
        when (sendErrorState) {
            SendErrorState.ENABLED_ERROR -> recipientMvpView.showOpenPgpEnabledErrorDialog(false)
            SendErrorState.PROVIDER_ERROR -> recipientMvpView.showErrorOpenPgpConnection()
            SendErrorState.KEY_CONFIG_ERROR -> recipientMvpView.showErrorNoKeyConfigured()
        }
    }

    fun showPgpAttachError(attachErrorState: AttachErrorState) {
        when (attachErrorState) {
            AttachErrorState.IS_INLINE -> recipientMvpView.showErrorInlineAttach()
        }
    }

    fun builderSetProperties(messageBuilder: MessageBuilder) {
        require(messageBuilder !is PgpMessageBuilder) {
            "PpgMessageBuilder must be called with ComposeCryptoStatus argument!"
        }

        messageBuilder.setTo(toAddresses)
        messageBuilder.setCc(ccAddresses)
        messageBuilder.setBcc(bccAddresses)
    }

    fun builderSetProperties(pgpMessageBuilder: PgpMessageBuilder, cryptoStatus: ComposeCryptoStatus) {
        pgpMessageBuilder.setTo(toAddresses)
        pgpMessageBuilder.setCc(ccAddresses)
        pgpMessageBuilder.setBcc(bccAddresses)
        pgpMessageBuilder.setOpenPgpApi(openPgpApiManager.openPgpApi)
        pgpMessageBuilder.setCryptoStatus(cryptoStatus)
    }

    fun onMenuSetPgpInline(enablePgpInline: Boolean) {
        onCryptoPgpInlineChanged(enablePgpInline)

        if (enablePgpInline) {
            val shouldShowPgpInlineDialog = checkAndIncrementPgpInlineDialogCounter()
            if (shouldShowPgpInlineDialog) {
                recipientMvpView.showOpenPgpInlineDialog(true)
            }
        }
    }

    fun onMenuSetSignOnly(enableSignOnly: Boolean) {
        if (enableSignOnly) {
            onCryptoModeChanged(CryptoMode.SIGN_ONLY)

            val shouldShowPgpSignOnlyDialog = checkAndIncrementPgpSignOnlyDialogCounter()
            if (shouldShowPgpSignOnlyDialog) {
                recipientMvpView.showOpenPgpSignOnlyDialog(true)
            }
        } else {
            onCryptoModeChanged(CryptoMode.NO_CHOICE)
        }
    }

    fun onMenuToggleEncryption() {
        toggleEncryptionState(true)
    }

    fun onCryptoPgpClickDisable() {
        onCryptoModeChanged(CryptoMode.CHOICE_DISABLED)
    }

    fun onCryptoPgpSignOnlyDisabled() {
        onCryptoPgpInlineChanged(false)
        onCryptoModeChanged(CryptoMode.NO_CHOICE)
    }

    private fun checkAndIncrementPgpInlineDialogCounter(): Boolean {
        val pgpInlineDialogCounter = K9.pgpInlineDialogCounter
        if (pgpInlineDialogCounter < PGP_DIALOG_DISPLAY_THRESHOLD) {
            K9.pgpInlineDialogCounter = pgpInlineDialogCounter + 1
            K9.saveSettingsAsync()
            return true
        }

        return false
    }

    private fun checkAndIncrementPgpSignOnlyDialogCounter(): Boolean {
        val pgpSignOnlyDialogCounter = K9.pgpSignOnlyDialogCounter
        if (pgpSignOnlyDialogCounter < PGP_DIALOG_DISPLAY_THRESHOLD) {
            K9.pgpSignOnlyDialogCounter = pgpSignOnlyDialogCounter + 1
            K9.saveSettingsAsync()
            return true
        }

        return false
    }

    fun onClickCryptoSpecialModeIndicator() {
        when {
            currentCryptoMode == CryptoMode.SIGN_ONLY -> {
                recipientMvpView.showOpenPgpSignOnlyDialog(false)
            }
            isForceTextMessageFormat -> {
                recipientMvpView.showOpenPgpInlineDialog(false)
            }
            else -> {
                error("This icon should not be clickable while no special mode is active!")
            }
        }
    }

    private fun Array<String>.toAddressArray(): Array<Address> {
        return flatMap { addressString ->
            Address.parseUnencoded(addressString).toList()
        }.toTypedArray()
    }

    private fun RecipientType.toRequestCode(): Int = when (this) {
        RecipientType.TO -> CONTACT_PICKER_TO
        RecipientType.CC -> CONTACT_PICKER_CC
        RecipientType.BCC -> CONTACT_PICKER_BCC
        else -> throw AssertionError("Unhandled case: $this")
    }

    private fun Int.toRecipientType(): RecipientType = when (this) {
        CONTACT_PICKER_TO -> RecipientType.TO
        CONTACT_PICKER_CC -> RecipientType.CC
        CONTACT_PICKER_BCC -> RecipientType.BCC
        else -> throw AssertionError("Unhandled case: $this")
    }

    enum class CryptoMode {
        SIGN_ONLY,
        NO_CHOICE,
        CHOICE_DISABLED,
        CHOICE_ENABLED,
    }
}
