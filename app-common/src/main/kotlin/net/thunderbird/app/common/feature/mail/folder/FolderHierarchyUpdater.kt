package net.thunderbird.app.common.feature.mail.folder

import com.fsck.k9.backend.api.BackendFolderUpdater
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.folders.FolderServerId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError

private const val TAG = "FolderHierarchyUpdater"

/**
 * Changes the folder hierarchy on the server and mirrors successful changes in the local folder list.
 *
 * Renames are mirrored by renaming the local folders, so locally stored messages and folder settings are retained.
 * New folders are added by refreshing the folder list, because only the server knows the full name of a new top-level
 * folder (e.g. with a namespace prefix like `INBOX.`). If mirroring a change fails, the folder list is refreshed
 * instead, because the server was already changed.
 *
 * Doesn't enforce any rules. That's the responsibility of the caller.
 */
internal class FolderHierarchyUpdater(
    private val backendStorageFactory: BackendStorageFactory,
    private val remoteFolderCreatorFactory: RemoteFolderCreator.Factory,
    private val remoteFolderRenamerFactory: RemoteFolderRenamer.Factory,
    private val remoteFolderDeleterFactory: RemoteFolderDeleter.Factory,
    private val folderListRefresher: FolderListRefresher,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun create(accountId: AccountId, folderServerId: String): Outcome<Unit, FolderManagementError> {
        val remoteFolderCreator = remoteFolderCreatorFactory.create(accountId)
        val outcome = remoteFolderCreator.create(FolderServerId(folderServerId), mustCreate = true)

        return outcome.fold(
            onSuccess = {
                folderListRefresher.refresh(accountId)
                Outcome.success(Unit)
            },
            onFailure = { error -> Outcome.failure(error.toFolderManagementError()) },
        )
    }

    suspend fun rename(
        accountId: AccountId,
        folderServerId: String,
        newFolderServerId: String,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<Unit, FolderManagementError> {
        val remoteFolderRenamer = remoteFolderRenamerFactory.create(accountId)
        val outcome = remoteFolderRenamer.rename(
            folderServerId = FolderServerId(folderServerId),
            newFolderServerId = FolderServerId(newFolderServerId),
            folderPathDelimiter = folderPathDelimiter,
        )

        return outcome.fold(
            onSuccess = {
                // Mirror the rename locally, so locally stored messages and folder settings are retained.
                updateLocalFolders(accountId) { backendStorage ->
                    val renamedFolders = buildRenameMapping(
                        localFolderServerIds = backendStorage.getFolderServerIds(),
                        folderServerId = folderServerId,
                        newFolderServerId = newFolderServerId,
                        folderPathDelimiter = folderPathDelimiter,
                    )

                    // Remove stale local folders that are in the way, e.g. ones deleted on the server by another
                    // client.
                    val localFolderServerIds = backendStorage.getFolderServerIds().toSet()
                    val staleFolderServerIds = renamedFolders.values.filter { serverId ->
                        serverId in localFolderServerIds && serverId !in renamedFolders
                    }
                    deleteFolders(staleFolderServerIds)

                    for ((oldServerId, newServerId) in renamedFolders) {
                        renameFolder(folderServerId = oldServerId, newFolderServerId = newServerId, name = newServerId)
                    }
                }
            },
            onFailure = { error -> Outcome.failure(error.toFolderManagementError()) },
        )
    }

    suspend fun delete(accountId: AccountId, folderServerId: String): Outcome<Unit, FolderManagementError> {
        val remoteFolderDeleter = remoteFolderDeleterFactory.create(accountId)
        val outcome = remoteFolderDeleter.delete(FolderServerId(folderServerId))

        return outcome.fold(
            onSuccess = {
                updateLocalFolders(accountId) { backendStorage ->
                    if (folderServerId in backendStorage.getFolderServerIds()) {
                        deleteFolders(listOf(folderServerId))
                    }
                }
            },
            onFailure = { error -> Outcome.failure(error.toFolderManagementError()) },
        )
    }

    private fun buildRenameMapping(
        localFolderServerIds: List<String>,
        folderServerId: String,
        newFolderServerId: String,
        folderPathDelimiter: FolderPathDelimiter,
    ): Map<String, String> {
        return buildMap {
            if (folderServerId in localFolderServerIds) {
                put(folderServerId, newFolderServerId)
            }

            localFolderServerIds
                .filter { FolderServerIdHelper.isSubfolder(it, folderServerId, folderPathDelimiter) }
                .forEach { serverId -> put(serverId, newFolderServerId + serverId.removePrefix(folderServerId)) }
        }
    }

    /**
     * Mirrors a successful change on the server in the local folder list. The operation counts as successful even if
     * this fails, because the server was changed. In that case, the folder list is refreshed from the server.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun updateLocalFolders(
        accountId: AccountId,
        block: BackendFolderUpdater.(BackendStorage) -> Unit,
    ): Outcome<Unit, FolderManagementError> = withContext(ioDispatcher) {
        try {
            val backendStorage = backendStorageFactory.createBackendStorage(accountId)
            backendStorage.updateFolders { block(backendStorage) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG) { "Failed to update local folder list: ${e::class.simpleName}" }
            folderListRefresher.refresh(accountId)
        }

        Outcome.success(Unit)
    }

    private fun RemoteFolderCreationOutcome.Error.toFolderManagementError(): FolderManagementError {
        return when (this) {
            RemoteFolderCreationOutcome.Error.AlreadyExists -> FolderManagementError.AlreadyExists

            RemoteFolderCreationOutcome.Error.NotSupportedOperation -> FolderManagementError.NotSupported

            is RemoteFolderCreationOutcome.Error.RejectedByServer -> FolderManagementError.ServerError(serverMessage)

            is RemoteFolderCreationOutcome.Error.FailedToCreateRemoteFolder -> {
                // The reason isn't logged because it may contain the folder name.
                logger.warn(TAG) { "Failed to create folder" }
                FolderManagementError.Failed
            }
        }
    }

    private fun RemoteFolderOperationError.toFolderManagementError(): FolderManagementError {
        return when (this) {
            RemoteFolderOperationError.AlreadyExists -> FolderManagementError.AlreadyExists

            RemoteFolderOperationError.HasSubfolders -> FolderManagementError.HasSubfolders

            RemoteFolderOperationError.NotSupportedOperation -> FolderManagementError.NotSupported

            is RemoteFolderOperationError.RejectedByServer -> FolderManagementError.ServerError(serverMessage)

            is RemoteFolderOperationError.Failed -> {
                // The reason isn't logged because it may contain the folder name.
                logger.warn(TAG) { "Folder operation failed" }
                FolderManagementError.Failed
            }
        }
    }
}
