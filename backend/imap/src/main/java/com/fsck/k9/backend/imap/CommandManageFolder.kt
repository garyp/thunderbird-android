package com.fsck.k9.backend.imap

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderAlreadyExistsException
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.FolderOperationException
import com.fsck.k9.backend.api.createFolder
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter
import net.thunderbird.legacy.logging.Log

private const val TAG = "CommandManageFolder"

/**
 * Creates, renames, and deletes folders on the IMAP server and keeps the local folder list in sync.
 *
 * Subscriptions are updated on a best-effort basis. RFC 3501 (section 6.3.4/6.3.5) does not require servers to
 * update the subscription list when a mailbox is deleted or renamed, so we do it ourselves. Otherwise a new or
 * renamed folder wouldn't show up when the "subscribed folders only" setting is enabled.
 */
internal class CommandManageFolder(
    private val backendStorage: BackendStorage,
    private val imapStore: ImapStore,
    private val logger: Logger = Log,
) {
    @Throws(MessagingException::class)
    fun createFolder(folderServerId: String) {
        val folder = imapStore.getFolder(folderServerId)
        try {
            if (folder.exists()) {
                throw FolderAlreadyExistsException("Folder already exists on the server")
            }

            // https://www.rfc-editor.org/rfc/rfc9051.html#section-6.3.4
            if (!folder.create()) {
                throw FolderOperationException("Server refused to create the folder")
            }
        } finally {
            folder.close()
        }

        subscribeQuietly(folderServerId)

        if (folderServerId !in backendStorage.getFolderServerIds()) {
            backendStorage.updateFolders {
                createFolder(FolderInfo(serverId = folderServerId, name = folderServerId, type = FolderType.REGULAR))
            }
        }
    }

    @Throws(MessagingException::class)
    fun renameFolder(folderServerId: String, newFolderServerId: String, folderPathDelimiter: FolderPathDelimiter) {
        val folder = imapStore.getFolder(folderServerId)
        val newFolder = imapStore.getFolder(newFolderServerId)
        try {
            if (newFolder.exists()) {
                throw FolderAlreadyExistsException("A folder with the new name already exists on the server")
            }

            // https://www.rfc-editor.org/rfc/rfc9051.html#section-6.3.6
            folder.rename(newFolderServerId)
        } catch (e: ImapFolderOperationException) {
            throw FolderOperationException("Server refused to rename the folder", e.responseText, e)
        } finally {
            folder.close()
            newFolder.close()
        }

        // The server renames all subfolders along with the folder. Mirror that locally, so locally stored messages
        // and folder settings are retained.
        val renamedFolders = buildRenameMapping(folderServerId, newFolderServerId, folderPathDelimiter)
        backendStorage.updateFolders {
            for ((oldServerId, newServerId) in renamedFolders) {
                renameFolder(folderServerId = oldServerId, newFolderServerId = newServerId, name = newServerId)
            }
        }

        for ((oldServerId, newServerId) in renamedFolders) {
            moveSubscriptionQuietly(oldServerId, newServerId)
        }
    }

    @Throws(MessagingException::class)
    fun deleteFolder(folderServerId: String) {
        val folder = imapStore.getFolder(folderServerId)
        try {
            // https://www.rfc-editor.org/rfc/rfc9051.html#section-6.3.5
            folder.delete()
        } catch (e: ImapFolderOperationException) {
            // If the folder has already been deleted on the server (e.g. by another client) we still want to remove
            // it locally.
            if (folder.exists()) {
                throw FolderOperationException("Server refused to delete the folder", e.responseText, e)
            }
        } finally {
            folder.close()
        }

        unsubscribeQuietly(folderServerId)

        backendStorage.updateFolders {
            deleteFolders(listOf(folderServerId))
        }
    }

    private fun buildRenameMapping(
        folderServerId: String,
        newFolderServerId: String,
        folderPathDelimiter: FolderPathDelimiter,
    ): Map<String, String> {
        val subfolderPrefix = folderServerId + folderPathDelimiter
        val newSubfolderPrefix = newFolderServerId + folderPathDelimiter

        return buildMap {
            put(folderServerId, newFolderServerId)

            backendStorage.getFolderServerIds()
                .filter { it.startsWith(subfolderPrefix) }
                .forEach { serverId ->
                    put(serverId, newSubfolderPrefix + serverId.removePrefix(subfolderPrefix))
                }
        }
    }

    private fun subscribeQuietly(folderServerId: String) {
        val folder = imapStore.getFolder(folderServerId)
        try {
            folder.subscribe()
        } catch (e: MessagingException) {
            logger.warn(TAG, e) { "Failed to subscribe to folder" }
        } finally {
            folder.close()
        }
    }

    private fun unsubscribeQuietly(folderServerId: String): Boolean {
        val folder = imapStore.getFolder(folderServerId)
        return try {
            folder.unsubscribe()
            true
        } catch (e: MessagingException) {
            logger.verbose(TAG, e) { "Failed to unsubscribe from folder" }
            false
        } finally {
            folder.close()
        }
    }

    /**
     * Only subscribes to the new name if the old name was subscribed. Servers that already moved the subscription
     * (or don't track it) will reject the `UNSUBSCRIBE` command, and we leave the subscription list alone.
     */
    private fun moveSubscriptionQuietly(oldFolderServerId: String, newFolderServerId: String) {
        if (unsubscribeQuietly(oldFolderServerId)) {
            subscribeQuietly(newFolderServerId)
        }
    }
}
