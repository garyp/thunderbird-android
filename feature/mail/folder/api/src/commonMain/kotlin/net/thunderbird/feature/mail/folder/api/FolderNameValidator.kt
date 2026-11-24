package net.thunderbird.feature.mail.folder.api

/**
 * Reasons why a folder name entered by the user can't be used.
 */
public enum class FolderNameError {
    EMPTY,
    CONTAINS_DELIMITER,
    INVALID_CHARACTER,
    RESERVED_NAME,
}

/**
 * Validates the name of a single level of a folder path entered by the user, e.g. `2026` in `Work/2026`.
 */
public fun interface FolderNameValidator {
    /**
     * @param name The folder name without its parent path. Leading and trailing whitespace is ignored.
     * @param folderPathDelimiter The delimiter separating levels in the account's folder hierarchy.
     * @param isTopLevel Whether the folder is (or will be) at the top level of the folder hierarchy.
     * @return The reason why [name] can't be used, or `null` if it is valid.
     */
    public fun validate(name: String, folderPathDelimiter: FolderPathDelimiter, isTopLevel: Boolean): FolderNameError?
}
