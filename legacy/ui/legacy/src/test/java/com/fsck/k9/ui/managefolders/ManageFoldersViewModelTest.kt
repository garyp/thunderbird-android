package com.fsck.k9.ui.managefolders

import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
class ManageFoldersViewModelTest {
    private val account = LegacyAccountDto(ACCOUNT_UUID)
    private val folderManagementRepository = FakeFolderManagementRepository()
    private val testSubject = ManageFoldersViewModel(
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
    fun `getFolderManagementSupported() should return value from repository`() {
        // Arrange
        folderManagementRepository.isSupported = false
        var supported: Boolean? = null

        // Act
        testSubject.getFolderManagementSupported(account).observeForever { supported = it }

        // Assert
        assertThat(supported).isEqualTo(false)
    }

    @Test
    fun `createFolder() should create top-level folder and emit success event`() {
        // Arrange
        val events = mutableListOf<FolderOperationEvent>()
        testSubject.getOperationEvents().observeForever { events.add(it) }

        // Act
        testSubject.createFolder(account, name = "Projects")

        // Assert
        assertThat(folderManagementRepository.createCalls).containsExactly(
            FakeFolderManagementRepository.CreateCall(account.id, parentFolderId = null, name = "Projects"),
        )
        assertThat(events).containsExactly(FolderOperationEvent(FolderOperation.CREATE, error = null))
        assertThat(testSubject.getOperationInProgress().value).isEqualTo(false)
    }

    @Test
    fun `createFolder() should emit error event when repository fails`() {
        // Arrange
        folderManagementRepository.outcome = Outcome.failure(FolderManagementError.AlreadyExists)
        val events = mutableListOf<FolderOperationEvent>()
        testSubject.getOperationEvents().observeForever { events.add(it) }

        // Act
        testSubject.createFolder(account, name = "Projects")

        // Assert
        assertThat(events).containsExactly(
            FolderOperationEvent(FolderOperation.CREATE, FolderManagementError.AlreadyExists),
        )
        assertThat(events.single().isSuccess).isFalse()
    }

    @Test
    fun `FolderOperationEvent without error should be successful`() {
        // Arrange
        val event = FolderOperationEvent(FolderOperation.DELETE, error = null)

        // Act
        val result = event.isSuccess

        // Assert
        assertThat(result).isTrue()
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
