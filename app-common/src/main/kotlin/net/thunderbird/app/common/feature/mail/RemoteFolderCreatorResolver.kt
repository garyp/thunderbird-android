package net.thunderbird.app.common.feature.mail

import kotlinx.coroutines.flow.firstOrNull
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.imap.ImapRemoteFolderCreatorFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.account.api.BaseAccount

/**
 * Resolves the correct [RemoteFolderCreator] implementation based on the [BaseAccount] type.
 *
 * @param developmentFactories Factories for development backends (e.g. `demo`), keyed by protocol.
 */
class RemoteFolderCreatorResolver(
    private val accountManager: LegacyAccountManager,
    private val imapFactory: ImapRemoteFolderCreatorFactory,
    private val developmentFactories: Map<String, RemoteFolderCreator.Factory> = emptyMap(),
) : RemoteFolderCreator.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderCreator {
        val account = accountManager.getById(accountId).firstOrNull()
            ?: error("Account not found: $accountId")

        val protocol = account.incomingServerSettings.type
        return when (protocol) {
            Protocols.IMAP -> imapFactory.create(accountId)
            in developmentFactories -> developmentFactories.getValue(protocol).create(accountId)
            else -> NoOpRemoteFolderCreator
        }
    }
}
