package com.fsck.k9.activity.compose

import android.app.PendingIntent
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewAnimator
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.loader.app.LoaderManager
import com.fsck.k9.FontSizes
import com.fsck.k9.activity.MessageCompose
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.ui.R
import com.fsck.k9.view.RecipientSelectView
import com.fsck.k9.view.RecipientSelectView.Recipient
import com.fsck.k9.view.ToolableViewAnimator

class RecipientMvpView(private val activity: MessageCompose) : View.OnFocusChangeListener, View.OnClickListener {
    private val toView: RecipientSelectView = activity.findViewById(R.id.to)
    private val ccView: RecipientSelectView = activity.findViewById(R.id.cc)
    private val bccView: RecipientSelectView = activity.findViewById(R.id.bcc)
    private val ccWrapper: View = activity.findViewById(R.id.cc_wrapper)
    private val ccDivider: View = activity.findViewById(R.id.cc_divider)
    private val bccWrapper: View = activity.findViewById(R.id.bcc_wrapper)
    private val bccDivider: View = activity.findViewById(R.id.bcc_divider)
    private val recipientExpanderContainer: ViewAnimator = activity.findViewById(R.id.recipient_expander_container)
    private val cryptoStatusView: ToolableViewAnimator = activity.findViewById(R.id.crypto_status)
    private val cryptoSpecialModeIndicator: ToolableViewAnimator = activity.findViewById(R.id.crypto_special_mode)
    private val textWatchers: MutableSet<TextWatcher> = HashSet()
    private lateinit var presenter: RecipientPresenter

    init {
        cryptoStatusView.setOnClickListener(this)
        cryptoSpecialModeIndicator.setOnClickListener(this)
        toView.onFocusChangeListener = this
        ccView.onFocusChangeListener = this
        bccView.onFocusChangeListener = this

        activity.findViewById<View>(R.id.recipient_expander).setOnClickListener(this)
        activity.findViewById<View>(R.id.to_label).setOnClickListener(this)
        activity.findViewById<View>(R.id.cc_label).setOnClickListener(this)
        activity.findViewById<View>(R.id.bcc_label).setOnClickListener(this)
    }

    val isCcVisible: Boolean
        get() = ccWrapper.isVisible

    val isBccVisible: Boolean
        get() = bccWrapper.isVisible

    val toAddresses: List<Address>
        get() = toView.addresses.toList()

    val ccAddresses: List<Address>
        get() = ccView.addresses.toList()

    val bccAddresses: List<Address>
        get() = bccView.addresses.toList()

    val toRecipients: List<Recipient>
        get() = toView.objects

    val ccRecipients: List<Recipient>
        get() = ccView.objects

    val bccRecipients: List<Recipient>
        get() = bccView.objects

    val isToAddressAdded: Boolean
        get() = presenter.isToAddressAdded()

    val isCcTextEmpty: Boolean
        get() = ccView.text.isEmpty()

    val isBccTextEmpty: Boolean
        get() = bccView.text.isEmpty()

    fun setPresenter(presenter: RecipientPresenter) {
        this.presenter = presenter
        toView.setTokenListener(
            object : RecipientSelectView.TokenListener<Recipient> {
                override fun onTokenAdded(recipient: Recipient) = presenter.onToTokenAdded()

                override fun onTokenRemoved(recipient: Recipient) = presenter.onToTokenRemoved()

                override fun onTokenChanged(recipient: Recipient) = presenter.onToTokenChanged()

                override fun onTokenIgnored(token: Recipient) = Unit
            },
        )

        ccView.setTokenListener(
            object : RecipientSelectView.TokenListener<Recipient> {
                override fun onTokenAdded(recipient: Recipient) = presenter.onCcTokenAdded()

                override fun onTokenRemoved(recipient: Recipient) = presenter.onCcTokenRemoved()

                override fun onTokenChanged(recipient: Recipient) = presenter.onCcTokenChanged()

                override fun onTokenIgnored(token: Recipient) = Unit
            },
        )

        bccView.setTokenListener(
            object : RecipientSelectView.TokenListener<Recipient> {
                override fun onTokenAdded(recipient: Recipient) = presenter.onBccTokenAdded()

                override fun onTokenRemoved(recipient: Recipient) = presenter.onBccTokenRemoved()

                override fun onTokenChanged(recipient: Recipient) = presenter.onBccTokenChanged()

                override fun onTokenIgnored(token: Recipient) = Unit
            },
        )
    }

