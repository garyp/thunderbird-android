package net.thunderbird.feature.mail.folder.api.data.repository

import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderManagementOptions
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError

/**
 * Creates, renames, and deletes folders on the server and keeps the local folder list in sync.
 *
 * Implementations enforce these rules:
 * - Special folders (Inbox, Drafts, Sent, …) can't be deleted.
 * - The Inbox can't be renamed.
 * - Folders with subfolders can't be deleted.
 * - Local-only folders (e.g. Outbox) can't be changed.
 */
public interface FolderManagementRepository {
    /**
     * Returns whether the account supports creating, renaming, and deleting folders.
     */
    public suspend fun isSupported(accountId: AccountId): Boolean

    /**
     * Returns which operations are available for the folder with the given [folderId].
     */
    public suspend fun getOptions(
        accountId: AccountId,
        folderId: Long,
    ): Outcome<FolderManagementOptions, FolderManagementError>

    /**
     * Creates a folder named [name] below the folder with the ID [parentFolderId], or at the top level if
     * [parentFolderId] is `null`.
     */
    public suspend fun create(
        accountId: AccountId,
        parentFolderId: Long?,
        name: String,
    ): Outcome<Unit, FolderManagementError>

    /**
     * Renames the folder with the given [folderId] to [newName], keeping it at the same level of the hierarchy.
     * Subfolders are renamed along with it.
     */
    public suspend fun rename(
        accountId: AccountId,
        folderId: Long,
        newName: String,
    ): Outcome<Unit, FolderManagementError>

    /**
     * Deletes the folder with the given [folderId], including locally stored messages.
     */
    public suspend fun delete(
        accountId: AccountId,
        folderId: Long,
    ): Outcome<Unit, FolderManagementError>
}
