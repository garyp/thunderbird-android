package net.thunderbird.backend.imap

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.folders.FolderServerId
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.logging.testing.TestLogger
import org.junit.Test

class ImapRemoteFolderDeleterTest {
    private val server = FakeImapServer()
    private val testSubject = ImapRemoteFolderDeleter(TestLogger(), server)

    @Test
    fun `delete() should delete folder on the server and unsubscribe`() = runTest {
        // Arrange
        server.mailboxes += listOf("Folder", "Other")
        server.subscriptions += listOf("Folder", "Other")

        // Act
        val outcome = testSubject.delete(FolderServerId("Folder"))

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Other")
        assertThat(server.subscriptions).containsExactlyInAnyOrder("Other")
    }

    @Test
    fun `delete() refused by the server should return RejectedByServer with server message`() = runTest {
        // Arrange
        server.mailboxes += "Folder"
        server.refuseDeleteWith = "Mailbox is in use"

        // Act
        val outcome = testSubject.delete(FolderServerId("Folder"))

        // Assert
        assertThat(outcome).isEqualTo(
            Outcome.failure(RemoteFolderOperationError.RejectedByServer(serverMessage = "Mailbox is in use")),
        )
        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Folder")
    }

    @Test
    fun `delete() of a folder already deleted on the server should succeed`() = runTest {
        // Arrange: the folder doesn't exist on the server

        // Act
        val outcome = testSubject.delete(FolderServerId("Folder"))

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
    }

    @Test
    fun `delete() of a folder with subfolders on the server should return HasSubfolders`() = runTest {
        // Arrange: the subfolder may be missing from the local folder list, e.g. because it isn't subscribed
        server.mailboxes += listOf("Parent", "Parent/Child")

        // Act
        val outcome = testSubject.delete(FolderServerId("Parent"))

        // Assert
        assertThat(outcome).isEqualTo(Outcome.failure(RemoteFolderOperationError.HasSubfolders))
        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Parent", "Parent/Child")
    }

    @Test
    fun `delete() refused for a non-selectable folder should return RejectedByServer`() = runTest {
        // Arrange
        server.mailboxes += "Folder"
        server.noSelectMailboxes += "Folder"
        server.refuseDeleteWith = "Permission denied"

        // Act
        val outcome = testSubject.delete(FolderServerId("Folder"))

        // Assert
        assertThat(outcome).isEqualTo(
            Outcome.failure(RemoteFolderOperationError.RejectedByServer(serverMessage = "Permission denied")),
        )
    }
}
