package net.thunderbird.app.common.feature.mail.folder

import net.thunderbird.feature.mail.folder.api.FolderNameError
import net.thunderbird.feature.mail.folder.api.FolderNameValidator
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

private const val INBOX = "INBOX"

/**
 * Validates folder names according to RFC 3501, section 5.1 and RFC 9051, section 5.1:
 * - The hierarchy delimiter separates levels, so it can't be part of a folder name.
 * - `%` and `*` are wildcards in `LIST` commands and shouldn't be used in mailbox names.
 * - Control characters (e.g. CR, LF, NUL) aren't allowed in mailbox names.
 * - `INBOX` is a reserved, case-insensitive name at the top level of the hierarchy.
 */
internal class DefaultFolderNameValidator : FolderNameValidator {
    override fun validate(
        name: String,
        folderPathDelimiter: FolderPathDelimiter,
        isTopLevel: Boolean,
    ): FolderNameError? {
        val trimmedName = name.trim()

        return when {
            trimmedName.isEmpty() -> FolderNameError.EMPTY

            folderPathDelimiter.isNotEmpty() && folderPathDelimiter in trimmedName -> {
                FolderNameError.CONTAINS_DELIMITER
            }

            trimmedName.any { it == '%' || it == '*' || it.isISOControl() } -> FolderNameError.INVALID_CHARACTER

            isTopLevel && trimmedName.equals(INBOX, ignoreCase = true) -> FolderNameError.RESERVED_NAME

            else -> null
        }
    }
}
