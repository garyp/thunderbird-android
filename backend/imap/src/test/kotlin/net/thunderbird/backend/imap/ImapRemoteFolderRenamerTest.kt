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
}
