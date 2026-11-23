package net.thunderbird.app.common.feature.mail.folder

import app.k9mail.backend.testing.InMemoryBackendStorage
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter
import net.thunderbird.feature.mail.folder.api.RemoteFolder
import net.thunderbird.feature.mail.folder.api.data.FolderError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderQueryRepository
import net.thunderbird.feature.mail.folder.api.data.repository.RemoteFolderQueryRepository

internal class FakeFolderRepository : FolderQueryRepository, RemoteFolderQueryRepository {
    private val folders = mutableMapOf<Long, Pair<Folder, String>>()
    var remoteFoldersError: FolderError? = null

    fun addFolder(folder: Folder, serverId: String) {
        folders[folder.id] = folder to serverId
    }

    override suspend fun findById(accountId: AccountId, folderId: Long): Outcome<Folder?, FolderError> {
        return Outcome.success(folders[folderId]?.first)
    }

    override suspend fun findFolderServerIdById(
        accountId: AccountId,
        folderId: Long,
    ): Outcome<FolderServerId?, FolderError> {
        return Outcome.success(folders[folderId]?.second?.let { FolderServerId(it) })
    }

    override suspend fun getAllByAccountId(accountId: AccountId): Outcome<List<RemoteFolder>, FolderError> {
        remoteFoldersError?.let { return Outcome.failure(it) }
        return Outcome.success(
            folders.values
                .filterNot { (folder, _) -> folder.isLocalOnly }
                .map { (folder, serverId) ->
                    RemoteFolder(id = folder.id, serverId = serverId, name = folder.name, type = folder.type)
                },
        )
    }

    override suspend fun findIdByServerId(
        accountId: AccountId,
        folderServerId: FolderServerId,
    ): Outcome<Long?, FolderError> = error("not implemented")

    override suspend fun isPresent(accountId: AccountId, folderId: Long): Boolean = error("not implemented")
}

internal class FakeBackendStorageFactory(
    val backendStorage: InMemoryBackendStorage = InMemoryBackendStorage(),
) : BackendStorageFactory {
    var exception: Exception? = null

    /**
     * Maps the requested new server ID to the server ID the server reports, e.g. to add a namespace prefix.
     */
    var renamedServerId: (String) -> String = { it }

    override fun createBackendStorage(accountId: AccountId): BackendStorage {
        exception?.let { throw it }
        return backendStorage
    }
}

internal class FakeRemoteFolderOperations :
    RemoteFolderCreator,
    RemoteFolderRenamer,
    RemoteFolderDeleter,
    RemoteFolderCreator.Factory,
    RemoteFolderRenamer.Factory,
    RemoteFolderDeleter.Factory {

    var createOutcome: Outcome<RemoteFolderCreationOutcome.Success, RemoteFolderCreationOutcome.Error> =
        Outcome.success(RemoteFolderCreationOutcome.Success.Created)
    var operationOutcome: Outcome<Unit, RemoteFolderOperationError> = Outcome.success(Unit)
    var exception: Exception? = null

    /**
     * Maps the requested new server ID to the server ID the server reports, e.g. to add a namespace prefix.
     */
    var renamedServerId: (String) -> String = { it }

    val createdFolders = mutableListOf<String>()
    val renamedFolders = mutableListOf<Pair<String, String>>()
    val deletedFolders = mutableListOf<String>()

    override suspend fun create(accountId: AccountId): FakeRemoteFolderOperations = this

    override suspend fun create(
        folderServerId: FolderServerId,
        mustCreate: Boolean,
        folderType: FolderType,
    ): Outcome<RemoteFolderCreationOutcome.Success, RemoteFolderCreationOutcome.Error> {
        createdFolders.add(folderServerId.serverId)
        return createOutcome
    }

    override suspend fun rename(
        folderServerId: FolderServerId,
        newFolderServerId: FolderServerId,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<FolderServerId, RemoteFolderOperationError> {
        renamedFolders.add(folderServerId.serverId to newFolderServerId.serverId)
        return operationOutcome.fold(
            onSuccess = { Outcome.success(FolderServerId(renamedServerId(newFolderServerId.serverId))) },
            onFailure = { Outcome.failure(it) },
        )
    }

    override suspend fun delete(folderServerId: FolderServerId): Outcome<Unit, RemoteFolderOperationError> {
        deletedFolders.add(folderServerId.serverId)
        exception?.let { throw it }
        return operationOutcome
    }
}
