package net.thunderbird.app.common.feature.mail.folder

import com.fsck.k9.controller.MessagingController
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.account.AccountId

/**
 * Requests a refresh of an account's local folder list from the server.
 */
internal fun interface FolderListRefresher {
    /**
     * Requests the refresh. It runs in the background, so it may not have finished when this function returns.
     */
    fun refresh(accountId: AccountId)
}

internal class MessagingControllerFolderListRefresher(
    private val accountManager: LegacyAccountDtoManager,
    private val messagingController: MessagingController,
) : FolderListRefresher {
    override fun refresh(accountId: AccountId) {
        val account = accountManager.getAccount(accountId.toString()) ?: return
        messagingController.refreshFolderList(account)
    }
}