    fun addTextChangedListener(textWatcher: TextWatcher) {
        textWatchers.add(textWatcher)
        toView.addTextChangedListener(textWatcher)
        ccView.addTextChangedListener(textWatcher)
        bccView.addTextChangedListener(textWatcher)
    }

    private fun removeAllTextChangedListeners(view: TextView) {
        for (textWatcher in textWatchers) {
            view.removeTextChangedListener(textWatcher)
        }
    }

    private fun addAllTextChangedListeners(view: TextView) {
        for (textWatcher in textWatchers) {
            view.addTextChangedListener(textWatcher)
        }
    }

    fun setRecipientTokensShowCryptoEnabled(isEnabled: Boolean) {
        toView.setShowCryptoEnabled(isEnabled)
        ccView.setShowCryptoEnabled(isEnabled)
        bccView.setShowCryptoEnabled(isEnabled)
    }

    fun setCryptoProvider(openPgpProvider: String?) {
        toView.setCryptoProvider(openPgpProvider)
        ccView.setCryptoProvider(openPgpProvider)
        bccView.setCryptoProvider(openPgpProvider)
    }

    fun requestFocusOnToField() {
        toView.requestFocus()
    }

    fun requestFocusOnCcField() {
        ccView.requestFocus()
    }

    fun requestFocusOnBccField() {
        bccView.requestFocus()
    }

    fun setFontSizes(fontSizes: FontSizes, fontSize: Int) {
        val tokenTextSize = getTokenTextSize(fontSize)
        toView.setTokenTextSize(tokenTextSize)
        ccView.setTokenTextSize(tokenTextSize)
        bccView.setTokenTextSize(tokenTextSize)
        fontSizes.setViewTextSize(toView, fontSize)
        fontSizes.setViewTextSize(ccView, fontSize)
        fontSizes.setViewTextSize(bccView, fontSize)
    }

    private fun getTokenTextSize(fontSize: Int): Int {
        return when (fontSize) {
            FontSizes.FONT_10SP -> FontSizes.FONT_10SP
            FontSizes.FONT_12SP -> FontSizes.FONT_12SP
            FontSizes.SMALL -> FontSizes.SMALL
            FontSizes.FONT_16SP -> 15
            FontSizes.MEDIUM -> FontSizes.FONT_16SP
            FontSizes.FONT_20SP -> FontSizes.MEDIUM
            FontSizes.LARGE -> FontSizes.FONT_20SP
            else -> FontSizes.FONT_DEFAULT
        }
    }

    fun addRecipients(recipientType: RecipientType, vararg recipients: Recipient) {
        when (recipientType) {
            RecipientType.TO -> toView.addRecipients(*recipients)
            RecipientType.CC -> ccView.addRecipients(*recipients)
            RecipientType.BCC -> bccView.addRecipients(*recipients)
            else -> throw AssertionError("Unsupported type: $recipientType")
        }
    }

    fun silentlyAddRecipients(recipientType: RecipientType, vararg recipients: Recipient) {
        removeAllTextListenersFromView(recipientType)
        addRecipients(recipientType, *recipients)
        addAllTextListenersToView(recipientType)
    }

    private fun removeAllTextListenersFromView(recipientType: RecipientType) {
        when (recipientType) {
            RecipientType.TO -> removeAllTextChangedListeners(toView)
            RecipientType.CC -> removeAllTextChangedListeners(ccView)
            RecipientType.BCC -> removeAllTextChangedListeners(bccView)
            else -> throw AssertionError("Unsupported type: $recipientType")
        }
    }

    private fun addAllTextListenersToView(recipientType: RecipientType) {
        when (recipientType) {
            RecipientType.TO -> addAllTextChangedListeners(toView)
            RecipientType.CC -> addAllTextChangedListeners(ccView)
            RecipientType.BCC -> addAllTextChangedListeners(bccView)
            else -> throw AssertionError("Unsupported type: $recipientType")
        }
    }

    fun silentlyAddBccAddresses(vararg recipients: Recipient) {
        removeAllTextChangedListeners(bccView)

        bccView.addRecipients(*recipients)

        addAllTextChangedListeners(bccView)
    }

