package net.thunderbird.feature.mail.folder.api

import net.thunderbird.piisafe.annotation.PiiSafe

/**
 * Describes which folder management operations are available for a folder.
 *
 * @property name The name of the folder without its parent path, e.g. `2026` for `Work/2026`.
 * @property isTopLevel Whether the folder is at the top level of the folder hierarchy.
 * @property folderPathDelimiter The delimiter separating levels in the account's folder hierarchy.
 */
@PiiSafe.HasPii
public data class FolderManagementOptions(
    @get:PiiSafe.Mask
    val name: String,
    val isTopLevel: Boolean,
    val folderPathDelimiter: FolderPathDelimiter,
    val canCreateSubfolder: Boolean,
    val canRename: Boolean,
    val canDelete: Boolean,
)
