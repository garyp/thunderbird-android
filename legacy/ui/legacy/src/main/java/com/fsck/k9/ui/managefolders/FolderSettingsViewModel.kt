package com.fsck.k9.ui.managefolders

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.helper.SingleLiveEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.legacy.logging.Log

private const val NO_FOLDER_ID = 0L

@Suppress("TooManyFunctions")
class FolderSettingsViewModel(
    private val preferences: Preferences,
    private val folderDetailsRepository: FolderDetailsRepository,
    private val messagingController: MessagingController,
    private val folderManagementOperations: FolderManagementOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val actionLiveData = SingleLiveEvent<Action>()
    private val operationInProgressLiveData = MutableLiveData(false)
    private var folderSettingsLiveData: LiveData<FolderSettingsResult>? = null

    private lateinit var account: LegacyAccountDto
    private var folderId: Long = NO_FOLDER_ID
    private var folder: Folder? = null
    private var folderManagementSupported = false

    val showClearFolderInMenu: Boolean
        get() = this::account.isInitialized && folderId != NO_FOLDER_ID

    val showCreateSubfolderInMenu: Boolean
        get() = folderManagementSupported && folder?.let { folderManagementOperations.canCreateSubfolder(it) } == true

    val showRenameFolderInMenu: Boolean
        get() = folderManagementSupported && folder?.let { folderManagementOperations.canRename(it) } == true

    val showDeleteFolderInMenu: Boolean
        get() = folderManagementSupported && folder?.let { folderManagementOperations.canDelete(it) } == true

    fun getFolderSettingsLiveData(accountUuid: String, folderId: Long): LiveData<FolderSettingsResult> {
        return folderSettingsLiveData ?: createFolderSettingsLiveData(accountUuid, folderId).also {
            folderSettingsLiveData = it
        }
    }

    private fun createFolderSettingsLiveData(
        accountUuid: String,
        folderId: Long,
    ): LiveData<FolderSettingsResult> {
        return liveData(context = viewModelScope.coroutineContext) {
            val account = loadAccount(accountUuid)
            val folderDetails = folderDetailsRepository.loadFolderDetails(account, folderId)
            if (folderDetails == null) {
                Log.w("Folder with ID $folderId not found")
                emit(FolderNotFound)
                return@liveData
            }

            this@FolderSettingsViewModel.account = account
            this@FolderSettingsViewModel.folderId = folderId
            this@FolderSettingsViewModel.folder = folderDetails.folder
            this@FolderSettingsViewModel.folderManagementSupported =
                folderManagementOperations.supportsFolderManagement(account)

            val folderSettingsData = FolderSettingsData(
                folder = folderDetails.folder,
                dataStore = FolderSettingsDataStore(folderDetailsRepository, account.id, folderDetails),
            )
            emit(folderSettingsData)
        }
    }

    private suspend fun loadAccount(accountUuid: String): LegacyAccountDto = withContext(ioDispatcher) {
        preferences.getAccount(accountUuid) ?: error("Missing account: $accountUuid")
    }

    private suspend fun FolderDetailsRepository.loadFolderDetails(
        account: LegacyAccountDto,
        folderId: Long,
    ): FolderDetails? = withContext(ioDispatcher) {
        findById(account.id, folderId).fold(onSuccess = { it }, onFailure = { null })
    }

    fun showClearFolderConfirmationDialog() {
        sendActionEvent(Action.ShowClearFolderConfirmationDialog)
    }

    fun onClearFolderConfirmation() {
        messagingController.clearFolder(account, folderId)
    }

    fun showCreateSubfolderDialog() {
        sendActionEvent(Action.ShowCreateSubfolderDialog(account.folderPathDelimiter))
    }

    fun showRenameFolderDialog() {
        viewModelScope.launch {
            val serverId = folderManagementOperations.getFolderServerId(account, folderId) ?: return@launch
            val delimiter = account.folderPathDelimiter
            sendActionEvent(
                Action.ShowRenameFolderDialog(
                    currentName = FolderServerIdHelper.getName(serverId, delimiter),
                    folderPathDelimiter = delimiter,
                    isTopLevel = FolderServerIdHelper.getParentServerId(serverId, delimiter) == null,
                ),
            )
        }
    }

    fun showDeleteFolderConfirmationDialog() {
        sendActionEvent(Action.ShowDeleteFolderConfirmationDialog)
    }

    fun onCreateSubfolder(name: String) {
        performFolderOperation(FolderOperation.CREATE) {
            folderManagementOperations.createFolder(account, parentFolderId = folderId, name = name)
        }
    }

    fun onRenameFolder(newName: String) {
        performFolderOperation(FolderOperation.RENAME) {
            folderManagementOperations.renameFolder(account, folderId, newName)
        }
    }

    fun onDeleteFolderConfirmation() {
        performFolderOperation(FolderOperation.DELETE) {
            folderManagementOperations.deleteFolder(account, folderId)
        }
    }

    private fun performFolderOperation(
        operation: FolderOperation,
        block: suspend () -> FolderOperationResult,
    ) {
        if (operationInProgressLiveData.value == true) return

        operationInProgressLiveData.value = true
        viewModelScope.launch {
            val result = block()

            val updatedFolder = if (operation == FolderOperation.RENAME && result == FolderOperationResult.Success) {
                folderDetailsRepository.loadFolderDetails(account, folderId)?.folder?.also { folder = it }
            } else {
                null
            }

            operationInProgressLiveData.value = false
            sendActionEvent(
                Action.ShowFolderOperationResult(FolderOperationEvent(operation, result), updatedFolder),
            )
        }
    }

    fun getOperationInProgress(): LiveData<Boolean> = operationInProgressLiveData

    fun getActionEvents(): LiveData<Action> = actionLiveData

    private fun sendActionEvent(action: Action) {
        actionLiveData.value = action
    }
}

sealed class FolderSettingsResult
object FolderNotFound : FolderSettingsResult()
data class FolderSettingsData(val folder: Folder, val dataStore: FolderSettingsDataStore) : FolderSettingsResult()

sealed class Action {
    object ShowClearFolderConfirmationDialog : Action()
    data class ShowCreateSubfolderDialog(val folderPathDelimiter: String) : Action()
    data class ShowRenameFolderDialog(
        val currentName: String,
        val folderPathDelimiter: String,
        val isTopLevel: Boolean,
    ) : Action()
    object ShowDeleteFolderConfirmationDialog : Action()
    data class ShowFolderOperationResult(
        val event: FolderOperationEvent,
        val updatedFolder: Folder?,
    ) : Action()
}
