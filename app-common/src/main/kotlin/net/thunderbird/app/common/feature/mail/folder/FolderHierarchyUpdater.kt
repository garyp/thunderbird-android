package net.thunderbird.app.common.feature.mail.folder

import com.fsck.k9.backend.api.BackendFolderUpdater
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.createFolder
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
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
 * Doesn't enforce any rules. That's the responsibility of the caller.
 */
internal class FolderHierarchyUpdater(
    private val backendStorageFactory: BackendStorageFactory,
    private val remoteFolderCreatorFactory: RemoteFolderCreator.Factory,
    private val remoteFolderRenamerFactory: RemoteFolderRenamer.Factory,
    private val remoteFolderDeleterFactory: RemoteFolderDeleter.Factory,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun create(accountId: AccountId, folderServerId: String): Outcome<Unit, FolderManagementError> {
        val remoteFolderCreator = remoteFolderCreatorFactory.create(accountId)
        val outcome = remoteFolderCreator.create(FolderServerId(folderServerId), mustCreate = true)

        return outcome.fold(
            onSuccess = {
                updateLocalFolders(accountId) { backendStorage ->
                    if (folderServerId !in backendStorage.getFolderServerIds()) {
                        createFolder(
                            FolderInfo(serverId = folderServerId, name = folderServerId, type = FolderType.REGULAR),
                        )
                    }
                }
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun updateLocalFolders(
        accountId: AccountId,
        block: BackendFolderUpdater.(BackendStorage) -> Unit,
    ): Outcome<Unit, FolderManagementError> = withContext(ioDispatcher) {
        try {
            val backendStorage = backendStorageFactory.createBackendStorage(accountId)
            backendStorage.updateFolders { block(backendStorage) }
            Outcome.success(Unit)
        } catch (e: RuntimeException) {
            // The server was changed successfully. The next folder list refresh will fix the local folder list.
            logger.error(TAG) { "Failed to update local folder list: ${e::class.simpleName}" }
            Outcome.failure(FolderManagementError.Failed)
        }
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