    fun silentlyRemoveBccAddresses(addresses: Array<Address>) {
        if (addresses.isEmpty()) return

        val addressesToRemove = addresses.toSet()
        for (recipient in bccRecipients.toList()) {
            removeAllTextChangedListeners(bccView)

            if (recipient.address in addressesToRemove) {
                bccView.removeObjectSync(recipient)
            }

            addAllTextChangedListeners(bccView)
        }
    }

    fun setCcVisibility(visible: Boolean) {
        ccWrapper.isVisible = visible
        ccDivider.isVisible = visible
    }

    fun setBccVisibility(visible: Boolean) {
        bccWrapper.isVisible = visible
        bccDivider.isVisible = visible
    }

    fun setRecipientExpanderVisibility(visible: Boolean) {
        val childToDisplay = if (visible) VIEW_INDEX_BCC_EXPANDER_VISIBLE else VIEW_INDEX_BCC_EXPANDER_HIDDEN

        if (recipientExpanderContainer.displayedChild != childToDisplay) {
            recipientExpanderContainer.displayedChild = childToDisplay
        }
    }

    fun showNoRecipientsError() {
        toView.error = toView.context.getString(R.string.message_compose_error_no_recipients)
    }

    fun recipientToHasUncompletedText(): Boolean {
        return toView.hasUncompletedText()
    }

    fun recipientCcHasUncompletedText(): Boolean {
        return ccView.hasUncompletedText()
    }

    fun recipientBccHasUncompletedText(): Boolean {
        return bccView.hasUncompletedText()
    }

    fun recipientToTryPerformCompletion(): Boolean {
        return toView.tryPerformCompletion()
    }

    fun recipientCcTryPerformCompletion(): Boolean {
        return ccView.tryPerformCompletion()
    }

    fun recipientBccTryPerformCompletion(): Boolean {
        return bccView.tryPerformCompletion()
    }

    fun showToUncompletedError() {
        toView.error = toView.context.getString(R.string.compose_error_incomplete_recipient)
    }

    fun showCcUncompletedError() {
        ccView.error = ccView.context.getString(R.string.compose_error_incomplete_recipient)
    }

    fun showBccUncompletedError() {
        bccView.error = bccView.context.getString(R.string.compose_error_incomplete_recipient)
    }

    fun showCryptoSpecialMode(cryptoSpecialModeDisplayType: CryptoSpecialModeDisplayType) {
        val shouldBeHidden = cryptoSpecialModeDisplayType.childIdToDisplay == VIEW_INDEX_HIDDEN
        if (shouldBeHidden) {
            cryptoSpecialModeIndicator.isGone = true
            return
        }

        cryptoSpecialModeIndicator.isVisible = true
        cryptoSpecialModeIndicator.displayedChildId = cryptoSpecialModeDisplayType.childIdToDisplay

        activity.invalidateOptionsMenu()
    }

    fun showCryptoStatus(cryptoStatusDisplayType: CryptoStatusDisplayType) {
        val shouldBeHidden = cryptoStatusDisplayType.childIdToDisplay == VIEW_INDEX_HIDDEN
        if (shouldBeHidden) {
            cryptoStatusView.animate()
                .translationXBy(100.0f)
                .alpha(0.0f)
                .setDuration(CRYPTO_ICON_OUT_DURATION.toLong())
                .setInterpolator(CRYPTO_ICON_OUT_ANIMATOR)
                .start()

            return
        }

        cryptoStatusView.isVisible = true
        cryptoStatusView.displayedChildId = cryptoStatusDisplayType.childIdToDisplay
        cryptoStatusView.animate()
            .translationX(0.0f)
            .alpha(1.0f)
            .setDuration(CRYPTO_ICON_IN_DURATION.toLong())
            .setInterpolator(CRYPTO_ICON_IN_ANIMATOR)
            .start()
    }

    fun showContactPicker(requestCode: Int) {
        activity.showContactPicker(requestCode)
    }

    fun showErrorIsSignOnly() {
        Toast.makeText(activity, R.string.error_sign_only_no_encryption, Toast.LENGTH_LONG).show()
    }

    fun showErrorContactNoAddress() {
        Toast.makeText(activity, R.string.error_contact_address_not_found, Toast.LENGTH_LONG).show()
    }

    fun showErrorOpenPgpIncompatible() {
        Toast.makeText(activity, R.string.error_crypto_provider_incompatible, Toast.LENGTH_LONG).show()
    }

    fun showErrorOpenPgpConnection() {
        Toast.makeText(activity, R.string.error_crypto_provider_connect, Toast.LENGTH_LONG).show()
    }

