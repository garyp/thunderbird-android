package com.fsck.k9.ui.managefolders

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.FolderAlreadyExistsException
import com.fsck.k9.backend.api.FolderOperationException
import com.fsck.k9.controller.MessagingController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderServerId
import net.thunderbird.feature.mail.folder.api.RemoteFolder
import net.thunderbird.feature.mail.folder.api.data.FolderError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderQueryRepository
import net.thunderbird.feature.mail.folder.api.data.repository.RemoteFolderQueryRepository
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// MessagingController is a large legacy Java class without an interface, so it is mocked. Everything else uses fakes.
class FolderManagementOperationsTest {
    private val testDispatcher = StandardTestDispatcher()
    private val account = LegacyAccountDto(ACCOUNT_UUID).apply { folderPathDelimiter = "/" }
    private val folderRepository = FakeFolderRepository()
    private val messagingController = mock<MessagingController>()
    private val testSubject = FolderManagementOperations(
        folderQueryRepository = folderRepository,
        remoteFolderQueryRepository = folderRepository,
        messagingController = messagingController,
        ioDispatcher = testDispatcher,
    )

    @Before
    fun setUp() {
        Log.logger = TestLogger()
    }

    @Test
    fun `special folders can be renamed but not deleted`() {
        val inbox = createFolder(id = 1, type = FolderType.INBOX)
        val trash = createFolder(id = 2, type = FolderType.TRASH)
        val regular = createFolder(id = 3, type = FolderType.REGULAR)

        assertThat(testSubject.canRename(inbox)).isFalse()
        assertThat(testSubject.canDelete(inbox)).isFalse()
        assertThat(testSubject.canRename(trash)).isTrue()
        assertThat(testSubject.canDelete(trash)).isFalse()
        assertThat(testSubject.canRename(regular)).isTrue()
        assertThat(testSubject.canDelete(regular)).isTrue()
    }

    @Test
    fun `local-only folders can't be changed`() {
        val outbox = createFolder(id = 1, type = FolderType.OUTBOX, isLocalOnly = true)

        assertThat(testSubject.canCreateSubfolder(outbox)).isFalse()
        assertThat(testSubject.canRename(outbox)).isFalse()
        assertThat(testSubject.canDelete(outbox)).isFalse()
    }

    @Test
    fun `createFolder() at top level should create folder with trimmed name`() = runTestWithDispatcher {
        val result = testSubject.createFolder(account, parentFolderId = null, name = "  Projects ")

        assertThat(result).isEqualTo(FolderOperationResult.Success)
        verify(messagingController).createFolderBlocking(account, "Projects")
    }

    @Test
    fun `createFolder() with parent should create subfolder`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Work")

        val result = testSubject.createFolder(account, parentFolderId = 1, name = "2026")

