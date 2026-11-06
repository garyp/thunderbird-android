package com.fsck.k9.ui.managefolders

import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.fsck.k9.fragment.ProgressDialogFragment
import com.fsck.k9.ui.R

private const val TAG_PROGRESS_DIALOG = "folder_operation_progress"
private const val MAX_SERVER_MESSAGE_LENGTH = 200

enum class FolderOperation {
    CREATE,
    RENAME,
    DELETE,
}

data class FolderOperationEvent(
    val operation: FolderOperation,
    val result: FolderOperationResult,
)

internal fun Fragment.setFolderOperationProgressVisible(visible: Boolean) {
    val fragmentManager = childFragmentManager
    val progressDialog = fragmentManager.findFragmentByTag(TAG_PROGRESS_DIALOG) as? DialogFragment

    if (visible && progressDialog == null) {
        ProgressDialogFragment.newInstance(
            title = null,
            message = getString(R.string.folder_operation_in_progress),
        ).apply {
            isCancelable = false
        }.show(fragmentManager, TAG_PROGRESS_DIALOG)
    } else if (!visible && progressDialog != null) {
        progressDialog.dismissAllowingStateLoss()
    }
}

internal fun Fragment.showFolderOperationResult(event: FolderOperationEvent) {
    val message = when (val result = event.result) {
        FolderOperationResult.Success -> getSuccessMessage(event.operation)
        is FolderOperationResult.InvalidName -> getInvalidNameMessage(result.error)
        FolderOperationResult.AlreadyExists -> getString(R.string.folder_operation_error_already_exists)
        FolderOperationResult.HasSubfolders -> getString(R.string.folder_operation_error_has_subfolders)
        FolderOperationResult.NotAllowed -> getString(R.string.folder_operation_error_not_allowed)
        FolderOperationResult.FolderNotFound -> getString(R.string.folder_operation_error_folder_not_found)
        is FolderOperationResult.ServerError -> getServerErrorMessage(result.serverMessage)
        FolderOperationResult.Failed -> getString(R.string.folder_operation_error_failed)
    }

    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
}

private fun Fragment.getSuccessMessage(operation: FolderOperation): String {
    return when (operation) {
        FolderOperation.CREATE -> getString(R.string.folder_operation_created)
        FolderOperation.RENAME -> getString(R.string.folder_operation_renamed)
        FolderOperation.DELETE -> getString(R.string.folder_operation_deleted)
    }
}

private fun Fragment.getInvalidNameMessage(error: FolderNameError): String {
    return when (error) {
        FolderNameError.EMPTY -> getString(R.string.folder_name_error_empty)
        FolderNameError.CONTAINS_DELIMITER, FolderNameError.INVALID_CHARACTER -> {
            getString(R.string.folder_name_error_invalid_character)
        }
        FolderNameError.RESERVED_NAME -> getString(R.string.folder_name_error_reserved_name)
    }
}

private fun Fragment.getServerErrorMessage(serverMessage: String?): String {
    val trimmedMessage = serverMessage?.trim()?.take(MAX_SERVER_MESSAGE_LENGTH)
    return if (trimmedMessage.isNullOrEmpty()) {
        getString(R.string.folder_operation_error_server)
    } else {
        getString(R.string.folder_operation_error_server_with_message, trimmedMessage)
    }
}
