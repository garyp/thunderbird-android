package com.fsck.k9.ui.managefolders

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.fsck.k9.fragment.ProgressDialogFragment
import com.fsck.k9.ui.R
import net.thunderbird.feature.mail.folder.api.FolderNameError
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError

private const val TAG_PROGRESS_DIALOG = "folder_operation_progress"
private const val MAX_SERVER_MESSAGE_LENGTH = 200

enum class FolderOperation {
    CREATE,
    RENAME,
    MOVE,
    DELETE,
}

/**
 * The result of a folder operation. [error] is `null` if the operation was successful.
 */
data class FolderOperationEvent(
    val operation: FolderOperation,
    val error: FolderManagementError?,
) {
    val isSuccess: Boolean get() = error == null
}

internal fun Fragment.setFolderOperationProgressVisible(visible: Boolean) {
    setFolderOperationProgressVisible(requireContext(), childFragmentManager, visible)
}

internal fun setFolderOperationProgressVisible(context: Context, fragmentManager: FragmentManager, visible: Boolean) {
    val progressDialog = fragmentManager.findFragmentByTag(TAG_PROGRESS_DIALOG) as? DialogFragment

    if (visible && progressDialog == null) {
        ProgressDialogFragment.newInstance(
            title = null,
            message = context.getString(R.string.folder_operation_in_progress),
        ).apply {
            isCancelable = false
        }.show(fragmentManager, TAG_PROGRESS_DIALOG)
    } else if (!visible && progressDialog != null) {
        progressDialog.dismissAllowingStateLoss()
    }
}

internal fun Fragment.showFolderOperationResult(event: FolderOperationEvent) {
    requireContext().showFolderOperationResult(event)
}

internal fun Context.showFolderOperationResult(event: FolderOperationEvent) {
    val message = when (val error = event.error) {
        null -> getSuccessMessage(event.operation)
        is FolderManagementError.InvalidName -> getInvalidNameMessage(error.error)
        FolderManagementError.AlreadyExists -> getString(R.string.folder_operation_error_already_exists)
        FolderManagementError.HasSubfolders -> getString(R.string.folder_operation_error_has_subfolders)
        FolderManagementError.InvalidMoveTarget -> getString(R.string.folder_operation_error_invalid_move_target)
        FolderManagementError.NotAllowed, FolderManagementError.NotSupported -> {
            getString(R.string.folder_operation_error_not_allowed)
        }
        FolderManagementError.FolderNotFound -> getString(R.string.folder_operation_error_folder_not_found)
        is FolderManagementError.ServerError -> getServerErrorMessage(error.serverMessage)
        FolderManagementError.Failed -> getString(R.string.folder_operation_error_failed)
    }

    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private fun Context.getSuccessMessage(operation: FolderOperation): String {
    return when (operation) {
        FolderOperation.CREATE -> getString(R.string.folder_operation_created)
        FolderOperation.RENAME -> getString(R.string.folder_operation_renamed)
        FolderOperation.MOVE -> getString(R.string.folder_operation_moved)
        FolderOperation.DELETE -> getString(R.string.folder_operation_deleted)
    }
}

private fun Context.getInvalidNameMessage(error: FolderNameError): String {
    return when (error) {
        FolderNameError.EMPTY -> getString(R.string.folder_name_error_empty)
        FolderNameError.CONTAINS_DELIMITER, FolderNameError.INVALID_CHARACTER -> {
            getString(R.string.folder_name_error_invalid_character)
        }
        FolderNameError.RESERVED_NAME -> getString(R.string.folder_name_error_reserved_name)
    }
}

private fun Context.getServerErrorMessage(serverMessage: String?): String {
    val trimmedMessage = serverMessage?.let { sanitizeServerMessage(it) }
    return if (trimmedMessage.isNullOrEmpty()) {
        getString(R.string.folder_operation_error_server)
    } else {
        getString(R.string.folder_operation_error_server_with_message, trimmedMessage)
    }
}

/**
 * Prepares untrusted, server-provided text for display as plain text: control and format characters (e.g. bidi
 * overrides) are replaced, whitespace is collapsed, and the length is limited.
 */
internal fun sanitizeServerMessage(serverMessage: String): String {
    return serverMessage
        .replace(UNSAFE_OR_WHITESPACE_REGEX, " ")
        .trim()
        .take(MAX_SERVER_MESSAGE_LENGTH)
}

private val UNSAFE_OR_WHITESPACE_REGEX = Regex("""[\p{Cc}\p{Cf}\s]+""")
