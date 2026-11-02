package com.fsck.k9.ui.managefolders

import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

private const val INBOX = "INBOX"

enum class FolderNameError {
    EMPTY,
    CONTAINS_DELIMITER,
    INVALID_CHARACTER,
    RESERVED_NAME,
}

/**
 * Validates the name of a single level of a folder path entered by the user.
 *
 * See RFC 3501, section 5.1 and RFC 9051, section 5.1:
 * - The hierarchy delimiter separates levels, so it can't be part of a folder name.
 * - `%` and `*` are wildcards in `LIST` commands and shouldn't be used in mailbox names.
 * - Control characters (e.g. CR, LF, NUL) aren't allowed in mailbox names.
 * - `INBOX` is a reserved, case-insensitive name at the top level of the hierarchy.
 */
object FolderNameValidator {
    fun validate(name: String, folderPathDelimiter: FolderPathDelimiter, isTopLevel: Boolean): FolderNameError? {
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

/**
 * Helpers to work with the hierarchical server ID of a folder, e.g. `Parent/Child`.
 */
internal object FolderServerIdHelper {
    fun buildServerId(parentServerId: String?, name: String, folderPathDelimiter: FolderPathDelimiter): String {
        return if (parentServerId == null) name else "$parentServerId$folderPathDelimiter$name"
    }

    fun getParentServerId(serverId: String, folderPathDelimiter: FolderPathDelimiter): String? {
        if (folderPathDelimiter.isEmpty()) return null

        val index = serverId.lastIndexOf(folderPathDelimiter)
        return if (index > 0) serverId.substring(0, index) else null
    }

    fun getName(serverId: String, folderPathDelimiter: FolderPathDelimiter): String {
        val parentServerId = getParentServerId(serverId, folderPathDelimiter) ?: return serverId
        return serverId.substring(parentServerId.length + folderPathDelimiter.length)
    }

    fun isSubfolder(serverId: String, parentServerId: String, folderPathDelimiter: FolderPathDelimiter): Boolean {
        return folderPathDelimiter.isNotEmpty() && serverId.startsWith("$parentServerId$folderPathDelimiter")
    }
}
