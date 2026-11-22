package app.k9mail.backend.demo

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderAlreadyExistsException
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.FolderOperationException
import com.fsck.k9.backend.api.createFolder
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import net.thunderbird.feature.mail.folder.api.FOLDER_DEFAULT_PATH_DELIMITER
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * Creates, renames, and deletes folders in the [DemoStore] and keeps the local folder list in sync.
 *
 * It mirrors the behavior of the IMAP backend, including refusing operations an IMAP server would refuse.
 */
internal class CommandManageFolder(
    private val backendStorage: BackendStorage,
    private val demoStore: DemoStore,
) {
    fun createFolder(folderServerId: String) {
        if (demoStore.getFolder(folderServerId) != null) {
            throw FolderAlreadyExistsException("Folder already exists on the server")
        }

        demoStore.addFolder(
            folderServerId = folderServerId,
            folder = DemoStoreFolder(
                name = folderServerId,
                type = FolderType.REGULAR,
                messageServerIds = emptyList(),
                resourcePath = null,
            ),
        )

        backendStorage.updateFolders {
            createFolder(FolderInfo(serverId = folderServerId, name = folderServerId, type = FolderType.REGULAR))
        }
    }

    fun renameFolder(folderServerId: String, newFolderServerId: String, folderPathDelimiter: FolderPathDelimiter) {
        val folder = demoStore.getFolder(folderServerId)
            ?: throw FolderOperationException("Folder doesn't exist", serverResponseText = "Mailbox doesn't exist")
        checkRenameAllowed(folder, newFolderServerId)

        val newName = buildNewName(folder.name, newFolderServerId, folderPathDelimiter)

        // Like an IMAP server, rename all subfolders along with the folder.
        val subfolderPrefix = folderServerId + folderPathDelimiter
        val renamedFolders = buildMap {
            put(folderServerId, newFolderServerId to newName)

            for (serverId in demoStore.getFolderIds().filter { it.startsWith(subfolderPrefix) }) {
                val subfolder = demoStore.getFolder(serverId) ?: continue
                val newSubfolderServerId = newFolderServerId + serverId.removePrefix(folderServerId)
                val newSubfolderName = newName + subfolder.name.removePrefix(folder.name)
                put(serverId, newSubfolderServerId to newSubfolderName)
            }
        }

        demoStore.renameFolders(renamedFolders)

        backendStorage.updateFolders {
            for ((oldServerId, newServerIdAndName) in renamedFolders) {
                val (newServerId, name) = newServerIdAndName
                renameFolder(folderServerId = oldServerId, newFolderServerId = newServerId, name = name)
            }
        }
    }

    fun deleteFolder(folderServerId: String) {
        val folder = demoStore.getFolder(folderServerId)

        if (folder != null) {
            if (folder.type == FolderType.INBOX) {
                throw FolderOperationException("INBOX can't be deleted", serverResponseText = "Can't delete INBOX")
            }

            val hasSubfolders = demoStore.getFolderIds().any {
                it.startsWith(folderServerId + FOLDER_DEFAULT_PATH_DELIMITER)
            }
            if (hasSubfolders) {
                throw FolderOperationException(
                    "Folder has subfolders",
                    serverResponseText = "Mailbox has inferior hierarchical names",
                )
            }

            demoStore.removeFolder(folderServerId)
        }

        // Like the IMAP backend, remove a folder that no longer exists on the "server" locally as well.
        backendStorage.updateFolders {
            deleteFolders(listOf(folderServerId))
        }
    }

    private fun checkRenameAllowed(folder: DemoStoreFolder, newFolderServerId: String) {
        if (folder.type == FolderType.INBOX) {
            throw FolderOperationException("Renaming INBOX is not supported")
        }

        if (demoStore.getFolder(newFolderServerId) != null) {
            throw FolderAlreadyExistsException("A folder with the new name already exists on the server")
        }
    }

    /**
     * Keeps the display name of the parent (e.g. "Archive" instead of the server ID "archive") and replaces the last
     * path segment with the new name chosen by the user.
     */
    private fun buildNewName(
        currentName: String,
        newFolderServerId: String,
        folderPathDelimiter: FolderPathDelimiter,
    ): String {
        val newLeafName = newFolderServerId.substringAfterLast(folderPathDelimiter)
        val parentName = currentName.substringBeforeLast(folderPathDelimiter, missingDelimiterValue = "")
        return if (parentName.isEmpty()) newLeafName else "$parentName$folderPathDelimiter$newLeafName"
    }
}
