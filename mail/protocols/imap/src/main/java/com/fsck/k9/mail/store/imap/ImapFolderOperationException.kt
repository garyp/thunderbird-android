package com.fsck.k9.mail.store.imap

import net.thunderbird.core.common.exception.MessagingException

/**
 * Thrown when the IMAP server rejects a mailbox management command (`DELETE`, `RENAME`, `SUBSCRIBE`, `UNSUBSCRIBE`)
 * with a tagged `NO` or `BAD` response.
 *
 * @property responseText The human-readable text of the server's response, if any. This is server-provided
 *   (untrusted) text and should only be displayed, never interpreted.
 */
class ImapFolderOperationException(
    message: String,
    val responseText: String?,
    cause: Throwable? = null,
) : MessagingException(message, isPermanentFailure = true, cause = cause)
