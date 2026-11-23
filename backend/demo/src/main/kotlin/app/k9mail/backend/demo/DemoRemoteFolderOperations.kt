package app.k9mail.backend.demo

import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FOLDER_DEFAULT_PATH_DELIMITER
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/*
 * Remote folder operations of the demo backend. They change the folder hierarchy of the demo "server" (DemoStore) and
 * mirror the behavior of an IMAP server, including refusing operations an IMAP server would refuse.
 *
 * The demo server always uses FOLDER_DEFAULT_PATH_DELIMITER ("/"), which is also the delimiter its folder list refresh
 * reports for the account.
 */

internal class DemoRemoteFolderCreator(
    private val demoStore: DemoStore,
) : RemoteFolderCreator {
    override suspend fun create(
        folderServerId: FolderServerId,
        mustCreate: Boolean,
        folderType: FolderType,
    ): Outcome<RemoteFolderCreationOutcome.Success, RemoteFolderCreationOutcome.Error> {
        val serverId = folderServerId.serverId
        if (demoStore.getFolder(serverId) != null) {
            return if (mustCreate) {
                Outcome.failure(RemoteFolderCreationOutcome.Error.AlreadyExists)
            } else {
                Outcome.success(RemoteFolderCreationOutcome.Success.AlreadyExists)
            }
        }

        demoStore.addFolder(
            folderServerId = serverId,
            folder = DemoStoreFolder(
                name = serverId,
                type = folderType,
                messageServerIds = emptyList(),
                resourcePath = null,
            ),
        )

        return Outcome.success(RemoteFolderCreationOutcome.Success.Created)
    }
}

internal class DemoRemoteFolderRenamer(
    private val demoStore: DemoStore,
) : RemoteFolderRenamer {
    override suspend fun rename(
        folderServerId: FolderServerId,
        newFolderServerId: FolderServerId,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<Unit, RemoteFolderOperationError> {
        val oldName = folderServerId.serverId
        val newServerId = newFolderServerId.serverId
        val folder = demoStore.getFolder(oldName)

        return when {
            folder == null -> Outcome.failure(RemoteFolderOperationError.RejectedByServer("Mailbox doesn't exist"))

            folder.type == FolderType.INBOX -> {
                Outcome.failure(RemoteFolderOperationError.RejectedByServer("Renaming INBOX is not supported"))
            }

            demoStore.getFolder(newServerId) != null -> Outcome.failure(RemoteFolderOperationError.AlreadyExists)

            else -> {
                demoStore.renameFolders(buildRenameMapping(oldName, folder.name, newServerId, folderPathDelimiter))
                Outcome.success(Unit)
            }
        }
    }

    /**
     * Like an IMAP server, rename all subfolders along with the folder. Display names keep the parent's display name
     * (e.g. "Archive" instead of the server ID "archive") and use the new name chosen by the user as the last segment.
     */
    private fun buildRenameMapping(
        folderServerId: String,
        folderName: String,
        newFolderServerId: String,
        folderPathDelimiter: FolderPathDelimiter,
    ): Map<String, Pair<String, String>> {
        val newLeafName = newFolderServerId.substringAfterLast(folderPathDelimiter)
        val parentName = folderName.substringBeforeLast(folderPathDelimiter, missingDelimiterValue = "")
        val newName = if (parentName.isEmpty()) newLeafName else "$parentName$folderPathDelimiter$newLeafName"

        val subfolderPrefix = folderServerId + folderPathDelimiter
        return buildMap {
            put(folderServerId, newFolderServerId to newName)

            for (serverId in demoStore.getFolderIds().filter { it.startsWith(subfolderPrefix) }) {
                val subfolder = demoStore.getFolder(serverId) ?: continue
                val newSubfolderServerId = newFolderServerId + serverId.removePrefix(folderServerId)
                val newSubfolderName = newName + subfolder.name.removePrefix(folderName)
                put(serverId, newSubfolderServerId to newSubfolderName)
            }
        }
    }
}

internal class DemoRemoteFolderDeleter(
    private val demoStore: DemoStore,
) : RemoteFolderDeleter {
    override suspend fun delete(folderServerId: FolderServerId): Outcome<Unit, RemoteFolderOperationError> {
        val serverId = folderServerId.serverId
        val folder = demoStore.getFolder(serverId) ?: return Outcome.success(Unit)
        val hasSubfolders = demoStore.getFolderIds().any {
            it.startsWith(serverId + FOLDER_DEFAULT_PATH_DELIMITER)
        }

        return when {
            folder.type == FolderType.INBOX -> {
                Outcome.failure(RemoteFolderOperationError.RejectedByServer("Can't delete INBOX"))
            }

            hasSubfolders -> {
                Outcome.failure(
                    RemoteFolderOperationError.RejectedByServer("Mailbox has inferior hierarchical names"),
                )
            }

            else -> {
                demoStore.removeFolder(serverId)
                Outcome.success(Unit)
            }
        }
    }
}

class DemoRemoteFolderCreatorFactory(
    private val backendStorageFactory: BackendStorageFactory,
) : RemoteFolderCreator.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderCreator {
        return DemoRemoteFolderCreator(DemoStore(backendStorageFactory.createBackendStorage(accountId)))
    }
}

class DemoRemoteFolderRenamerFactory(
    private val backendStorageFactory: BackendStorageFactory,
) : RemoteFolderRenamer.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderRenamer {
        return DemoRemoteFolderRenamer(DemoStore(backendStorageFactory.createBackendStorage(accountId)))
    }
}

class DemoRemoteFolderDeleterFactory(
    private val backendStorageFactory: BackendStorageFactory,
) : RemoteFolderDeleter.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderDeleter {
        return DemoRemoteFolderDeleter(DemoStore(backendStorageFactory.createBackendStorage(accountId)))
    }
}
