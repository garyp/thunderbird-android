package net.thunderbird.backend.api.folder

import com.fsck.k9.mail.folders.FolderServerId
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId

interface RemoteFolderDeleter {
    /**
     * Deletes a folder on the remote server. A folder that doesn't exist on the server (anymore) is treated as
     * deleted.
     *
     * This only changes the folder on the server. Callers are responsible for updating the local folder list.
     *
     * @param folderServerId The server ID of the folder to delete.
     */
    suspend fun delete(
        folderServerId: FolderServerId,
    ): Outcome<Unit, RemoteFolderOperationError>

    interface Factory {
        suspend fun create(accountId: AccountId): RemoteFolderDeleter
    }
}
