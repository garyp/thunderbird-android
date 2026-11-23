package net.thunderbird.backend.imap

import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId

private const val TAG = "ImapRemoteFolderDeleter"

/**
 * Deletes a folder using the IMAP `DELETE` command (RFC 9051, section 6.3.5) and removes its subscription on a
 * best-effort basis.
 */
class ImapRemoteFolderDeleter(
    private val logger: Logger,
    private val imapStore: ImapStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteFolderDeleter {
    private val subscriptions = ImapFolderSubscriptions(logger, imapStore)

    override suspend fun delete(
        folderServerId: FolderServerId,
    ): Outcome<Unit, RemoteFolderOperationError> = withContext(ioDispatcher) {
        val name = folderServerId.serverId
        val folder = imapStore.getFolder(name)
        try {
            // The local folder list may not contain all subfolders, e.g. unsubscribed ones. Many servers would delete
            // the folder's messages but keep it as a non-selectable parent (RFC 9051, section 6.3.5).
            if (folder.hasSubfolders()) {
                return@withContext Outcome.failure(RemoteFolderOperationError.HasSubfolders)
            }

            try {
                folder.delete()
            } catch (e: ImapFolderOperationException) {
                // A folder that was already deleted on the server (e.g. by another client) counts as deleted.
                if (folder.isListed()) {
                    logger.warn(TAG) { "Server refused to delete folder" }
                    return@withContext Outcome.failure(RemoteFolderOperationError.RejectedByServer(e.responseText))
                }
            }

            subscriptions.unsubscribe(name)

            Outcome.success(Unit)
        } catch (e: MessagingException) {
            logger.error(TAG) { "Failed to delete folder: ${e::class.simpleName}" }
            Outcome.failure(RemoteFolderOperationError.Failed(reason = e::class.simpleName.orEmpty()))
        } finally {
            folder.close()
        }
    }
}

interface ImapRemoteFolderDeleterFactory : RemoteFolderDeleter.Factory

class DefaultImapRemoteFolderDeleterFactory(
    private val logger: Logger,
    private val backendFactory: BackendFactory,
) : ImapRemoteFolderDeleterFactory {
    override suspend fun create(accountId: AccountId): RemoteFolderDeleter {
        val backend = backendFactory.createBackend(accountId) as ImapBackend
        return ImapRemoteFolderDeleter(
            logger = logger,
            imapStore = backend.imapStore,
            ioDispatcher = Dispatchers.IO,
        )
    }
}
