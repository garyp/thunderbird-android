package net.thunderbird.app.common.feature.mail

import com.fsck.k9.mail.folders.FolderServerId
import kotlinx.coroutines.flow.firstOrNull
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.imap.ImapRemoteFolderDeleterFactory
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.feature.account.AccountId

/**
 * Resolves the correct [RemoteFolderDeleter] implementation based on the account's incoming server protocol.
 *
 * @param developmentFactories Factories for development backends (e.g. `demo`), keyed by protocol.
 */
internal class RemoteFolderDeleterResolver(
    private val accountManager: LegacyAccountManager,
    private val imapFactory: ImapRemoteFolderDeleterFactory,
    private val developmentFactories: Map<String, RemoteFolderDeleter.Factory> = emptyMap(),
) : RemoteFolderDeleter.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderDeleter {
        val account = accountManager.getById(accountId).firstOrNull()
            ?: error("Account not found: $accountId")

        val protocol = account.incomingServerSettings.type
        return when (protocol) {
            Protocols.IMAP -> imapFactory.create(accountId)
            in developmentFactories -> developmentFactories.getValue(protocol).create(accountId)
            else -> NoOpRemoteFolderDeleter
        }
    }
}

/**
 * A [RemoteFolderDeleter] for accounts that don't support deleting folders.
 */
internal object NoOpRemoteFolderDeleter : RemoteFolderDeleter {
    override suspend fun delete(folderServerId: FolderServerId): Outcome<Unit, RemoteFolderOperationError> {
        return Outcome.failure(RemoteFolderOperationError.NotSupportedOperation)
    }
}
