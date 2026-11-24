package net.thunderbird.app.common.feature.mail.folder

import com.fsck.k9.controller.MessagingController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.account.AccountId

/**
 * Requests a refresh of an account's local folder list from the server.
 */
internal fun interface FolderListRefresher {
    /**
     * Refreshes the folder list and returns when the refresh has finished. Failures are ignored.
     */
    suspend fun refresh(accountId: AccountId)
}

internal class MessagingControllerFolderListRefresher(
    private val accountManager: LegacyAccountDtoManager,
    private val messagingController: MessagingController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderListRefresher {
    override suspend fun refresh(accountId: AccountId) = withContext(ioDispatcher) {
        val account = accountManager.getAccount(accountId.toString()) ?: return@withContext
        messagingController.refreshFolderListBlocking(account)
    }
}