    fun showErrorOpenPgpUserInteractionRequired() {
        Toast.makeText(activity, R.string.error_crypto_provider_ui_required, Toast.LENGTH_LONG).show()
    }

    fun showErrorNoKeyConfigured() {
        Toast.makeText(activity, R.string.compose_error_no_key_configured, Toast.LENGTH_LONG).show()
    }

    fun showErrorInlineAttach() {
        Toast.makeText(activity, R.string.error_crypto_inline_attach, Toast.LENGTH_LONG).show()
    }

    override fun onFocusChange(view: View, hasFocus: Boolean) {
        if (!hasFocus) return

        when (view.id) {
            R.id.to -> presenter.onToFocused()
            R.id.cc -> presenter.onCcFocused()
            R.id.bcc -> presenter.onBccFocused()
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.to_label -> presenter.onClickToLabel()
            R.id.cc_label -> presenter.onClickCcLabel()
            R.id.bcc_label -> presenter.onClickBccLabel()
            R.id.recipient_expander -> presenter.onClickRecipientExpander()
            R.id.crypto_status -> presenter.onClickCryptoStatus()
            R.id.crypto_special_mode -> presenter.onClickCryptoSpecialModeIndicator()
        }
    }

    fun showOpenPgpInlineDialog(firstTime: Boolean) {
        val dialog = PgpInlineDialog.newInstance(firstTime, R.id.crypto_special_mode)
        dialog.show(activity.supportFragmentManager, "openpgp_inline")
    }

    fun showOpenPgpSignOnlyDialog(firstTime: Boolean) {
        val dialog = PgpSignOnlyDialog.newInstance(firstTime, R.id.crypto_special_mode)
        dialog.show(activity.supportFragmentManager, "openpgp_signonly")
    }

    fun showOpenPgpEnabledErrorDialog(isGotItDialog: Boolean) {
        val dialog = PgpEnabledErrorDialog.newInstance(isGotItDialog, R.id.crypto_status_anchor)
        dialog.show(activity.supportFragmentManager, "openpgp_error")
    }

    fun showOpenPgpEncryptExplanationDialog() {
        val dialog = PgpEncryptDescriptionDialog.newInstance(R.id.crypto_status_anchor)
        dialog.show(activity.supportFragmentManager, "openpgp_description")
    }

    fun launchUserInteractionPendingIntent(pendingIntent: PendingIntent?, requestCode: Int) {
        activity.launchUserInteractionPendingIntent(pendingIntent, requestCode)
    }

    fun setLoaderManager(loaderManager: LoaderManager?) {
        toView.setLoaderManager(loaderManager)
        ccView.setLoaderManager(loaderManager)
        bccView.setLoaderManager(loaderManager)
    }

    enum class CryptoStatusDisplayType(val childIdToDisplay: Int) {
        UNCONFIGURED(VIEW_INDEX_HIDDEN),
        UNINITIALIZED(VIEW_INDEX_HIDDEN),
        SIGN_ONLY(R.id.crypto_status_disabled),
        UNAVAILABLE(VIEW_INDEX_HIDDEN),
        ENABLED(R.id.crypto_status_enabled),
        ENABLED_ERROR(R.id.crypto_status_error),
        ENABLED_TRUSTED(R.id.crypto_status_trusted),
        AVAILABLE(R.id.crypto_status_disabled),
        ERROR(R.id.crypto_status_error),
    }

    enum class CryptoSpecialModeDisplayType(val childIdToDisplay: Int) {
        NONE(VIEW_INDEX_HIDDEN),
        PGP_INLINE(R.id.crypto_special_inline),
        SIGN_ONLY(R.id.crypto_special_sign_only),
        SIGN_ONLY_PGP_INLINE(R.id.crypto_special_sign_only_inline),
    }

    companion object {
        private const val VIEW_INDEX_HIDDEN = -1
        private const val VIEW_INDEX_BCC_EXPANDER_VISIBLE = 0
        private const val VIEW_INDEX_BCC_EXPANDER_HIDDEN = 1

        private val CRYPTO_ICON_OUT_ANIMATOR = FastOutLinearInInterpolator()
        private const val CRYPTO_ICON_OUT_DURATION = 195

        private val CRYPTO_ICON_IN_ANIMATOR = LinearOutSlowInInterpolator()
        private const val CRYPTO_ICON_IN_DURATION = 225
    }
}
