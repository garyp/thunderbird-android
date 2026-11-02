package com.fsck.k9.ui.managefolders

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import com.fsck.k9.helper.SingleLiveEvent
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountDto

class ManageFoldersViewModel(
    private val folderRepository: DisplayFolderRepository,
    private val folderManagementOperations: FolderManagementOperations,
) : ViewModel() {
    private val operationInProgressLiveData = MutableLiveData(false)
    private val operationEventLiveData = SingleLiveEvent<FolderOperationEvent>()

    fun getFolders(account: LegacyAccountDto): LiveData<List<DisplayFolder>> {
        return folderRepository.getDisplayFoldersFlow(account, includeHiddenFolders = true).asLiveData()
    }

    fun supportsFolderManagement(account: LegacyAccountDto): Boolean {
        return folderManagementOperations.supportsFolderManagement(account)
    }

    fun getOperationInProgress(): LiveData<Boolean> = operationInProgressLiveData

    fun getOperationEvents(): LiveData<FolderOperationEvent> = operationEventLiveData

    fun createFolder(account: LegacyAccountDto, name: String) {
        if (operationInProgressLiveData.value == true) return

        operationInProgressLiveData.value = true
        viewModelScope.launch {
            val result = folderManagementOperations.createFolder(account, parentFolderId = null, name = name)

            operationInProgressLiveData.value = false
            operationEventLiveData.value = FolderOperationEvent(FolderOperation.CREATE, result)
        }
    }
}
