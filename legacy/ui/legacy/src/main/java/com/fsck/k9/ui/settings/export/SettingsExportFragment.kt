package com.fsck.k9.ui.settings.export

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import app.k9mail.core.android.common.activity.CreateDocumentResultContract
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.livedata.observeNotNull
import com.google.android.material.textview.MaterialTextView
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import org.koin.androidx.viewmodel.ext.android.viewModel

class SettingsExportFragment : Fragment() {
    private val viewModel: SettingsExportViewModel by viewModel()

    private lateinit var settingsExportAdapter: FastAdapter<CheckBoxItem<*>>
    private lateinit var itemAdapter: ItemAdapter<CheckBoxItem<*>>

    private val createDocumentLauncher: ActivityResultLauncher<CreateDocumentResultContract.Input> =
        registerForActivityResult(
            CreateDocumentResultContract(),
        ) { contentUri ->
            if (contentUri != null) {
                viewModel.onDocumentPicked(contentUri)
            } else {
                viewModel.onDocumentPickCanceled()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings_export, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            viewModel.initializeFromSavedState(savedInstanceState)
        }

        val viewHolder = ViewHolder(view)
        initializeSettingsExportList(viewHolder.settingsExportList)

        viewHolder.exportButton.setOnClickListener { viewModel.onExportButtonClicked() }
        viewHolder.shareButton.setOnClickListener { viewModel.onShareButtonClicked() }

        viewModel.getUiModel().observeNotNull(this) { viewHolder.updateUi(it) }
        viewModel.getActionEvents().observeNotNull(this) { handleActionEvents(it) }
    }

    private fun initializeSettingsExportList(recyclerView: RecyclerView) {
        itemAdapter = ItemAdapter()
        settingsExportAdapter = FastAdapter.with(itemAdapter).apply {
            setHasStableIds(true)
            onClickListener = { _, _, item: CheckBoxItem<*>, position ->
                viewModel.onSettingsListItemSelected(position, !item.isSelected)
                true
            }
            addEventHook(
                CheckBoxClickEvent { position, isSelected ->
                    viewModel.onSettingsListItemSelected(position, isSelected)
                },
            )
        }

        recyclerView.adapter = settingsExportAdapter
    }

    private fun ViewHolder.updateUi(model: SettingsExportUiModel) {
        when (model.exportButton) {
            ButtonState.DISABLED -> {
                exportButton.isVisible = true
                exportButton.isEnabled = false
            }

            ButtonState.ENABLED -> {
                exportButton.isVisible = true
                exportButton.isEnabled = true
            }

            ButtonState.INVISIBLE -> exportButton.visibility = View.INVISIBLE
            ButtonState.GONE -> exportButton.visibility = View.GONE
        }

        shareButton.isVisible = model.isShareButtonVisible
        progressBar.isVisible = model.isProgressVisible

        when (model.statusText) {
            StatusText.HIDDEN -> statusText.isVisible = false
            StatusText.EXPORT_SUCCESS -> {
                statusText.isVisible = true
                statusText.text = getString(R.string.settings_export_success_generic)
            }

            StatusText.PROGRESS -> {
                statusText.isVisible = true
                statusText.text = getString(R.string.settings_export_progress_text)
            }

            StatusText.EXPORT_FAILURE -> {
                statusText.isVisible = true
                statusText.text = getString(R.string.settings_export_failure)
            }
        }

        setSettingsList(model.settingsList, model.isSettingsListEnabled)
    }

    // TODO: Update list instead of replacing it completely
    private fun ViewHolder.setSettingsList(items: List<SettingsListItem>, enable: Boolean) {
        val checkBoxItems = items.map { item ->
            val checkBoxItem = when (item) {
                is SettingsListItem.GeneralSettings -> GeneralSettingsItem()
                is SettingsListItem.Account -> AccountItem(item)
            }

            checkBoxItem.apply {
                isSelected = item.selected
                isEnabled = enable
            }
        }

        itemAdapter.set(checkBoxItems)

        settingsExportList.isEnabled = enable
    }

    private fun handleActionEvents(action: Action) {
        when (action) {
            is Action.PickDocument -> pickDocument(action.fileNameSuggestion, action.mimeType)
            is Action.ShareDocument -> shareDocument(action.contentUri, action.mimeType)
        }
    }

    private fun pickDocument(fileNameSuggestion: String, mimeType: String) {
        createDocumentLauncher.launch(
            input = CreateDocumentResultContract.Input(
                title = fileNameSuggestion,
                mimeType = mimeType,
            ),
        )
    }

    private fun shareDocument(contentUri: Uri, mimeType: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, null))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.saveInstanceState(outState)
    }
}

private class ViewHolder(view: View) {
    val exportButton: View = view.findViewById(R.id.exportButton)
    val shareButton: View = view.findViewById(R.id.shareButton)
    val progressBar: View = view.findViewById(R.id.progressBar)
    val statusText: MaterialTextView = view.findViewById(R.id.statusText)
    val settingsExportList: RecyclerView = view.findViewById(R.id.settingsExportList)
}
