package com.fsck.k9.ui.managefolders

import android.app.Dialog
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.fsck.k9.ui.base.R as BaseR

/**
 * Dialog asking the user for a folder name, e.g. when creating or renaming a folder.
 *
 * The entered name is validated using [FolderNameValidator] and returned via the Fragment Result API using the
 * request key passed to [show]. The name can be retrieved from the result bundle using [RESULT_FOLDER_NAME].
 */
class FolderNameDialogFragment : DialogFragment() {
    private lateinit var folderNameLayout: TextInputLayout
    private lateinit var folderNameEditText: TextInputEditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val title = arguments.getString(ARG_TITLE)
        val confirmText = arguments.getString(ARG_CONFIRM_TEXT)
        val initialName = arguments.getString(ARG_INITIAL_NAME).orEmpty()

        val view = layoutInflater.inflate(R.layout.dialog_folder_name, null)
        folderNameLayout = view.findViewById(R.id.folderNameLayout)
        folderNameEditText = view.findViewById(R.id.folderName)

        if (savedInstanceState == null) {
            folderNameEditText.setText(initialName)
            folderNameEditText.setSelection(initialName.length)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(view)
            .setPositiveButton(confirmText, null)
            .setNegativeButton(BaseR.string.cancel_action, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
            updateState(showError = false)
        }

        folderNameEditText.doAfterTextChanged { updateState(showError = true) }
        folderNameEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirm()
                true
            } else {
                false
            }
        }

        return dialog
    }

    private fun validate(): FolderNameError? {
        val arguments = requireArguments()
        val folderPathDelimiter = arguments.getString(ARG_FOLDER_PATH_DELIMITER).orEmpty()
        val isTopLevel = arguments.getBoolean(ARG_IS_TOP_LEVEL)

        return FolderNameValidator.validate(folderNameEditText.text.toString(), folderPathDelimiter, isTopLevel)
    }

    private fun updateState(showError: Boolean) {
        val error = validate()

        folderNameLayout.error = if (showError) error?.let { getErrorMessage(it) } else null
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = error == null
    }

    private fun getErrorMessage(error: FolderNameError): String? {
        return when (error) {
            // The confirm button is disabled; showing an error for an empty name would be noise
            FolderNameError.EMPTY -> null
            FolderNameError.CONTAINS_DELIMITER -> getString(
                R.string.folder_name_error_contains_delimiter,
                requireArguments().getString(ARG_FOLDER_PATH_DELIMITER),
            )
            FolderNameError.INVALID_CHARACTER -> getString(R.string.folder_name_error_invalid_character)
            FolderNameError.RESERVED_NAME -> getString(R.string.folder_name_error_reserved_name)
        }
    }

    private fun confirm() {
        if (validate() != null) return

        val requestKey = requireArguments().getString(ARG_REQUEST_KEY) ?: error("Missing request key")
        parentFragmentManager.setFragmentResult(
            requestKey,
            bundleOf(RESULT_FOLDER_NAME to folderNameEditText.text.toString().trim()),
        )
        dismiss()
    }

    companion object {
        const val RESULT_FOLDER_NAME = "folderName"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_CONFIRM_TEXT = "confirmText"
        private const val ARG_INITIAL_NAME = "initialName"
        private const val ARG_FOLDER_PATH_DELIMITER = "folderPathDelimiter"
        private const val ARG_IS_TOP_LEVEL = "isTopLevel"

        @Suppress("LongParameterList")
        fun show(
            fragmentManager: FragmentManager,
            requestKey: String,
            title: String,
            confirmText: String,
            folderPathDelimiter: String,
            isTopLevel: Boolean,
            initialName: String? = null,
        ) {
            FolderNameDialogFragment().apply {
                arguments = createArguments(
                    requestKey,
                    title,
                    confirmText,
                    folderPathDelimiter,
                    isTopLevel,
                    initialName,
                )
            }.show(fragmentManager, requestKey)
        }

        @Suppress("LongParameterList")
        internal fun createArguments(
            requestKey: String,
            title: String,
            confirmText: String,
            folderPathDelimiter: String,
            isTopLevel: Boolean,
            initialName: String?,
        ): Bundle = bundleOf(
            ARG_REQUEST_KEY to requestKey,
            ARG_TITLE to title,
            ARG_CONFIRM_TEXT to confirmText,
            ARG_INITIAL_NAME to initialName,
            ARG_FOLDER_PATH_DELIMITER to folderPathDelimiter,
            ARG_IS_TOP_LEVEL to isTopLevel,
        )
    }
}
