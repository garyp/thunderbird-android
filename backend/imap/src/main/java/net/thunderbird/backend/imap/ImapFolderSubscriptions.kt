package net.thunderbird.backend.imap

import com.fsck.k9.mail.store.imap.ImapStore
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger

private const val TAG = "ImapFolderSubscriptions"

/**
 * Best-effort updates of the server's set of subscribed folders.
 *
 * RFC 9051 (sections 6.3.4–6.3.6) doesn't require servers to update subscriptions when a folder is created, renamed,
 * or deleted. Without a subscription, a folder isn't shown when the "subscribed folders only" setting is enabled.
 *
 * Failures are logged without the exception, because IMAP exceptions contain the command, including folder names.
 */
internal class ImapFolderSubscriptions(
    private val logger: Logger,
    private val imapStore: ImapStore,
) {
    fun subscribe(folderServerId: String) {
        val folder = imapStore.getFolder(folderServerId)
        try {
            folder.subscribe()
        } catch (e: MessagingException) {
            logger.warn(TAG) { "Failed to subscribe to folder: ${e::class.simpleName}" }
        } finally {
            folder.close()
        }
    }

    fun unsubscribe(folderServerId: String) {
        val folder = imapStore.getFolder(folderServerId)
        try {
            folder.unsubscribe()
        } catch (e: MessagingException) {
            logger.verbose(TAG) { "Failed to unsubscribe from folder: ${e::class.simpleName}" }
        } finally {
            folder.close()
        }
    }

    fun isSubscribed(folderServerId: String): Boolean {
        val folder = imapStore.getFolder(folderServerId)
        return try {
            folder.isSubscribed()
        } catch (e: MessagingException) {
            logger.verbose(TAG) { "Failed to check folder subscription: ${e::class.simpleName}" }
            false
        } finally {
            folder.close()
        }
    }
}
