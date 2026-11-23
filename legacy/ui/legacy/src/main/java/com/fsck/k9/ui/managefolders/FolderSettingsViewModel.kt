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
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.FolderManagementOptions
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.feature.mail.folder.api.data.repository.FolderManagementRepository
import net.thunderbird.legacy.logging.Log

private const val NO_FOLDER_ID = 0L

@Suppress("TooManyFunctions")
class FolderSettingsViewModel(
    private val preferences: Preferences,
    private val folderDetailsRepository: FolderDetailsRepository,
    private val messagingController: MessagingController,
    private val folderManagementRepository: FolderManagementRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val actionLiveData = SingleLiveEvent<Action>()
    private val operationInProgressLiveData = MutableLiveData(false)
    private var folderSettingsLiveData: LiveData<FolderSettingsResult>? = null

    private lateinit var account: LegacyAccountDto
    private var folderId: Long = NO_FOLDER_ID
    private var folderManagementOptions: FolderManagementOptions? = null

    val showClearFolderInMenu: Boolean
        get() = this::account.isInitialized && folderId != NO_FOLDER_ID

    val showCreateSubfolderInMenu: Boolean
        get() = folderManagementOptions?.canCreateSubfolder == true

    val showRenameFolderInMenu: Boolean
        get() = folderManagementOptions?.canRename == true

    val showDeleteFolderInMenu: Boolean
        get() = folderManagementOptions?.canDelete == true

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
            this@FolderSettingsViewModel.folderManagementOptions = loadFolderManagementOptions(account, folderId)

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

    private suspend fun loadFolderManagementOptions(
        account: LegacyAccountDto,
        folderId: Long,
    ): FolderManagementOptions? {
        if (!folderManagementRepository.isSupported(account.id)) return null

        return folderManagementRepository.getOptions(account.id, folderId).fold(
            onSuccess = { it },
            onFailure = { null },
        )
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
        val options = folderManagementOptions ?: return
        sendActionEvent(Action.ShowCreateSubfolderDialog(options.folderPathDelimiter))
    }

    fun showRenameFolderDialog() {
        val options = folderManagementOptions ?: return
        sendActionEvent(
            Action.ShowRenameFolderDialog(
                currentName = options.name,
                folderPathDelimiter = options.folderPathDelimiter,
                isTopLevel = options.isTopLevel,
            ),
        )
    }

    fun showDeleteFolderConfirmationDialog() {
        sendActionEvent(Action.ShowDeleteFolderConfirmationDialog)
    }

    fun onCreateSubfolder(name: String) {
        performFolderOperation(FolderOperation.CREATE) {
            folderManagementRepository.create(account.id, parentFolderId = folderId, name = name)
        }
    }

    fun onRenameFolder(newName: String) {
        performFolderOperation(FolderOperation.RENAME) {
            folderManagementRepository.rename(account.id, folderId, newName)
        }
    }

    fun onDeleteFolderConfirmation() {
        performFolderOperation(FolderOperation.DELETE) {
            folderManagementRepository.delete(account.id, folderId)
        }
    }

    private fun performFolderOperation(
        operation: FolderOperation,
        block: suspend () -> Outcome<Unit, FolderManagementError>,
    ) {
        if (operationInProgressLiveData.value == true) return

        operationInProgressLiveData.value = true
        viewModelScope.launch {
            val event = FolderOperationEvent(
                operation = operation,
                error = block().fold(onSuccess = { null }, onFailure = { it }),
            )

            val updatedFolder = if (operation == FolderOperation.RENAME && event.isSuccess) {
                folderManagementOptions = loadFolderManagementOptions(account, folderId)
                folderDetailsRepository.loadFolderDetails(account, folderId)?.folder
            } else {
                null
            }

            operationInProgressLiveData.value = false
            sendActionEvent(Action.ShowFolderOperationResult(event, updatedFolder))
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
