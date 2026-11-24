package com.fsck.k9.ui.choosefolder

import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.fsck.k9.ui.managefolders.FakeFolderManagementRepository
import com.fsck.k9.ui.managefolders.FolderOperation
import com.fsck.k9.ui.managefolders.FolderOperationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChooseFolderViewModelTest {
    private val account = LegacyAccountDto(ACCOUNT_UUID)
    private val folderManagementRepository = FakeFolderManagementRepository()
    private val testSubject = ChooseFolderViewModel(
        folderRepository = FakeDisplayFolderRepository(),
        folderManagementRepository = folderManagementRepository,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `createFolder() should create top-level folder and report its ID`() {
        // Arrange
        folderManagementRepository.createdFolderId = 42L
        val results = mutableListOf<CreateFolderResult>()
        testSubject.getCreateFolderResults().observeForever { results.add(it) }

        // Act
        testSubject.createFolder(account, name = " Receipts ")

        // Assert
        assertThat(folderManagementRepository.createCalls).containsExactly(
            FakeFolderManagementRepository.CreateCall(account.id, parentFolderId = null, name = " Receipts "),
        )
        assertThat(results).containsExactly(CreateFolderResult.Created(folderId = 42L, displayName = "Receipts"))
        assertThat(testSubject.getOperationInProgress().value).isEqualTo(false)
    }

    @Test
    fun `createFolder() should report failure`() {
        // Arrange
        folderManagementRepository.outcome = Outcome.failure(FolderManagementError.AlreadyExists)
        val results = mutableListOf<CreateFolderResult>()
        testSubject.getCreateFolderResults().observeForever { results.add(it) }

        // Act
        testSubject.createFolder(account, name = "Receipts")

        // Assert
        assertThat(results).containsExactly(
            CreateFolderResult.Failed(
                FolderOperationEvent(FolderOperation.CREATE, FolderManagementError.AlreadyExists),
            ),
        )
    }

    @Test
    fun `getFolderManagementSupported() should return value from repository`() {
        // Arrange
        folderManagementRepository.isSupported = false
        var supported: Boolean? = null

        // Act
        testSubject.getFolderManagementSupported(account).observeForever { supported = it }

        // Assert
        assertThat(supported).isEqualTo(false)
    }

    private class FakeDisplayFolderRepository : DisplayFolderRepository {
        override fun getDisplayFoldersFlow(
            account: LegacyAccountDto,
            includeHiddenFolders: Boolean,
        ): Flow<List<DisplayFolder>> = emptyFlow()

        override fun getDisplayFoldersFlow(accountUuid: String): Flow<List<DisplayFolder>> = emptyFlow()
    }

    private companion object {
        const val ACCOUNT_UUID = "00000000-0000-4000-0000-000000000000"
    }
}
