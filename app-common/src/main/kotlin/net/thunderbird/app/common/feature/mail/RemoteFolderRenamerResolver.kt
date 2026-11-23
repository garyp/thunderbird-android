package net.thunderbird.app.common.feature.mail

import com.fsck.k9.mail.folders.FolderServerId
import kotlinx.coroutines.flow.firstOrNull
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.backend.imap.ImapRemoteFolderRenamerFactory
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * Resolves the correct [RemoteFolderRenamer] implementation based on the account's incoming server protocol.
 *
 * @param developmentFactories Factories for development backends (e.g. `demo`), keyed by protocol.
 */
internal class RemoteFolderRenamerResolver(
    private val accountManager: LegacyAccountManager,
    private val imapFactory: ImapRemoteFolderRenamerFactory,
    private val developmentFactories: Map<String, RemoteFolderRenamer.Factory> = emptyMap(),
) : RemoteFolderRenamer.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderRenamer {
        val account = accountManager.getById(accountId).firstOrNull()
            ?: error("Account not found: $accountId")

        val protocol = account.incomingServerSettings.type
        return when (protocol) {
            Protocols.IMAP -> imapFactory.create(accountId)
            in developmentFactories -> developmentFactories.getValue(protocol).create(accountId)
            else -> NoOpRemoteFolderRenamer
        }
    }
}

/**
 * A [RemoteFolderRenamer] for accounts that don't support renaming folders.
 */
internal object NoOpRemoteFolderRenamer : RemoteFolderRenamer {
    override suspend fun rename(
        folderServerId: FolderServerId,
        newFolderServerId: FolderServerId,
        folderPathDelimiter: FolderPathDelimiter,
    ): Outcome<FolderServerId, RemoteFolderOperationError> {
        return Outcome.failure(RemoteFolderOperationError.NotSupportedOperation)
    }
}
