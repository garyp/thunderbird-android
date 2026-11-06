package com.fsck.k9.backend.api

import net.thunderbird.core.common.exception.MessagingException

/**
 * Thrown when a folder management operation (create, rename, delete) fails because the server refused it.
 *
 * @property serverResponseText Human-readable explanation provided by the server, if any. This is untrusted,
 *   server-provided text that may only be displayed to the user.
 */
open class FolderOperationException(
    message: String,
    val serverResponseText: String? = null,
    cause: Throwable? = null,
) : MessagingException(message, isPermanentFailure = true, cause = cause)

/**
 * Thrown when trying to create a folder, or rename a folder to a name, that already exists on the server.
 */
class FolderAlreadyExistsException(
    message: String,
) : FolderOperationException(message)
