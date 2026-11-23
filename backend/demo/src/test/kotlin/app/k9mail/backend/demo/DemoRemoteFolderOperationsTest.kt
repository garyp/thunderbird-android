package app.k9mail.backend.demo

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.components.core.outcome.Outcome

class DemoRemoteFolderOperationsTest {
    private val backendStorage = InMemoryBackendStorage()
    private val demoStore = DemoStore(backendStorage)
    private lateinit var tempDirectory: File

    @BeforeTest
    fun setUp() {
        tempDirectory = Files.createTempDirectory("DemoRemoteFolderOperationsTest").toFile()
        BinaryTempFileBody.setTempDirectory(tempDirectory)
    }

    @AfterTest
    fun tearDown() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `create() should add folder to the demo server`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderCreator(demoStore)

        // Act
        val outcome = testSubject.create(FolderServerId("nested/Projects"), mustCreate = true)

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(RemoteFolderCreationOutcome.Success.Created))
        assertThat(demoStore.getFolder("nested/Projects")?.type).isEqualTo(FolderType.REGULAR)
    }

    @Test
    fun `create() with existing folder and mustCreate should return AlreadyExists error`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderCreator(demoStore)

        // Act
        val outcome = testSubject.create(FolderServerId("turing"), mustCreate = true)

        // Assert
        assertThat(outcome).isEqualTo(Outcome.failure(RemoteFolderCreationOutcome.Error.AlreadyExists))
    }

    @Test
    fun `rename() should rename folder and subfolders and keep demo messages loadable`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderRenamer(demoStore)

        // Act
        val outcome = testSubject.rename(FolderServerId("nested"), FolderServerId("Renamed"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(demoStore.getFolderIds()).doesNotContain("nested")
        assertThat(demoStore.getFolderIds()).containsAll(
            "Renamed",
            "Renamed/nested_level_1",
            "Renamed/nested_level_1/nested_level_2",
        )
        assertThat(demoStore.getFolder("Renamed/nested_level_1")?.name).isEqualTo("Renamed/Nested Level 1")
        val folder = demoStore.getFolder("Renamed/nested_level_1/nested_level_2")
        assertThat(folder).isNotNull()
        val messageServerId = folder!!.messageServerIds.first()
        assertThat(demoStore.getMessage("Renamed/nested_level_1/nested_level_2", messageServerId).uid)
            .isEqualTo(messageServerId)
    }

    @Test
    fun `rename() of subfolder should keep parent display name`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderRenamer(demoStore)

        // Act
        testSubject.rename(
            FolderServerId("nested/nested_level_1"),
            FolderServerId("nested/Level One"),
            folderPathDelimiter = "/",
        )

        // Assert
        assertThat(demoStore.getFolder("nested/Level One")?.name).isEqualTo("Nested/Level One")
        assertThat(demoStore.getFolder("nested/Level One/nested_level_2")?.name)
            .isEqualTo("Nested/Level One/Nested Level 2")
    }

    @Test
    fun `rename() to existing name should return AlreadyExists`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderRenamer(demoStore)

        // Act
        val outcome = testSubject.rename(FolderServerId("nested"), FolderServerId("turing"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(Outcome.failure(RemoteFolderOperationError.AlreadyExists))
        assertThat(demoStore.getFolderIds()).contains("nested")
    }

    @Test
    fun `rename() of inbox should be rejected`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderRenamer(demoStore)

        // Act
        val outcome = testSubject.rename(FolderServerId("inbox"), FolderServerId("Old"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isInstanceOf<Outcome.Failure<RemoteFolderOperationError>>()
    }

    @Test
    fun `delete() should remove folder from the demo server`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderDeleter(demoStore)

        // Act
        val outcome = testSubject.delete(FolderServerId("turing"))

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(demoStore.getFolder("turing")).isNull()
    }

    @Test
    fun `delete() with subfolders should be rejected like an IMAP server would`() = runTest {
        // Arrange
        val testSubject = DemoRemoteFolderDeleter(demoStore)

        // Act
        val outcome = testSubject.delete(FolderServerId("nested"))

        // Assert
        assertThat(outcome).isEqualTo(
            Outcome.failure(RemoteFolderOperationError.HasSubfolders),
        )
        assertThat(demoStore.getFolderIds()).contains("nested")
    }

    @Test
    fun `changes should be visible to other store instances and survive a folder list refresh`() = runTest {
        // Arrange
        val creator = DemoRemoteFolderCreator(DemoStore(backendStorage))
        val renamer = DemoRemoteFolderRenamer(DemoStore(backendStorage))
        val deleter = DemoRemoteFolderDeleter(DemoStore(backendStorage))

        // Act
        creator.create(FolderServerId("Projects"), mustCreate = true)
        renamer.rename(FolderServerId("turing"), FolderServerId("Alan"), folderPathDelimiter = "/")
        deleter.delete(FolderServerId("archive"))
        CommandRefreshFolderList(backendStorage, demoStore).refreshFolderList()

        // Assert
        val expectedServerIds = arrayOf(
            "inbox", "drafts", "sent", "spam", "trash",
            "nested", "nested/nested_level_1", "nested/nested_level_1/nested_level_2",
            "Alan", "Projects",
        )
        assertThat(demoStore.getFolderIds()).containsExactlyInAnyOrder(*expectedServerIds)
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder(*expectedServerIds)
    }
}
