package net.thunderbird.app.common.feature.mail.folder

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderManagementOptions
import net.thunderbird.feature.mail.folder.api.FolderNameValidator
import net.thunderbird.feature.mail.folder.api.data.FolderError
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderManagementRepository
import net.thunderbird.feature.mail.folder.api.data.repository.FolderQueryRepository
import net.thunderbird.feature.mail.folder.api.data.repository.RemoteFolderQueryRepository

private const val TAG = "FolderManagementRepository"
private const val INBOX = "INBOX"

/**
 * Bridges [FolderManagementRepository] to the legacy account and folder storage.
 *
 * @param supportedProtocols The incoming server protocols (e.g. `imap`) of accounts that support folder management.
 */
@Suppress("TooManyFunctions")
internal class DefaultFolderManagementRepository(
    private val accountManager: LegacyAccountManager,
    private val folderQueryRepository: FolderQueryRepository,
    private val remoteFolderQueryRepository: RemoteFolderQueryRepository,
    private val folderHierarchyUpdater: FolderHierarchyUpdater,
    private val folderNameValidator: FolderNameValidator,
    private val supportedProtocols: Set<String>,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderManagementRepository {

    override suspend fun isSupported(accountId: AccountId): Boolean {
        val account = withContext(ioDispatcher) { accountManager.getById(accountId).firstOrNull() }
        return account != null && account.incomingServerSettings.type in supportedProtocols
    }

    override suspend fun getOptions(
        accountId: AccountId,
        folderId: Long,
    ): Outcome<FolderManagementOptions, FolderManagementError> = performOperation(accountId) { account ->
        val folder = findFolder(accountId, folderId) ?: return@performOperation notFound()
        val serverId = findFolderServerId(accountId, folderId) ?: return@performOperation notFound()
        val delimiter = account.folderPathDelimiter

        Outcome.success(
            FolderManagementOptions(
                name = FolderServerIdHelper.getName(serverId, delimiter),
                isTopLevel = FolderServerIdHelper.getParentServerId(serverId, delimiter) == null,
                folderPathDelimiter = delimiter,
                canCreateSubfolder = canCreateSubfolder(folder),
                canRename = canRename(folder, serverId),
                canDelete = canDelete(folder),
            ),
        )
    }

    override suspend fun create(
        accountId: AccountId,
        parentFolderId: Long?,
        name: String,
    ): Outcome<Unit, FolderManagementError> = performOperation(accountId) { account ->
        val delimiter = account.folderPathDelimiter
        val parentServerId = parentFolderId?.let { folderId ->
            val parentFolder = findFolder(accountId, folderId) ?: return@performOperation notFound()
            if (!canCreateSubfolder(parentFolder)) return@performOperation notAllowed()

            findFolderServerId(accountId, folderId) ?: return@performOperation notFound()
        }

        val trimmedName = name.trim()
        folderNameValidator.validate(trimmedName, delimiter, isTopLevel = parentServerId == null)?.let { error ->
            return@performOperation Outcome.failure(FolderManagementError.InvalidName(error))
        }

        val serverId = FolderServerIdHelper.buildServerId(parentServerId, trimmedName, delimiter)
        if (folderExists(accountId, serverId)) {
            return@performOperation Outcome.failure(FolderManagementError.AlreadyExists)
        }

        folderHierarchyUpdater.create(accountId, serverId)
    }

    override suspend fun rename(
        accountId: AccountId,
        folderId: Long,
        newName: String,
    ): Outcome<Unit, FolderManagementError> = performOperation(accountId) { account ->
        val delimiter = account.folderPathDelimiter
        val folder = findFolder(accountId, folderId) ?: return@performOperation notFound()
        val serverId = findFolderServerId(accountId, folderId) ?: return@performOperation notFound()
        if (!canRename(folder, serverId)) return@performOperation notAllowed()

        val parentServerId = FolderServerIdHelper.getParentServerId(serverId, delimiter)
        val trimmedName = newName.trim()
        folderNameValidator.validate(trimmedName, delimiter, isTopLevel = parentServerId == null)?.let { error ->
            return@performOperation Outcome.failure(FolderManagementError.InvalidName(error))
        }

        val newServerId = FolderServerIdHelper.buildServerId(parentServerId, trimmedName, delimiter)
        when {
            newServerId == serverId -> Outcome.success(Unit)
            folderExists(accountId, newServerId) -> Outcome.failure(FolderManagementError.AlreadyExists)
            else -> folderHierarchyUpdater.rename(accountId, serverId, newServerId, delimiter)
        }
    }

    override suspend fun delete(
        accountId: AccountId,
        folderId: Long,
    ): Outcome<Unit, FolderManagementError> = performOperation(accountId) { account ->
        val folder = findFolder(accountId, folderId) ?: return@performOperation notFound()
        if (!canDelete(folder)) return@performOperation notAllowed()

        val serverId = findFolderServerId(accountId, folderId) ?: return@performOperation notFound()
        val hasSubfolders = getRemoteFolderServerIds(accountId).any { remoteServerId ->
            FolderServerIdHelper.isSubfolder(remoteServerId, serverId, account.folderPathDelimiter)
        }

        if (hasSubfolders) {
            Outcome.failure(FolderManagementError.HasSubfolders)
        } else {
            folderHierarchyUpdater.delete(accountId, serverId)
        }
    }

    private fun canCreateSubfolder(folder: Folder): Boolean = !folder.isLocalOnly

    private fun canRename(folder: Folder, serverId: String): Boolean {
        return !folder.isLocalOnly && folder.type != FolderType.INBOX && !serverId.equals(INBOX, ignoreCase = true)
    }

    private fun canDelete(folder: Folder): Boolean = !folder.isLocalOnly && folder.type == FolderType.REGULAR

    private suspend fun findFolder(accountId: AccountId, folderId: Long): Folder? {
        return folderQueryRepository.findById(accountId, folderId).getOrThrow()
    }

    private suspend fun findFolderServerId(accountId: AccountId, folderId: Long): String? {
        return folderQueryRepository.findFolderServerIdById(accountId, folderId).getOrThrow()?.serverId
    }

    private suspend fun getRemoteFolderServerIds(accountId: AccountId): List<String> {
        return remoteFolderQueryRepository.getAllByAccountId(accountId).getOrThrow().map { it.serverId }
    }

    private suspend fun folderExists(accountId: AccountId, serverId: String): Boolean {
        return serverId in getRemoteFolderServerIds(accountId)
    }

    /**
     * Runs [block] on the I/O dispatcher for a supported account and maps failures to [FolderManagementError]s.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> performOperation(
        accountId: AccountId,
        block: suspend (LegacyAccount) -> Outcome<T, FolderManagementError>,
    ): Outcome<T, FolderManagementError> = withContext(ioDispatcher) {
        try {
            val account = accountManager.getById(accountId).firstOrNull()
            when {
                account == null -> Outcome.failure(FolderManagementError.Failed)

                account.incomingServerSettings.type !in supportedProtocols -> {
                    Outcome.failure(FolderManagementError.NotSupported)
                }

                else -> block(account)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderQueryException) {
            logger.error(TAG) { "Failed to query folders: ${e.error::class.simpleName}" }
            Outcome.failure(FolderManagementError.Failed)
        } catch (e: Exception) {
            // Exception messages may contain folder names, so only the type is logged.
            logger.error(TAG) { "Folder operation failed: ${e::class.simpleName}" }
            Outcome.failure(FolderManagementError.Failed)
        }
    }

    private fun <T> notFound(): Outcome<T, FolderManagementError> = Outcome.failure(
        FolderManagementError.FolderNotFound,
    )

    private fun <T> notAllowed(): Outcome<T, FolderManagementError> = Outcome.failure(FolderManagementError.NotAllowed)

    private fun <T> Outcome<T, FolderError>.getOrThrow(): T {
        return fold(
            onSuccess = { it },
            onFailure = { error -> throw FolderQueryException(error) },
        )
    }

    private class FolderQueryException(val error: FolderError) : RuntimeException()
}
