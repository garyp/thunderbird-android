package net.thunderbird.backend.api.folder

import com.fsck.k9.mail.folders.FolderServerId
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

interface RemoteFolderRenamer {
    /**
     * Renames a folder on the remote server. Subfolders are renamed along with it.
     *
     * This only changes the folder on the server. Callers are responsible for updating the local folder list.
     *
     * @param folderServerId The server ID of the folder to rename.
     * @param newFolderServerId The new server ID (full hierarchical name).
     * @param folderPathDelimiter The delimiter separating levels in the folder hierarchy.
     */
    suspend fun rename(
        folderServerId: FolderServerId,
        newFolderServerId: FolderServerId,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<Unit, RemoteFolderOperationError>

    interface Factory {
        suspend fun create(accountId: AccountId): RemoteFolderRenamer
    }
}
