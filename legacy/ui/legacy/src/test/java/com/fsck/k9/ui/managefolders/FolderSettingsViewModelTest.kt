package com.fsck.k9.ui.managefolders

import androidx.lifecycle.LiveData
import app.k9mail.legacy.di.DI
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.Preferences
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.FolderManagementOptions
import net.thunderbird.feature.mail.folder.api.data.FolderError
import net.thunderbird.feature.mail.folder.api.data.FolderManagementError
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.feature.mail.folder.api.data.repository.PartialUpdatableFolderDetails
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class FolderSettingsViewModelTest : K9RobolectricTest() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val folderManagementRepository = FakeFolderManagementRepository(options = OPTIONS)
    private lateinit var account: LegacyAccountDto
    private lateinit var testSubject: FolderSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val preferences = DI.get<Preferences>()
        account = preferences.newAccount().apply {
            incomingServerSettings = SERVER_SETTINGS
            outgoingServerSettings = SERVER_SETTINGS
        }
        preferences.saveAccount(account)

        testSubject = FolderSettingsViewModel(
            preferences = preferences,
            folderDetailsRepository = FakeFolderDetailsRepository(),
            // MessagingController is a legacy class without an interface that can't be created in tests. The tested
            // code doesn't use it.
            messagingController = mock(),
            folderManagementRepository = folderManagementRepository,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `menu should reflect folder management options`() {
        // Act
        loadFolderSettings()

        // Assert
        assertThat(testSubject.showCreateSubfolderInMenu).isTrue()
        assertThat(testSubject.showRenameFolderInMenu).isTrue()
        assertThat(testSubject.showDeleteFolderInMenu).isFalse()
    }

    @Test
    fun `menu should hide folder management items when not supported`() {
        // Arrange
        folderManagementRepository.isSupported = false

        // Act
        loadFolderSettings()

        // Assert
        assertThat(testSubject.showCreateSubfolderInMenu).isFalse()
        assertThat(testSubject.showRenameFolderInMenu).isFalse()
        assertThat(testSubject.showDeleteFolderInMenu).isFalse()
    }

    @Test
    fun `showRenameFolderDialog() should use name and delimiter from options`() {
        // Arrange
        loadFolderSettings()
        val actions = testSubject.getActionEvents().collect()

        // Act
        testSubject.showRenameFolderDialog()

        // Assert
        assertThat(actions).containsExactly(
            Action.ShowRenameFolderDialog(currentName = "2026", folderPathDelimiter = "/", isTopLevel = false),
        )
    }

    @Test
    fun `onRenameFolder() should rename folder and report updated folder`() {
        // Arrange
        loadFolderSettings()
        val actions = testSubject.getActionEvents().collect()

        // Act
        testSubject.onRenameFolder("2027")

        // Assert
        assertThat(folderManagementRepository.renameCalls).containsExactly(
            FakeFolderManagementRepository.RenameCall(account.id, FOLDER_ID, "2027"),
        )
        val action = actions.single() as Action.ShowFolderOperationResult
        assertThat(action.event).isEqualTo(FolderOperationEvent(FolderOperation.RENAME, error = null))
        assertThat(action.updatedFolder).isEqualTo(FOLDER)
    }

    @Test
    fun `onDeleteFolderConfirmation() should report error`() {
        // Arrange
        loadFolderSettings()
        folderManagementRepository.outcome = Outcome.failure(FolderManagementError.HasSubfolders)
        val actions = testSubject.getActionEvents().collect()

        // Act
        testSubject.onDeleteFolderConfirmation()

        // Assert
        assertThat(folderManagementRepository.deleteCalls).containsExactly(
            FakeFolderManagementRepository.DeleteCall(account.id, FOLDER_ID),
        )
        assertThat(actions.single()).isInstanceOf<Action.ShowFolderOperationResult>()
            .transform { it.event }
            .isEqualTo(FolderOperationEvent(FolderOperation.DELETE, FolderManagementError.HasSubfolders))
    }

    private fun loadFolderSettings() {
        val results = testSubject.getFolderSettingsLiveData(account.uuid, FOLDER_ID).collect()
        assertThat(results.single()).isInstanceOf<FolderSettingsData>()
    }

    private fun <T> LiveData<T>.collect(): List<T> {
        return mutableListOf<T>().also { values -> observeForever { values.add(it) } }
    }

    private class FakeFolderDetailsRepository : FolderDetailsRepository {
        override suspend fun findById(accountId: AccountId, folderId: Long): Outcome<FolderDetails?, FolderError> {
            return Outcome.success(FOLDER_DETAILS.takeIf { folderId == FOLDER_ID })
        }

        override suspend fun update(accountId: AccountId, folderDetails: FolderDetails): Outcome<Unit, FolderError> {
            return Outcome.success(Unit)
        }

        override suspend fun update(
            accountId: AccountId,
            partialUpdate: PartialUpdatableFolderDetails,
        ): Outcome<Unit, FolderError> = Outcome.success(Unit)
    }

    private companion object {
        const val FOLDER_ID = 1L
        val SERVER_SETTINGS = ServerSettings(
            type = "imap",
            host = "imap.example.com",
            port = 993,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = "user",
            password = "password",
            clientCertificateAlias = null,
        )
        val FOLDER = Folder(id = FOLDER_ID, name = "Work/2026", type = FolderType.REGULAR, isLocalOnly = false)
        val FOLDER_DETAILS = FolderDetails(
            folder = FOLDER,
            isInTopGroup = false,
            isIntegrate = false,
            isSyncEnabled = true,
            isVisible = true,
            isNotificationsEnabled = false,
            isPushEnabled = false,
        )
        val OPTIONS = FolderManagementOptions(
            name = "2026",
            isTopLevel = false,
            folderPathDelimiter = "/",
            canCreateSubfolder = true,
            canRename = true,
            canDelete = false,
        )
    }
}
