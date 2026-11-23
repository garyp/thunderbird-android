package com.fsck.k9.ui.managefolders

import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderManagementOptions
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderManagementRepository

internal class FakeFolderManagementRepository(
    var isSupported: Boolean = true,
    var options: FolderManagementOptions? = null,
    var outcome: Outcome<Unit, FolderManagementError> = Outcome.success(Unit),
) : FolderManagementRepository {
    val createCalls = mutableListOf<CreateCall>()
    val renameCalls = mutableListOf<RenameCall>()
    val deleteCalls = mutableListOf<DeleteCall>()

    override suspend fun isSupported(accountId: AccountId): Boolean = isSupported

    override suspend fun getOptions(
        accountId: AccountId,
        folderId: Long,
    ): Outcome<FolderManagementOptions, FolderManagementError> {
        return options?.let { Outcome.success(it) } ?: Outcome.failure(FolderManagementError.FolderNotFound)
    }

    override suspend fun create(
        accountId: AccountId,
        parentFolderId: Long?,
        name: String,
    ): Outcome<Unit, FolderManagementError> {
        createCalls.add(CreateCall(accountId, parentFolderId, name))
        return outcome
    }

    override suspend fun rename(
        accountId: AccountId,
        folderId: Long,
        newName: String,
    ): Outcome<Unit, FolderManagementError> {
        renameCalls.add(RenameCall(accountId, folderId, newName))
        return outcome
    }

    override suspend fun delete(accountId: AccountId, folderId: Long): Outcome<Unit, FolderManagementError> {
        deleteCalls.add(DeleteCall(accountId, folderId))
        return outcome
    }

    data class CreateCall(val accountId: AccountId, val parentFolderId: Long?, val name: String)
    data class RenameCall(val accountId: AccountId, val folderId: Long, val newName: String)
    data class DeleteCall(val accountId: AccountId, val folderId: Long)
}
