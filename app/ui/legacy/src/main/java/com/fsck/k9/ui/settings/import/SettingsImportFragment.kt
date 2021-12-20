package com.fsck.k9.ui.settings.import

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.ThemeManager
import com.fsck.k9.ui.observeNotNull
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class SettingsImportFragment : Fragment() {
    private val themeManager: ThemeManager by inject()
    private val viewModel: SettingsImportViewModel by viewModel()
    private val resultViewModel: SettingsImportResultViewModel by sharedViewModel()

    private lateinit var settingsImportAdapter: FastAdapter<ImportListItem<*>>
    private lateinit var itemAdapter: ItemAdapter<ImportListItem<*>>

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
        viewHolder.importButton.setOnClickListener { viewModel.onImportButtonClicked() }
        viewHolder.closeButton.setOnClickListener { viewModel.onCloseButtonClicked() }

        viewModel.getUiModel().observeNotNull(this) { viewHolder.updateUi(it) }
        viewModel.getActionEvents().observeNotNull(this) { handleActionEvents(it) }
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
                }
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
            CloseButtonLabel.OK -> closeButton.setText(R.string.okay_action)
            CloseButtonLabel.LATER -> closeButton.setText(R.string.settings_import_later_button)
        }

        settingsImportList.isVisible = model.isSettingsListVisible
        pickDocumentButton.isInvisible = !model.isPickDocumentButtonVisible
        pickDocumentButton.isEnabled = model.isPickDocumentButtonEnabled
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

        if (model.statusText == StatusText.IMPORT_SUCCESS ||
            model.statusText == StatusText.IMPORT_SUCCESS_PASSWORD_REQUIRED ||
            model.statusText == StatusText.IMPORT_PARTIAL_FAILURE
        ) {
            themeManager.updateAppTheme()
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
            is Action.PasswordPrompt -> showPasswordPrompt(action)
        }
    }

    private fun closeImportScreen(action: Action.Close) {
        if (action.importSuccess) {
            resultViewModel.setSettingsImportResult()
        }
        findNavController().popBackStack()
    }

    private fun pickDocument() {
        val createDocumentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(createDocumentIntent, REQUEST_PICK_DOCUMENT)
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
            requestCode = REQUEST_PASSWORD_PROMPT
        )
        dialogFragment.show(requireFragmentManager(), null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.saveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_PICK_DOCUMENT -> handlePickDocumentResult(resultCode, data)
            REQUEST_PASSWORD_PROMPT -> handlePasswordPromptResult(resultCode, data)
        }
    }

    private fun handlePickDocumentResult(resultCode: Int, data: Intent?) {
        val contentUri = data?.data
        if (resultCode == Activity.RESULT_OK && contentUri != null) {
            viewModel.onDocumentPicked(contentUri)
        } else {
            viewModel.onDocumentPickCanceled()
        }
    }

    private fun handlePasswordPromptResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val resultIntent = data ?: error("No result intent received")
            val result = PasswordPromptResult.fromIntent(resultIntent)
            viewModel.onPasswordPromptResult(result)
        }
    }

    companion object {
        private const val REQUEST_PICK_DOCUMENT = Activity.RESULT_FIRST_USER
        private const val REQUEST_PASSWORD_PROMPT = Activity.RESULT_FIRST_USER + 1
    }
}

private class ViewHolder(view: View) {
    val pickDocumentButton: View = view.findViewById(R.id.pickDocumentButton)
    val importButton: View = view.findViewById(R.id.importButton)
    val closeButton: Button = view.findViewById(R.id.closeButton)
    val loadingProgressBar: View = view.findViewById(R.id.loadingProgressBar)
    val importProgressBar: View = view.findViewById(R.id.importProgressBar)
    val statusText: TextView = view.findViewById(R.id.statusText)
    val settingsImportList: RecyclerView = view.findViewById(R.id.settingsImportList)
}
