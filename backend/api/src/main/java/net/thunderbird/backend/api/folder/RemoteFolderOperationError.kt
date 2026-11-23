package net.thunderbird.backend.api.folder

/**
 * Errors of remote folder operations, like renaming or deleting a folder on the server.
 */
sealed interface RemoteFolderOperationError {
    /**
     * A folder with the target name already exists on the server.
     */
    data object AlreadyExists : RemoteFolderOperationError

    /**
     * The server refused the operation.
     *
     * @param serverMessage The server's explanation, if any. This is untrusted, server-provided text that may only be
     *   displayed to the user.
     */
    data class RejectedByServer(
        val serverMessage: String?,
    ) : RemoteFolderOperationError

    /**
     * The operation couldn't be completed, e.g. because of a network error.
     *
     * @param reason A technical description for logging. Must not be shown to the user.
     */
    data class Failed(
        val reason: String,
    ) : RemoteFolderOperationError

    /**
     * The account's server doesn't support this operation.
     */
    data object NotSupportedOperation : RemoteFolderOperationError
}
