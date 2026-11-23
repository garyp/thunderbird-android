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

class ImapRemoteFolderRenamerTest {
    private val server = FakeImapServer()
    private val testSubject = ImapRemoteFolderRenamer(TestLogger(), server)

    @Test
    fun `rename() should rename folder and subfolders on the server`() = runTest {
        // Arrange
        server.mailboxes += listOf("Old", "Old/Child", "Old/Child/Grandchild", "Oldie")

        // Act
        val outcome = testSubject.rename(FolderServerId("Old"), FolderServerId("New"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(server.mailboxes).containsExactlyInAnyOrder(
            "INBOX",
            "New",
            "New/Child",
            "New/Child/Grandchild",
            "Oldie",
        )
    }

    @Test
    fun `rename() should move only subscriptions that existed before the rename`() = runTest {
        // Arrange
        server.mailboxes += listOf("Old", "Old/Child", "Old/Child/Grandchild", "Oldie")
        server.subscriptions += listOf("Old", "Old/Child/Grandchild", "Oldie")

        // Act
        testSubject.rename(FolderServerId("Old"), FolderServerId("New"), folderPathDelimiter = "/")

        // Assert
        assertThat(server.subscriptions).containsExactlyInAnyOrder("New", "New/Child/Grandchild", "Oldie")
    }

    @Test
    fun `rename() to existing name should return AlreadyExists`() = runTest {
        // Arrange
        server.mailboxes += listOf("Old", "New")

        // Act
        val outcome = testSubject.rename(FolderServerId("Old"), FolderServerId("New"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(Outcome.failure(RemoteFolderOperationError.AlreadyExists))
        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Old", "New")
    }

    @Test
    fun `rename() refused by the server should return RejectedByServer with server message`() = runTest {
        // Arrange
        server.mailboxes += "Old"
        server.refuseRenameWith = "Permission denied"

        // Act
        val outcome = testSubject.rename(FolderServerId("Old"), FolderServerId("New"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(
            Outcome.failure(RemoteFolderOperationError.RejectedByServer(serverMessage = "Permission denied")),
        )
    }

    @Test
    fun `rename() should succeed even if subscriptions can't be changed`() = runTest {
        // Arrange
        server.mailboxes += "Old"
        server.subscriptions += "Old"
        server.refuseSubscriptionChanges = true

        // Act
        val outcome = testSubject.rename(FolderServerId("Old"), FolderServerId("New"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "New")
    }

    @Test
    fun `rename() with namespace prefix should move subscriptions`() = runTest {
        // Arrange
        val prefixedServer = FakeImapServer(combinedPrefix = "INBOX/")
        prefixedServer.mailboxes += listOf("INBOX/Work", "INBOX/Work/2026")
        prefixedServer.subscriptions += listOf("INBOX/Work", "INBOX/Work/2026")
        val testSubject = ImapRemoteFolderRenamer(TestLogger(), prefixedServer)

        // Act
        val outcome = testSubject.rename(
            FolderServerId("INBOX/Work"),
            FolderServerId("INBOX/Projects"),
            folderPathDelimiter = "/",
        )

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(prefixedServer.mailboxes).containsExactlyInAnyOrder("INBOX", "INBOX/Projects", "INBOX/Projects/2026")
        assertThat(prefixedServer.subscriptions).containsExactlyInAnyOrder("INBOX/Projects", "INBOX/Projects/2026")
    }

    @Test
    fun `rename() that only changes the case should succeed on case-insensitive servers`() = runTest {
        // Arrange
        server.caseInsensitive = true
        server.mailboxes += "work"

        // Act
        val outcome = testSubject.rename(FolderServerId("work"), FolderServerId("Work"), folderPathDelimiter = "/")

        // Assert
        assertThat(outcome).isEqualTo(Outcome.success(Unit))
        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Work")
    }
}
