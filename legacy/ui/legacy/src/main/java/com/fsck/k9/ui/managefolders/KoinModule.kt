package com.fsck.k9.ui.managefolders

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val manageFoldersUiModule = module {
    factory {
        FolderManagementOperations(
            folderQueryRepository = get(),
            remoteFolderQueryRepository = get(),
            messagingController = get(),
        )
    }
    viewModel { ManageFoldersViewModel(folderRepository = get(), folderManagementOperations = get()) }
    viewModel {
        FolderSettingsViewModel(
            preferences = get(),
            folderDetailsRepository = get(),
            messagingController = get(),
            folderManagementOperations = get(),
        )
    }
}
