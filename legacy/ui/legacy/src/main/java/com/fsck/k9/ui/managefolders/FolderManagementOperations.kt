package com.fsck.k9.ui.managefolders

import com.fsck.k9.backend.api.FolderAlreadyExistsException
import com.fsck.k9.backend.api.FolderOperationException
import com.fsck.k9.controller.MessagingController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderType
import net.thunderbird.feature.mail.folder.api.RemoteFolder
import net.thunderbird.feature.mail.folder.api.data.FolderError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderQueryRepository
import net.thunderbird.feature.mail.folder.api.data.repository.RemoteFolderQueryRepository
import net.thunderbird.legacy.logging.Log

sealed interface FolderOperationResult {
    data object Success : FolderOperationResult
    data class InvalidName(val error: FolderNameError) : FolderOperationResult
    data object AlreadyExists : FolderOperationResult
    data object HasSubfolders : FolderOperationResult
    data object NotAllowed : FolderOperationResult
    data object FolderNotFound : FolderOperationResult

    /**
     * The server refused the operation. [serverMessage] is untrusted text provided by the server.
     */
    data class ServerError(val serverMessage: String?) : FolderOperationResult
    data object Failed : FolderOperationResult
}

/**
 * Creates, renames, and deletes folders on the server, enforcing the rules the UI depends on:
 * - Special folders (Inbox, Drafts, Sent, …) can't be deleted.
 * - The Inbox can't be renamed.
 * - Folders with subfolders can't be deleted.
 * - Local-only folders (e.g. Outbox) can't be changed.
 */
class FolderManagementOperations(
    private val folderQueryRepository: FolderQueryRepository,
    private val remoteFolderQueryRepository: RemoteFolderQueryRepository,
    private val messagingController: MessagingController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun supportsFolderManagement(account: LegacyAccountDto): Boolean {
        return messagingController.supportsFolderManagement(account)
    }

    fun canCreateSubfolder(folder: Folder): Boolean = !folder.isLocalOnly

    fun canRename(folder: Folder): Boolean = !folder.isLocalOnly && folder.type != FolderType.INBOX

    fun canDelete(folder: Folder): Boolean = !folder.isLocalOnly && folder.type == FolderType.REGULAR

    suspend fun getFolderServerId(account: LegacyAccountDto, folderId: Long): String? {
        return withContext(ioDispatcher) {
            findFolderServerId(account, folderId)
        }
    }

    suspend fun createFolder(
        account: LegacyAccountDto,
        parentFolderId: Long?,
        name: String,
    ): FolderOperationResult = withContext(ioDispatcher) {
        val delimiter = account.folderPathDelimiter
        val parentServerId = if (parentFolderId != null) {
            val parentFolder = findFolder(account, parentFolderId)
                ?: return@withContext FolderOperationResult.FolderNotFound
            if (!canCreateSubfolder(parentFolder)) return@withContext FolderOperationResult.NotAllowed

            findFolderServerId(account, parentFolderId)
                ?: return@withContext FolderOperationResult.FolderNotFound
        } else {
            null
        }

        val trimmedName = name.trim()
        FolderNameValidator.validate(trimmedName, delimiter, isTopLevel = parentServerId == null)?.let { error ->
            return@withContext FolderOperationResult.InvalidName(error)
        }

        val serverId = FolderServerIdHelper.buildServerId(parentServerId, trimmedName, delimiter)
        if (folderExists(account, serverId)) {
            return@withContext FolderOperationResult.AlreadyExists
        }

        performOperation {
            messagingController.createFolderBlocking(account, serverId)
        }
    }

    suspend fun renameFolder(
        account: LegacyAccountDto,
        folderId: Long,
        newName: String,
    ): FolderOperationResult = withContext(ioDispatcher) {
        val delimiter = account.folderPathDelimiter
        val folder = findFolder(account, folderId)
            ?: return@withContext FolderOperationResult.FolderNotFound
        if (!canRename(folder)) return@withContext FolderOperationResult.NotAllowed

        val serverId = findFolderServerId(account, folderId)
            ?: return@withContext FolderOperationResult.FolderNotFound
        if (serverId.equals(INBOX, ignoreCase = true)) return@withContext FolderOperationResult.NotAllowed

        val parentServerId = FolderServerIdHelper.getParentServerId(serverId, delimiter)
        val trimmedName = newName.trim()
        FolderNameValidator.validate(trimmedName, delimiter, isTopLevel = parentServerId == null)?.let { error ->
            return@withContext FolderOperationResult.InvalidName(error)
        }

        val newServerId = FolderServerIdHelper.buildServerId(parentServerId, trimmedName, delimiter)
        if (newServerId == serverId) {
            return@withContext FolderOperationResult.Success
        }

        if (folderExists(account, newServerId)) {
            return@withContext FolderOperationResult.AlreadyExists
        }

        performOperation {
            messagingController.renameFolderBlocking(account, serverId, newServerId)
        }
    }

    suspend fun deleteFolder(
        account: LegacyAccountDto,
        folderId: Long,
    ): FolderOperationResult = withContext(ioDispatcher) {
        val folder = findFolder(account, folderId)
            ?: return@withContext FolderOperationResult.FolderNotFound
        if (!canDelete(folder)) return@withContext FolderOperationResult.NotAllowed

        val serverId = findFolderServerId(account, folderId)
            ?: return@withContext FolderOperationResult.FolderNotFound

        val hasSubfolders = getRemoteFolders(account).any { remoteFolder ->
            FolderServerIdHelper.isSubfolder(remoteFolder.serverId, serverId, account.folderPathDelimiter)
        }
        if (hasSubfolders) {
            return@withContext FolderOperationResult.HasSubfolders
        }

        performOperation {
            messagingController.deleteFolderBlocking(account, serverId)
        }
    }

    private suspend fun folderExists(account: LegacyAccountDto, serverId: String): Boolean {
        return getRemoteFolders(account).any { it.serverId == serverId }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun performOperation(block: () -> Unit): FolderOperationResult {
        return try {
            block()
            FolderOperationResult.Success
        } catch (e: FolderAlreadyExistsException) {
            Log.w(e, "Folder operation failed: folder already exists")
            FolderOperationResult.AlreadyExists
        } catch (e: FolderOperationException) {
            Log.w(e, "Folder operation was refused by the server")
            FolderOperationResult.ServerError(e.serverResponseText)
        } catch (e: MessagingException) {
            Log.e(e, "Folder operation failed")
            FolderOperationResult.Failed
        } catch (e: RuntimeException) {
            Log.e(e, "Folder operation failed")
            FolderOperationResult.Failed
        }
    }

    private suspend fun findFolder(account: LegacyAccountDto, folderId: Long): Folder? {
        return folderQueryRepository.findById(account.id, folderId).getOrThrow()
    }

    private suspend fun findFolderServerId(account: LegacyAccountDto, folderId: Long): String? {
        return folderQueryRepository.findFolderServerIdById(account.id, folderId).getOrThrow()?.serverId
    }

    private suspend fun getRemoteFolders(account: LegacyAccountDto): List<RemoteFolder> {
        return remoteFolderQueryRepository.getAllByAccountId(account.id).getOrThrow()
    }

    private fun <T> Outcome<T, FolderError>.getOrThrow(): T {
        return fold(
            onSuccess = { it },
            onFailure = { error -> throw FolderQueryException(error) },
        )
    }

    private class FolderQueryException(error: FolderError) :
        MessagingException("Failed to query folders: ${error::class.simpleName}", error.throwable)

    private companion object {
        const val INBOX = "INBOX"
    }
}