        assertThat(result).isEqualTo(FolderOperationResult.Success)
        verify(messagingController).createFolderBlocking(account, "Work/2026")
    }

    @Test
    fun `createFolder() with invalid name should not contact server`() = runTestWithDispatcher {
        val result = testSubject.createFolder(account, parentFolderId = null, name = "a/b")

        assertThat(result).isEqualTo(FolderOperationResult.InvalidName(FolderNameError.CONTAINS_DELIMITER))
        verify(messagingController, never()).createFolderBlocking(any(), any())
    }

    @Test
    fun `createFolder() with existing name should return AlreadyExists`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Projects")

        val result = testSubject.createFolder(account, parentFolderId = null, name = "Projects")

        assertThat(result).isEqualTo(FolderOperationResult.AlreadyExists)
        verify(messagingController, never()).createFolderBlocking(any(), any())
    }

    @Test
    fun `createFolder() should map FolderAlreadyExistsException from server`() = runTestWithDispatcher {
        doThrow(FolderAlreadyExistsException("exists"))
            .whenever(messagingController).createFolderBlocking(account, "Projects")

        val result = testSubject.createFolder(account, parentFolderId = null, name = "Projects")

        assertThat(result).isEqualTo(FolderOperationResult.AlreadyExists)
    }

    @Test
    fun `renameFolder() should keep parent path`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Work/Old")

        val result = testSubject.renameFolder(account, folderId = 1, newName = "New")

        assertThat(result).isEqualTo(FolderOperationResult.Success)
        verify(messagingController).renameFolderBlocking(account, "Work/Old", "Work/New")
    }

    @Test
    fun `renameFolder() of Inbox should return NotAllowed`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1, type = FolderType.INBOX), serverId = "INBOX")

        val result = testSubject.renameFolder(account, folderId = 1, newName = "Old Inbox")

        assertThat(result).isEqualTo(FolderOperationResult.NotAllowed)
        verify(messagingController, never()).renameFolderBlocking(any(), any(), any())
    }

    @Test
    fun `renameFolder() with server error should return server message`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Old")
        doThrow(FolderOperationException("refused", serverResponseText = "Permission denied"))
            .whenever(messagingController).renameFolderBlocking(account, "Old", "New")

        val result = testSubject.renameFolder(account, folderId = 1, newName = "New")

        assertThat(result).isEqualTo(FolderOperationResult.ServerError("Permission denied"))
    }

    @Test
    fun `deleteFolder() with subfolders should return HasSubfolders`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Work")
        folderRepository.addFolder(createFolder(id = 2), serverId = "Work/2026")

        val result = testSubject.deleteFolder(account, folderId = 1)

        assertThat(result).isEqualTo(FolderOperationResult.HasSubfolders)
        verify(messagingController, never()).deleteFolderBlocking(any(), any())
    }

    @Test
    fun `deleteFolder() should ignore folders that only share a name prefix`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Work")
        folderRepository.addFolder(createFolder(id = 2), serverId = "Workshop")

        val result = testSubject.deleteFolder(account, folderId = 1)

        assertThat(result).isEqualTo(FolderOperationResult.Success)
        verify(messagingController).deleteFolderBlocking(account, "Work")
    }

    @Test
    fun `deleteFolder() of special folder should return NotAllowed`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1, type = FolderType.SENT), serverId = "Sent")

        val result = testSubject.deleteFolder(account, folderId = 1)

        assertThat(result).isEqualTo(FolderOperationResult.NotAllowed)
    }

    @Test
    fun `deleteFolder() of unknown folder should return FolderNotFound`() = runTestWithDispatcher {
        val result = testSubject.deleteFolder(account, folderId = 42)

        assertThat(result).isEqualTo(FolderOperationResult.FolderNotFound)
    }

    @Test
    fun `network error should return Failed`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Folder")
        doThrow(MessagingException("IO Error"))
            .whenever(messagingController).deleteFolderBlocking(account, "Folder")

        val result = testSubject.deleteFolder(account, folderId = 1)

        assertThat(result).isEqualTo(FolderOperationResult.Failed)
    }

    @Test
    fun `error reading the folder list should return Failed instead of throwing`() = runTestWithDispatcher {
        folderRepository.addFolder(createFolder(id = 1), serverId = "Folder")
        folderRepository.remoteFoldersError = FolderError.Unavailable

        val result = testSubject.deleteFolder(account, folderId = 1)

        assertThat(result).isEqualTo(FolderOperationResult.Failed)
    }

    private fun runTestWithDispatcher(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) { block() }

    private fun createFolder(
        id: Long,
        type: FolderType = FolderType.REGULAR,
        isLocalOnly: Boolean = false,
    ) = Folder(id = id, name = "irrelevant", type = type, isLocalOnly = isLocalOnly)

    private class FakeFolderRepository : FolderQueryRepository, RemoteFolderQueryRepository {
        private val folders = mutableMapOf<Long, Pair<Folder, String>>()
        var remoteFoldersError: FolderError? = null

        fun addFolder(folder: Folder, serverId: String) {
            folders[folder.id] = folder to serverId
        }

        override suspend fun findById(accountId: AccountId, folderId: Long): Outcome<Folder?, FolderError> {
            return Outcome.success(folders[folderId]?.first)
        }

        override suspend fun findFolderServerIdById(
            accountId: AccountId,
            folderId: Long,
        ): Outcome<FolderServerId?, FolderError> {
            return Outcome.success(folders[folderId]?.second?.let { FolderServerId(it) })
        }

        override suspend fun getAllByAccountId(accountId: AccountId): Outcome<List<RemoteFolder>, FolderError> {
            remoteFoldersError?.let { return Outcome.failure(it) }
            return Outcome.success(
                folders.values.map { (folder, serverId) ->
                    RemoteFolder(id = folder.id, serverId = serverId, name = folder.name, type = folder.type)
                },
            )
        }

        override suspend fun findIdByServerId(
            accountId: AccountId,
            folderServerId: FolderServerId,
        ): Outcome<Long?, FolderError> = throw UnsupportedOperationException("not implemented")

        override suspend fun isPresent(accountId: AccountId, folderId: Long): Boolean {
            throw UnsupportedOperationException("not implemented")
        }
    }

    private companion object {
        const val ACCOUNT_UUID = "00000000-0000-4000-0000-000000000000"
    }
}
