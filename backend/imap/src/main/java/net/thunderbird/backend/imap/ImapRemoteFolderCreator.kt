package net.thunderbird.backend.imap

import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId

class ImapRemoteFolderCreator(
    private val logger: Logger,
    private val imapStore: ImapStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteFolderCreator {
    private val subscriptions = ImapFolderSubscriptions(logger, imapStore)

    override suspend fun create(
        folderServerId: FolderServerId,
        mustCreate: Boolean,
        folderType: FolderType,
    ): Outcome<RemoteFolderCreationOutcome.Success, RemoteFolderCreationOutcome.Error> = withContext(ioDispatcher) {
        val remoteFolder = imapStore.getFolder(name = folderServerId.serverId)
        val outcome = try {
            val folderExists = remoteFolder.exists()
            when {
                folderExists && mustCreate -> Outcome.failure(
                    RemoteFolderCreationOutcome.Error.AlreadyExists,
                )

                folderExists -> Outcome.success(RemoteFolderCreationOutcome.Success.AlreadyExists)

                !folderExists && remoteFolder.create(folderType = folderType) -> {
                    // Without a subscription the new folder isn't listed when "subscribed folders only" is enabled.
                    subscriptions.subscribe(folderServerId.serverId)
                    Outcome.success(RemoteFolderCreationOutcome.Success.Created)
                }

                else -> Outcome.failure(
                    RemoteFolderCreationOutcome.Error.FailedToCreateRemoteFolder(
                        reason = "Failed to create folder on remote server.",
                    ),
                )
            }
        } catch (e: ImapFolderOperationException) {
            logger.warn(message = { "Server refused to create remote folder" })
            Outcome.failure(RemoteFolderCreationOutcome.Error.RejectedByServer(serverMessage = e.responseText))
        } catch (e: MessagingException) {
            logger.error(message = { "Failed to create remote folder '${folderServerId.serverId}'" }, throwable = e)
            Outcome.failure(
                RemoteFolderCreationOutcome.Error.FailedToCreateRemoteFolder(
                    reason = e.message ?: "Unhandled exception. Please check the logs.",
                ),
            )
        } finally {
            remoteFolder.close()
        }

        outcome
    }
}

interface ImapRemoteFolderCreatorFactory : RemoteFolderCreator.Factory

class DefaultImapRemoteFolderCreatorFactory(
    private val logger: Logger,
    private val backendFactory: BackendFactory,
) : ImapRemoteFolderCreatorFactory {
    override suspend fun create(accountId: AccountId): RemoteFolderCreator {
        val backend = backendFactory.createBackend(accountId) as ImapBackend
        return ImapRemoteFolderCreator(
            logger = logger,
            imapStore = backend.imapStore,
            ioDispatcher = Dispatchers.IO,
        )
    }
}
