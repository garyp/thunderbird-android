package com.fsck.k9.ui.choosefolder

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import com.fsck.k9.helper.SingleLiveEvent
import com.fsck.k9.ui.managefolders.FolderOperation
import com.fsck.k9.ui.managefolders.FolderOperationEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.mail.folder.api.data.repository.FolderManagementRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ChooseFolderViewModel(
    private val folderRepository: DisplayFolderRepository,
    private val folderManagementRepository: FolderManagementRepository,
) : ViewModel() {
    private val inputFlow = MutableSharedFlow<DisplayMode>(replay = 1)
    private val foldersFlow = inputFlow
        .flatMapLatest { (account, showHiddenFolders) ->
            folderRepository.getDisplayFoldersFlow(account, showHiddenFolders)
        }
    private val operationInProgressLiveData = MutableLiveData(false)
    private val createFolderResultLiveData = SingleLiveEvent<CreateFolderResult>()
    private var folderManagementSupportedLiveData: LiveData<Boolean>? = null

    var isShowHiddenFolders: Boolean = false
        private set

    fun getFolders(): LiveData<List<DisplayFolder>> {
        return foldersFlow.asLiveData()
    }

    fun setDisplayMode(account: LegacyAccountDto, showHiddenFolders: Boolean) {
        isShowHiddenFolders = showHiddenFolders
        viewModelScope.launch {
            inputFlow.emit(DisplayMode(account, showHiddenFolders))
        }
    }

    fun getFolderManagementSupported(account: LegacyAccountDto): LiveData<Boolean> {
        return folderManagementSupportedLiveData ?: liveData {
            emit(folderManagementRepository.isSupported(account.id))
        }.also { folderManagementSupportedLiveData = it }
    }

    fun getOperationInProgress(): LiveData<Boolean> = operationInProgressLiveData

    fun getCreateFolderResults(): LiveData<CreateFolderResult> = createFolderResultLiveData

    /**
     * Creates a folder at the top level of the folder hierarchy, so it can be chosen right away.
     */
    fun createFolder(account: LegacyAccountDto, name: String) {
        if (operationInProgressLiveData.value == true) return

        operationInProgressLiveData.value = true
        viewModelScope.launch {
            val outcome = folderManagementRepository.create(account.id, parentFolderId = null, name = name)

            operationInProgressLiveData.value = false
            createFolderResultLiveData.value = outcome.fold(
                onSuccess = { folderId -> CreateFolderResult.Created(folderId, displayName = name.trim()) },
                onFailure = { error -> CreateFolderResult.Failed(FolderOperationEvent(FolderOperation.CREATE, error)) },
            )
        }
    }
}

sealed interface CreateFolderResult {
    /**
     * @param folderId The ID of the new folder, or `null` if it isn't in the local folder list (yet).
     */
    data class Created(val folderId: Long?, val displayName: String) : CreateFolderResult

    data class Failed(val event: FolderOperationEvent) : CreateFolderResult
}

private data class DisplayMode(val account: LegacyAccountDto, val showHiddenFolders: Boolean)
