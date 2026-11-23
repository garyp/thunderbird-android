package net.thunderbird.app.common.feature.mail

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import com.fsck.k9.mail.folders.FolderServerId
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.app.common.account.data.FakeLegacyAccountManager
import net.thunderbird.app.common.feature.mail.folder.FakeRemoteFolderOperations
import net.thunderbird.backend.api.folder.RemoteFolderOperationError
import net.thunderbird.backend.imap.ImapRemoteFolderDeleterFactory
import net.thunderbird.backend.imap.ImapRemoteFolderRenamerFactory
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.feature.account.AccountId

class RemoteFolderOperationResolverTest {
    private val imapOperations = FakeRemoteFolderOperations()
    private val demoOperations = FakeRemoteFolderOperations()
    private val accountManager = FakeLegacyAccountManager()

    @Test
    fun `renamer resolver should use IMAP factory for IMAP accounts`() = runTest {
        // Arrange
        val accountId = addAccount(Protocols.IMAP)
        val testSubject = createRenamerResolver()

        // Act
        val result = testSubject.create(accountId)

        // Assert
        assertThat(result).isSameInstanceAs(imapOperations)
    }

    @Test
    fun `renamer resolver should use development factory for its protocol`() = runTest {
        // Arrange
        val accountId = addAccount("demo")
        val testSubject = createRenamerResolver()

        // Act
        val result = testSubject.create(accountId)

        // Assert
        assertThat(result).isSameInstanceAs(demoOperations)
    }

    @Test
    fun `renamer resolver should return NoOp renamer that fails for POP3 accounts`() = runTest {
        // Arrange
        val accountId = addAccount(Protocols.POP3)
        val testSubject = createRenamerResolver()

        // Act
        val renamer = testSubject.create(accountId)
        val result = renamer.rename(FolderServerId("a"), FolderServerId("b"), folderPathDelimiter = "/")

        // Assert
        assertThat(renamer).isSameInstanceAs(NoOpRemoteFolderRenamer)
        assertThat(result).isEqualTo(Outcome.failure(RemoteFolderOperationError.NotSupportedOperation))
    }

    @Test
    fun `deleter resolver should use IMAP factory for IMAP accounts`() = runTest {
        // Arrange
        val accountId = addAccount(Protocols.IMAP)
        val testSubject = createDeleterResolver()

        // Act
        val result = testSubject.create(accountId)

        // Assert
        assertThat(result).isSameInstanceAs(imapOperations)
    }

    @Test
    fun `deleter resolver should return NoOp deleter that fails for POP3 accounts`() = runTest {
        // Arrange
        val accountId = addAccount(Protocols.POP3)
        val testSubject = createDeleterResolver()

        // Act
        val deleter = testSubject.create(accountId)
        val result = deleter.delete(FolderServerId("a"))

        // Assert
        assertThat(deleter).isSameInstanceAs(NoOpRemoteFolderDeleter)
        assertThat(result).isEqualTo(Outcome.failure(RemoteFolderOperationError.NotSupportedOperation))
    }

    @Test
    fun `creator resolver should use development factory for its protocol`() = runTest {
        // Arrange
        val accountId = addAccount("demo")
        val testSubject = RemoteFolderCreatorResolver(
            accountManager = accountManager,
            imapFactory = FakeImapRemoteFolderCreatorFactory(),
            developmentFactories = mapOf("demo" to demoOperations),
        )

        // Act
        val result = testSubject.create(accountId)

        // Assert
        assertThat(result).isSameInstanceAs(demoOperations)
    }

    private suspend fun addAccount(protocol: String): AccountId {
        val account = FakeData.legacyAccount.copy(
            incomingServerSettings = FakeData.legacyAccount.incomingServerSettings.copy(type = protocol),
        )
        accountManager.update(account)
        return account.id
    }

    private fun createRenamerResolver() = RemoteFolderRenamerResolver(
        accountManager = accountManager,
        imapFactory = object : ImapRemoteFolderRenamerFactory {
            override suspend fun create(accountId: AccountId) = imapOperations
        },
        developmentFactories = mapOf("demo" to demoOperations),
    )

    private fun createDeleterResolver() = RemoteFolderDeleterResolver(
        accountManager = accountManager,
        imapFactory = object : ImapRemoteFolderDeleterFactory {
            override suspend fun create(accountId: AccountId) = imapOperations
        },
        developmentFactories = mapOf("demo" to demoOperations),
    )
}
