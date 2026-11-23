package net.thunderbird.backend.imap

import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

private const val TAG = "ImapRemoteFolderRenamer"

/**
 * Renames a folder using the IMAP `RENAME` command (RFC 9051, section 6.3.6), which also renames all subfolders.
 *
 * Subscriptions of the folder and its subfolders are moved to the new names on a best-effort basis.
 */
class ImapRemoteFolderRenamer(
    private val logger: Logger,
    private val imapStore: ImapStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteFolderRenamer {
    private val subscriptions = ImapFolderSubscriptions(logger, imapStore)

    override suspend fun rename(
        folderServerId: FolderServerId,
        newFolderServerId: FolderServerId,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<Unit, RemoteFolderOperationError> = withContext(ioDispatcher) {
        val oldName = folderServerId.serverId
        val newName = newFolderServerId.serverId

        try {
            // Servers keep subscriptions of names that no longer exist, so check them before renaming.
            val subscribedFolders = findFolderAndSubfolders(oldName, folderPathDelimiter)
                .filter { subscriptions.isSubscribed(it) }

            val folder = imapStore.getFolder(oldName)
            val newFolder = imapStore.getFolder(newName)
            try {
                if (newFolder.exists()) {
                    return@withContext Outcome.failure(RemoteFolderOperationError.AlreadyExists)
                }

                folder.rename(newName)
            } finally {
                folder.close()
                newFolder.close()
            }

            for (subscribedFolder in subscribedFolders) {
                subscriptions.unsubscribe(subscribedFolder)
                subscriptions.subscribe(newName + subscribedFolder.removePrefix(oldName))
            }

            Outcome.success(Unit)
        } catch (e: ImapFolderOperationException) {
            logger.warn(TAG) { "Server refused to rename folder" }
            Outcome.failure(RemoteFolderOperationError.RejectedByServer(e.responseText))
        } catch (e: MessagingException) {
            logger.error(TAG) { "Failed to rename folder: ${e::class.simpleName}" }
            Outcome.failure(RemoteFolderOperationError.Failed(reason = e::class.simpleName.orEmpty()))
        }
    }

    /**
     * Returns the server IDs (without namespace prefix) of the folder and its subfolders that exist on the server.
     */
    private fun findFolderAndSubfolders(
        folderServerId: String,
        folderPathDelimiter: FolderPathDelimiter,
    ): List<String> {
        val prefix = imapStore.combinedPrefix.orEmpty()
        val subfolderPrefix = folderServerId + folderPathDelimiter

        return imapStore.getFolders()
            .map { it.serverId.removePrefix(prefix) }
            .filter { it == folderServerId || it.startsWith(subfolderPrefix) }
    }
}

interface ImapRemoteFolderRenamerFactory : RemoteFolderRenamer.Factory

class DefaultImapRemoteFolderRenamerFactory(
    private val logger: Logger,
    private val backendFactory: BackendFactory,
) : ImapRemoteFolderRenamerFactory {
    override suspend fun create(accountId: AccountId): RemoteFolderRenamer {
        val backend = backendFactory.createBackend(accountId) as ImapBackend
        return ImapRemoteFolderRenamer(
            logger = logger,
            imapStore = backend.imapStore,
            ioDispatcher = Dispatchers.IO,
        )
    }
}
