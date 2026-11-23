package net.thunderbird.app.common.feature.mail.folder

import app.k9mail.backend.testing.InMemoryBackendFolder
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.thunderbird.app.common.account.data.FakeLegacyAccountManager
import net.thunderbird.app.common.feature.mail.FakeData
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderManagementOptions
import net.thunderbird.feature.mail.folder.api.FolderNameError
import net.thunderbird.feature.mail.folder.api.data.FolderError
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError
import com.fsck.k9.mail.FolderType as LegacyFolderType

@Suppress("LargeClass")
class DefaultFolderManagementRepositoryTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val account = FakeData.legacyAccount
    private val accountId = account.id
    private val accountManager = FakeLegacyAccountManager(initialAccounts = listOf(account))
    private val folderRepository = FakeFolderRepository()
    private val backendStorageFactory = FakeBackendStorageFactory()
    private val localFolders = backendStorageFactory.backendStorage.folders
    private val remoteFolderOperations = FakeRemoteFolderOperations()
    private val refreshedAccounts = mutableListOf<AccountId>()
    private val testSubject = DefaultFolderManagementRepository(
        accountManager = accountManager,
        folderQueryRepository = folderRepository,
        remoteFolderQueryRepository = folderRepository,
        folderHierarchyUpdater = FolderHierarchyUpdater(
            backendStorageFactory = backendStorageFactory,
            remoteFolderCreatorFactory = remoteFolderOperations,
            remoteFolderRenamerFactory = remoteFolderOperations,
            remoteFolderDeleterFactory = remoteFolderOperations,
            folderListRefresher = { accountId -> refreshedAccounts.add(accountId) },
            logger = TestLogger(),
            ioDispatcher = testDispatcher,
        ),
        folderNameValidator = DefaultFolderNameValidator(),
        supportedProtocols = setOf(Protocols.IMAP),
        logger = TestLogger(),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `isSupported() should return true for IMAP account`() = runTest(testDispatcher) {
        // Act
        val result = testSubject.isSupported(accountId)

        // Assert
        assertThat(result).isTrue()
    }

    @Test
    fun `isSupported() should return false for POP3 account`() = runTest(testDispatcher) {
        // Arrange
        usePop3Account()

        // Act
        val result = testSubject.isSupported(accountId)

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `getOptions() should allow everything for regular subfolder`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Work/2026")

        // Act
        val result = testSubject.getOptions(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(
            Outcome.success(
                FolderManagementOptions(
                    name = "2026",
                    isTopLevel = false,
                    folderPathDelimiter = "/",
                    canCreateSubfolder = true,
                    canRename = true,
                    canDelete = true,
                ),
            ),
        )
    }

    @Test
    fun `getOptions() should allow renaming but not deleting special folders`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Trash", type = FolderType.TRASH)

        // Act
        val result = testSubject.getOptions(accountId, folderId = 1)

        // Assert
        assertThat(result.optionsOrNull()?.canRename).isEqualTo(true)
        assertThat(result.optionsOrNull()?.canDelete).isEqualTo(false)
    }

    @Test
    fun `getOptions() should not allow renaming or deleting the Inbox`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "INBOX", type = FolderType.INBOX)

        // Act
        val result = testSubject.getOptions(accountId, folderId = 1)

        // Assert
        assertThat(result.optionsOrNull()?.canCreateSubfolder).isEqualTo(true)
        assertThat(result.optionsOrNull()?.canRename).isEqualTo(false)
        assertThat(result.optionsOrNull()?.canDelete).isEqualTo(false)
    }

    @Test
    fun `getOptions() should not allow changing local-only folders`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "K9MAIL_INTERNAL_OUTBOX", type = FolderType.OUTBOX, isLocalOnly = true)

        // Act
        val result = testSubject.getOptions(accountId, folderId = 1)

        // Assert
        assertThat(result.optionsOrNull()?.canCreateSubfolder).isEqualTo(false)
        assertThat(result.optionsOrNull()?.canRename).isEqualTo(false)
        assertThat(result.optionsOrNull()?.canDelete).isEqualTo(false)
    }

    @Test
    fun `create() at top level should create folder with trimmed name and refresh folder list`() =
        runTest(testDispatcher) {
            // Act
            val result = testSubject.create(accountId, parentFolderId = null, name = "  Projects ")

            // Assert
            assertThat(result).isEqualTo(Outcome.success(Unit))
            assertThat(remoteFolderOperations.createdFolders).containsExactly("Projects")
            assertThat(refreshedAccounts).containsExactly(accountId)
        }

    @Test
    fun `create() with parent should create subfolder`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Work")

        // Act
        val result = testSubject.create(accountId, parentFolderId = 1, name = "2026")

        // Assert
        assertThat(result).isEqualTo(Outcome.success(Unit))
        assertThat(remoteFolderOperations.createdFolders).containsExactly("Work/2026")
        assertThat(refreshedAccounts).containsExactly(accountId)
    }

    @Test
    fun `create() with invalid name should not contact server`() = runTest(testDispatcher) {
        // Act
        val result = testSubject.create(accountId, parentFolderId = null, name = "a/b")

        // Assert
        assertThat(result).isEqualTo(
            Outcome.failure(FolderManagementError.InvalidName(FolderNameError.CONTAINS_DELIMITER)),
        )
        assertThat(remoteFolderOperations.createdFolders).isEmpty()
    }

    @Test
    fun `create() with existing name should return AlreadyExists`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Projects")

        // Act
        val result = testSubject.create(accountId, parentFolderId = null, name = "Projects")

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.AlreadyExists))
        assertThat(remoteFolderOperations.createdFolders).isEmpty()
    }

    @Test
    fun `create() should map server refusal to ServerError`() = runTest(testDispatcher) {
        // Arrange
        remoteFolderOperations.createOutcome = Outcome.failure(
            RemoteFolderCreationOutcome.Error.RejectedByServer("Quota exceeded"),
        )

        // Act
        val result = testSubject.create(accountId, parentFolderId = null, name = "Projects")

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.ServerError("Quota exceeded")))
        assertThat(refreshedAccounts).isEmpty()
    }

    @Test
    fun `create() for unsupported account should return NotSupported`() = runTest(testDispatcher) {
        // Arrange
        usePop3Account()

        // Act
        val result = testSubject.create(accountId, parentFolderId = null, name = "Projects")

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.NotSupported))
        assertThat(remoteFolderOperations.createdFolders).isEmpty()
    }

    @Test
    fun `rename() should keep parent path and rename local subfolders`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Work/Old")
        addFolder(id = 2, serverId = "Work/Old/2026")
        addFolder(id = 3, serverId = "Work/Older")

        // Act
        val result = testSubject.rename(accountId, folderId = 1, newName = "New")

        // Assert
        assertThat(result).isEqualTo(Outcome.success(Unit))
        assertThat(remoteFolderOperations.renamedFolders).containsExactly("Work/Old" to "Work/New")
        assertThat(localFolders.keys).containsExactlyInAnyOrder("Work/New", "Work/New/2026", "Work/Older")
    }

    @Test
    fun `rename() to the same name should not contact server`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Projects")

        // Act
        val result = testSubject.rename(accountId, folderId = 1, newName = " Projects ")

        // Assert
        assertThat(result).isEqualTo(Outcome.success(Unit))
        assertThat(remoteFolderOperations.renamedFolders).isEmpty()
    }

    @Test
    fun `rename() of Inbox should return NotAllowed`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "INBOX", type = FolderType.INBOX)

        // Act
        val result = testSubject.rename(accountId, folderId = 1, newName = "Old Inbox")

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.NotAllowed))
        assertThat(remoteFolderOperations.renamedFolders).isEmpty()
    }

    @Test
    fun `rename() should map server refusal to ServerError`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Old")
        remoteFolderOperations.operationOutcome = Outcome.failure(
            RemoteFolderOperationError.RejectedByServer("Permission denied"),
        )

        // Act
        val result = testSubject.rename(accountId, folderId = 1, newName = "New")

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.ServerError("Permission denied")))
        assertThat(localFolders.keys.toList()).containsExactly("Old")
    }

    @Test
    fun `delete() with subfolders should return HasSubfolders`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Work")
        addFolder(id = 2, serverId = "Work/2026")

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.HasSubfolders))
        assertThat(remoteFolderOperations.deletedFolders).isEmpty()
    }

    @Test
    fun `delete() should ignore folders that only share a name prefix`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Work")
        addFolder(id = 2, serverId = "Workshop")

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.success(Unit))
        assertThat(remoteFolderOperations.deletedFolders).containsExactly("Work")
        assertThat(localFolders.keys.toList()).containsExactly("Workshop")
    }

    @Test
    fun `delete() of special folder should return NotAllowed`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Sent", type = FolderType.SENT)

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.NotAllowed))
    }

    @Test
    fun `delete() of unknown folder should return FolderNotFound`() = runTest(testDispatcher) {
        // Act
        val result = testSubject.delete(accountId, folderId = 42)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.FolderNotFound))
    }

    @Test
    fun `delete() should return Failed when the remote operation fails`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Folder")
        remoteFolderOperations.operationOutcome = Outcome.failure(RemoteFolderOperationError.Failed("IOException"))

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.Failed))
        assertThat(localFolders.keys.toList()).containsExactly("Folder")
    }

    @Test
    fun `error reading the folder list should return Failed instead of throwing`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Folder")
        folderRepository.remoteFoldersError = FolderError.Unavailable

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.Failed))
        assertThat(remoteFolderOperations.deletedFolders).isEmpty()
    }

    @Test
    fun `rename() should succeed and refresh folder list when the local update fails`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Old")
        backendStorageFactory.exception = MessagingException("Database error")

        // Act
        val result = testSubject.rename(accountId, folderId = 1, newName = "New")

        // Assert
        assertThat(result).isEqualTo(Outcome.success(Unit))
        assertThat(remoteFolderOperations.renamedFolders).containsExactly("Old" to "New")
        assertThat(refreshedAccounts).containsExactly(accountId)
    }

    @Test
    fun `delete() should map subfolders on the server to HasSubfolders`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Parent")
        remoteFolderOperations.operationOutcome = Outcome.failure(RemoteFolderOperationError.HasSubfolders)

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.HasSubfolders))
        assertThat(localFolders.keys.toList()).containsExactly("Parent")
    }

    @Test
    fun `checked exception should return Failed instead of throwing`() = runTest(testDispatcher) {
        // Arrange
        addFolder(id = 1, serverId = "Folder")
        remoteFolderOperations.exception = MessagingException("Database error")

        // Act
        val result = testSubject.delete(accountId, folderId = 1)

        // Assert
        assertThat(result).isEqualTo(Outcome.failure(FolderManagementError.Failed))
    }

    private suspend fun usePop3Account() {
        accountManager.update(
            account.copy(incomingServerSettings = account.incomingServerSettings.copy(type = Protocols.POP3)),
        )
    }

    private fun addFolder(
        id: Long,
        serverId: String,
        type: FolderType = FolderType.REGULAR,
        isLocalOnly: Boolean = false,
    ) {
        folderRepository.addFolder(
            Folder(id = id, name = serverId, type = type, isLocalOnly = isLocalOnly),
            serverId = serverId,
        )
        localFolders[serverId] = InMemoryBackendFolder(name = serverId, type = LegacyFolderType.REGULAR)
    }

    private fun Outcome<FolderManagementOptions, FolderManagementError>.optionsOrNull(): FolderManagementOptions? {
        return (this as? Outcome.Success)?.data
    }
}
