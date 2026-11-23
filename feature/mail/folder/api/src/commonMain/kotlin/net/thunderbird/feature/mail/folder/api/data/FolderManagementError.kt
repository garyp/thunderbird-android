package net.thunderbird.feature.mail.folder.api.data

import net.thunderbird.feature.mail.folder.api.FolderNameError
import net.thunderbird.piisafe.annotation.PiiSafe

/**
 * Errors of creating, renaming, or deleting a folder.
 */
public sealed interface FolderManagementError {
    public data class InvalidName(val error: FolderNameError) : FolderManagementError

    /**
     * A folder with the requested name already exists.
     */
    public data object AlreadyExists : FolderManagementError

    /**
     * The folder can't be deleted because it has subfolders.
     */
    public data object HasSubfolders : FolderManagementError

    /**
     * The folder can't be moved to the chosen parent, e.g. because it's the folder itself or one of its subfolders.
     */
    public data object InvalidMoveTarget : FolderManagementError

    /**
     * The operation isn't allowed for this folder, e.g. deleting a special folder or renaming the Inbox.
     */
    public data object NotAllowed : FolderManagementError

    public data object FolderNotFound : FolderManagementError

    /**
     * The account doesn't support folder management, e.g. POP3 accounts.
     */
    public data object NotSupported : FolderManagementError

    /**
     * The server refused the operation.
     *
     * @property serverMessage The server's explanation, if any. This is untrusted, server-provided text that may only
     *   be displayed to the user as plain text. It may contain folder names, so it must not be logged.
     */
    @PiiSafe.HasPii
    public data class ServerError(
        @get:PiiSafe.Mask
        val serverMessage: String?,
    ) : FolderManagementError

    /**
     * The operation couldn't be completed, e.g. because of a network error.
     */
    public data object Failed : FolderManagementError
}
