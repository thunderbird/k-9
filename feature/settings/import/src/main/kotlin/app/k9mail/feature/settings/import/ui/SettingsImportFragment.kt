package app.k9mail.feature.settings.import.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import app.k9mail.feature.migration.launcher.api.MigrationManager
import app.k9mail.feature.settings.importing.R
import com.fsck.k9.ui.base.livedata.observeNotNull
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.fsck.k9.ui.base.R as BaseR

class SettingsImportFragment : Fragment() {
    private val viewModel: SettingsImportViewModel by viewModel()
    private val migrationManager: MigrationManager by inject()

    private lateinit var settingsImportAdapter: FastAdapter<ImportListItem<*>>
    private lateinit var itemAdapter: ItemAdapter<ImportListItem<*>>

    private val pickDocumentCallback = ActivityResultCallback<Uri?> { contentUri ->
        if (contentUri != null) {
            viewModel.onDocumentPicked(contentUri)
        } else {
            viewModel.onDocumentPickCanceled()
        }
    }

    private val pickDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
        pickDocumentCallback,
    )
    private val qrCodeScannerLauncher = registerForActivityResult(
        migrationManager.getQrCodeActivityResultContract(),
        pickDocumentCallback,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize()

        setFragmentResultListener(PickAppDialogFragment.FRAGMENT_RESULT_KEY) { _, result: Bundle ->
            val packageName = result.getString(PickAppDialogFragment.FRAGMENT_RESULT_APP)
            if (packageName != null) {
                viewModel.onAppPicked(packageName)
            } else {
                viewModel.onAppPickCanceled()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings_import, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            viewModel.initializeFromSavedState(savedInstanceState)
        }

        val viewHolder = ViewHolder(view)

        initializeSettingsImportList(viewHolder.settingsImportList)
        viewHolder.pickDocumentButton.setOnClickListener { viewModel.onPickDocumentButtonClicked() }
        viewHolder.scanQrCodeButton.setOnClickListener { viewModel.onScanQrCodeButtonClicked() }
        viewHolder.pickAppButton.setOnClickListener { viewModel.onPickAppButtonClicked() }
        viewHolder.importButton.setOnClickListener { viewModel.onImportButtonClicked() }
        viewHolder.closeButton.setOnClickListener { viewModel.onCloseButtonClicked() }

        viewModel.getUiModel().observeNotNull(this) { viewHolder.updateUi(it) }
        viewModel.getActionEvents().observeNotNull(this) { handleActionEvents(it) }

        arguments?.let { arguments ->
            val action = SettingsImportAction.fromBundle(arguments)
            viewModel.setAction(action)
        }
    }

    private fun initializeSettingsImportList(recyclerView: RecyclerView) {
        itemAdapter = ItemAdapter()
        settingsImportAdapter = FastAdapter.with(itemAdapter).apply {
            setHasStableIds(true)
            onClickListener = { _, _, _, position ->
                viewModel.onSettingsListItemClicked(position)
                true
            }
            addEventHook(
                ImportListItemClickEvent { position ->
                    viewModel.onSettingsListItemClicked(position)
                },
            )
        }

        recyclerView.adapter = settingsImportAdapter
    }

    private fun ViewHolder.updateUi(model: SettingsImportUiModel) {
        when (model.importButton) {
            ButtonState.DISABLED -> {
                importButton.isVisible = true
                importButton.isEnabled = false
            }

            ButtonState.ENABLED -> {
                importButton.isVisible = true
                importButton.isEnabled = true
            }

            ButtonState.INVISIBLE -> importButton.isInvisible = true
            ButtonState.GONE -> importButton.isGone = true
        }

        closeButton.isGone = model.closeButton == ButtonState.GONE
        when (model.closeButtonLabel) {
            CloseButtonLabel.OK -> closeButton.setText(BaseR.string.okay_action)
            CloseButtonLabel.LATER -> closeButton.setText(R.string.settings_import_later_button)
        }

        settingsImportList.isVisible = model.isSettingsListVisible
        pickDocumentButton.isInvisible = !model.isPickDocumentButtonVisible
        pickDocumentButton.isEnabled = model.isPickDocumentButtonEnabled
        scanQrCodeButton.isInvisible = !model.isScanQrCodeButtonVisible
        scanQrCodeButton.isEnabled = model.isScanQrCodeButtonEnabled
        pickAppButton.isInvisible = !model.isPickAppButtonVisible
        pickAppButton.isEnabled = model.isPickAppButtonEnabled
        loadingProgressBar.isVisible = model.isLoadingProgressVisible
        importProgressBar.isVisible = model.isImportProgressVisible

        statusText.isVisible = model.statusText != StatusText.HIDDEN
        when (model.statusText) {
            StatusText.IMPORTING_PROGRESS -> {
                statusText.text = getString(R.string.settings_importing)
            }

            StatusText.IMPORT_SUCCESS -> {
                statusText.text = getString(R.string.settings_import_success_generic)
            }

            StatusText.IMPORT_SUCCESS_PASSWORD_REQUIRED -> {
                statusText.text = getString(R.string.settings_import_password_required)
            }

            StatusText.IMPORT_SUCCESS_AUTHORIZATION_REQUIRED -> {
                statusText.text = getString(R.string.settings_import_authorization_required)
            }

            StatusText.IMPORT_SUCCESS_PASSWORD_AND_AUTHORIZATION_REQUIRED -> {
                statusText.text = getString(R.string.settings_import_authorization_and_password_required)
            }

            StatusText.IMPORT_READ_FAILURE -> {
                statusText.text = getString(R.string.settings_import_read_failure)
            }

            StatusText.IMPORT_PARTIAL_FAILURE -> {
                statusText.text = getString(R.string.settings_import_partial_failure)
            }

            StatusText.IMPORT_FAILURE -> {
                statusText.text = getString(R.string.settings_import_failure)
            }

            StatusText.HIDDEN -> statusText.text = null
        }

        setSettingsList(model.settingsList, model.isSettingsListEnabled)
    }

    // TODO: Update list instead of replacing it completely
    private fun ViewHolder.setSettingsList(items: List<SettingsListItem>, enable: Boolean) {
        val importListItems = items.map { item ->
            val checkBoxItem = when (item) {
                is SettingsListItem.GeneralSettings -> GeneralSettingsItem(item.importStatus)
                is SettingsListItem.Account -> AccountItem(item)
            }

            checkBoxItem.apply {
                isSelected = item.selected
                isEnabled = item.enabled && enable
            }
        }

        itemAdapter.set(importListItems)

        settingsImportList.isEnabled = enable
    }

    private fun handleActionEvents(action: Action) {
        when (action) {
            is Action.Close -> closeImportScreen(action)
            is Action.PickDocument -> pickDocument()
            is Action.ScanQrCode -> scanQrCode()
            is Action.PickApp -> pickApp()
            is Action.PasswordPrompt -> showPasswordPrompt(action)
            is Action.StartAuthorization -> startAuthorization(action)
        }
    }

    @Suppress("SwallowedException")
    private fun closeImportScreen(action: Action.Close) {
        setFragmentResult(action.importSuccess)

        try {
            findNavController().popBackStack()
        } catch (e: IllegalStateException) {
            // Fragment does not have NavController
        }
    }

    private fun pickDocument() {
        pickDocumentLauncher.launch("*/*")
    }

    private fun scanQrCode() {
        qrCodeScannerLauncher.launch(Unit)
    }

    private fun pickApp() {
        PickAppDialogFragment().show(parentFragmentManager, "pick_app")
    }

    private fun startAuthorization(action: Action.StartAuthorization) {
        val intent = OAuthFlowActivity.buildLaunchIntent(
            context = requireContext(),
            accountUuid = action.accountUuid,
        )

        startActivityForResult(intent, REQUEST_AUTHORIZATION)
    }

    private fun showPasswordPrompt(action: Action.PasswordPrompt) {
        val dialogFragment = PasswordPromptDialogFragment.create(
            action.accountUuid,
            action.accountName,
            action.inputIncomingServerPassword,
            action.incomingServerName,
            action.inputOutgoingServerPassword,
            action.outgoingServerName,
            targetFragment = this,
            requestCode = REQUEST_PASSWORD_PROMPT,
        )
        dialogFragment.show(requireFragmentManager(), null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.saveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_PASSWORD_PROMPT -> handlePasswordPromptResult(resultCode, data)
            REQUEST_AUTHORIZATION -> handleAuthorizationResult(resultCode)
        }
    }

    private fun handlePasswordPromptResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val resultIntent = data ?: error("No result intent received")
            val result = PasswordPromptResult.fromIntent(resultIntent)
            viewModel.onPasswordPromptResult(result)
        }
    }

    private fun handleAuthorizationResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            viewModel.onReturnAfterAuthorization()
        }
    }

    private fun setFragmentResult(accountImported: Boolean) {
        setFragmentResult(
            requestKey = FRAGMENT_RESULT_KEY,
            result = bundleOf(
                FRAGMENT_RESULT_ACCOUNT_IMPORTED to accountImported,
            ),
        )
    }

    companion object {
        private const val REQUEST_PASSWORD_PROMPT = Activity.RESULT_FIRST_USER
        private const val REQUEST_AUTHORIZATION = Activity.RESULT_FIRST_USER + 1

        const val FRAGMENT_RESULT_KEY = "settings_import"
        const val FRAGMENT_RESULT_ACCOUNT_IMPORTED = "accountImported"
    }
}

private class ViewHolder(view: View) {
    val pickDocumentButton: View = view.findViewById(R.id.pickDocumentButton)
    val scanQrCodeButton: View = view.findViewById(R.id.scanQrCodeButton)
    val pickAppButton: View = view.findViewById(R.id.pickAppButton)
    val importButton: View = view.findViewById(R.id.importButton)
    val closeButton: MaterialButton = view.findViewById(R.id.closeButton)
    val loadingProgressBar: View = view.findViewById(R.id.loadingProgressBar)
    val importProgressBar: View = view.findViewById(R.id.importProgressBar)
    val statusText: MaterialTextView = view.findViewById(R.id.statusText)
    val settingsImportList: RecyclerView = view.findViewById(R.id.settingsImportList)
}
