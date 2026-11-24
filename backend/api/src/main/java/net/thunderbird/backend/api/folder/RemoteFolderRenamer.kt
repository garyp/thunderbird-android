package net.thunderbird.backend.api.folder

import com.fsck.k9.mail.folders.FolderServerId
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

interface RemoteFolderRenamer {
    /**
     * Renames a folder on the remote server. Subfolders are renamed along with it. The new name may have a different
     * parent, which moves the folder to another place in the folder hierarchy.
     *
     * This only changes the folder on the server. Callers are responsible for updating the local folder list.
     *
     * @param folderServerId The server ID of the folder to rename.
     * @param newFolderServerId The new server ID (full hierarchical name).
     * @param folderPathDelimiter The delimiter separating levels in the folder hierarchy.
     * @return The server ID the folder has after the rename. It can differ from [newFolderServerId], e.g. when the
     *   server adds a namespace prefix to top-level folders.
     */
    suspend fun rename(
        folderServerId: FolderServerId,
        newFolderServerId: FolderServerId,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<FolderServerId, RemoteFolderOperationError>

    interface Factory {
        suspend fun create(accountId: AccountId): RemoteFolderRenamer
    }
}
