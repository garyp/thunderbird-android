package app.k9mail.backend.demo

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.backend.api.FolderAlreadyExistsException
import com.fsck.k9.backend.api.FolderOperationException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class CommandManageFolderTest {
    private val backendStorage = InMemoryBackendStorage()
    private val demoStore = DemoStore(backendStorage)
    private val testSubject = CommandManageFolder(backendStorage, demoStore)
    private lateinit var tempDirectory: File

    @BeforeTest
    fun setUp() {
        tempDirectory = Files.createTempDirectory("CommandManageFolderTest").toFile()
        BinaryTempFileBody.setTempDirectory(tempDirectory)

        CommandRefreshFolderList(backendStorage, demoStore).refreshFolderList()
    }

    @AfterTest
    fun tearDown() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `createFolder() should add folder to store and local folder list`() {
        testSubject.createFolder("nested/Projects")

        assertThat(demoStore.getFolderIds()).contains("nested/Projects")
        assertThat(backendStorage.getFolderServerIds()).contains("nested/Projects")
        assertThat(backendStorage.getFolder("nested/Projects").type).isEqualTo(FolderType.REGULAR)
    }

    @Test
    fun `createFolder() with existing folder should throw FolderAlreadyExistsException`() {
        assertFailure {
            testSubject.createFolder("turing")
        }.isInstanceOf<FolderAlreadyExistsException>()
    }

    @Test
    fun `renameFolder() should rename folder and subfolders and keep demo messages loadable`() {
        testSubject.renameFolder(folderServerId = "nested", newFolderServerId = "Renamed", folderPathDelimiter = "/")

        val expectedServerIds = listOf("Renamed", "Renamed/nested_level_1", "Renamed/nested_level_1/nested_level_2")
        assertThat(demoStore.getFolderIds()).doesNotContain("nested")
        assertThat(demoStore.getFolderIds()).containsAll(*expectedServerIds.toTypedArray())
        assertThat(backendStorage.getFolderServerIds()).containsAll(*expectedServerIds.toTypedArray())
        assertThat(backendStorage.getFolder("Renamed/nested_level_1").name).isEqualTo("Renamed/Nested Level 1")

        val folder = demoStore.getFolder("Renamed/nested_level_1/nested_level_2")
        assertThat(folder).isNotNull()
        val messageServerId = folder!!.messageServerIds.first()
        assertThat(demoStore.getMessage("Renamed/nested_level_1/nested_level_2", messageServerId).uid)
            .isEqualTo(messageServerId)
    }

    @Test
    fun `renameFolder() of subfolder should keep parent display name`() {
        testSubject.renameFolder(
            folderServerId = "nested/nested_level_1",
            newFolderServerId = "nested/Level One",
            folderPathDelimiter = "/",
        )

        assertThat(backendStorage.getFolder("nested/Level One").name).isEqualTo("Nested/Level One")
        assertThat(backendStorage.getFolder("nested/Level One/nested_level_2").name)
            .isEqualTo("Nested/Level One/Nested Level 2")
    }

    @Test
    fun `renameFolder() to existing name should throw FolderAlreadyExistsException`() {
        assertFailure {
            testSubject.renameFolder(folderServerId = "nested", newFolderServerId = "turing", folderPathDelimiter = "/")
        }.isInstanceOf<FolderAlreadyExistsException>()

        assertThat(demoStore.getFolderIds()).contains("nested")
    }

    @Test
    fun `renameFolder() of inbox should throw FolderOperationException`() {
        assertFailure {
            testSubject.renameFolder(folderServerId = "inbox", newFolderServerId = "Old", folderPathDelimiter = "/")
        }.isInstanceOf<FolderOperationException>()
    }

    @Test
    fun `deleteFolder() should remove folder from store and local folder list`() {
        testSubject.deleteFolder("turing")

        assertThat(demoStore.getFolder("turing")).isNull()
        assertThat(backendStorage.getFolderServerIds()).doesNotContain("turing")
    }

    @Test
    fun `deleteFolder() with subfolders should throw FolderOperationException`() {
        assertFailure {
            testSubject.deleteFolder("nested")
        }.isInstanceOf<FolderOperationException>()
            .transform { it.serverResponseText }.isEqualTo("Mailbox has inferior hierarchical names")

        assertThat(demoStore.getFolderIds()).contains("nested")
    }

    @Test
    fun `deleteFolder() of inbox should throw FolderOperationException`() {
        assertFailure {
            testSubject.deleteFolder("inbox")
        }.isInstanceOf<FolderOperationException>()
    }

    @Test
    fun `changes should survive a new store instance and a folder list refresh`() {
        testSubject.createFolder("Projects")
        testSubject.renameFolder(folderServerId = "turing", newFolderServerId = "Alan", folderPathDelimiter = "/")
        testSubject.deleteFolder("archive")

        val newDemoStore = DemoStore(backendStorage)
        CommandRefreshFolderList(backendStorage, newDemoStore).refreshFolderList()

        assertThat(newDemoStore.getFolderIds()).containsExactlyInAnyOrder(
            "inbox", "drafts", "sent", "spam", "trash",
            "nested", "nested/nested_level_1", "nested/nested_level_1/nested_level_2",
            "Alan", "Projects",
        )
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder(
            "inbox", "drafts", "sent", "spam", "trash",
            "nested", "nested/nested_level_1", "nested/nested_level_1/nested_level_2",
            "Alan", "Projects",
        )
    }
}
