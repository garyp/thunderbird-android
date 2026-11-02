package com.fsck.k9.backend.imap

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fsck.k9.backend.api.FolderAlreadyExistsException
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.FolderOperationException
import com.fsck.k9.backend.api.createFolder
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.store.imap.FolderListItem
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore
import net.thunderbird.core.logging.testing.TestLogger
import org.junit.Test

class CommandManageFolderTest {
    private val server = FakeImapServer()
    private val backendStorage = InMemoryBackendStorage()
    private val testSubject = CommandManageFolder(backendStorage, server, TestLogger())

    @Test
    fun `createFolder() should create and subscribe folder on server and add it locally`() {
        testSubject.createFolder("Parent/Child")

        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Parent/Child")
        assertThat(server.subscriptions).containsExactlyInAnyOrder("Parent/Child")
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder("Parent/Child")
        assertThat(backendStorage.getFolder("Parent/Child").name).isEqualTo("Parent/Child")
    }

    @Test
    fun `createFolder() with existing folder on server should throw FolderAlreadyExistsException`() {
        server.mailboxes += "Existing"

        assertFailure {
            testSubject.createFolder("Existing")
        }.isInstanceOf<FolderAlreadyExistsException>()

        assertThat(backendStorage.getFolderServerIds()).isEqualTo(emptyList())
    }

    @Test
    fun `createFolder() when server refuses should throw and not create local folder`() {
        server.refuseCreate = true

        assertFailure {
            testSubject.createFolder("Folder")
        }.isInstanceOf<FolderOperationException>()

        assertThat(backendStorage.getFolderServerIds()).isEqualTo(emptyList())
    }

    @Test
    fun `createFolder() should succeed even if SUBSCRIBE fails`() {
        server.refuseSubscriptionChanges = true

        testSubject.createFolder("Folder")

        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Folder")
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder("Folder")
    }

    @Test
    fun `renameFolder() should rename folder and subfolders locally and move subscriptions`() {
        createFolders("Old", "Old/Child", "Old/Child/Grandchild", "Oldie", "Other")
        server.subscriptions += listOf("Old", "Old/Child/Grandchild", "Oldie")

        testSubject.renameFolder(folderServerId = "Old", newFolderServerId = "New", folderPathDelimiter = "/")

        assertThat(server.mailboxes).containsExactlyInAnyOrder(
            "INBOX",
            "New",
            "New/Child",
            "New/Child/Grandchild",
            "Oldie",
            "Other",
        )
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder(
            "New",
            "New/Child",
            "New/Child/Grandchild",
            "Oldie",
            "Other",
        )
        assertThat(backendStorage.getFolder("New/Child").name).isEqualTo("New/Child")
        assertThat(server.subscriptions).containsExactlyInAnyOrder("New", "New/Child/Grandchild", "Oldie")
    }

    @Test
    fun `renameFolder() should keep local folder settings`() {
        createFolders("Old")
        backendStorage.getFolder("Old").type = FolderType.TRASH

        testSubject.renameFolder(folderServerId = "Old", newFolderServerId = "New", folderPathDelimiter = "/")

        assertThat(backendStorage.getFolder("New").type).isEqualTo(FolderType.TRASH)
    }

    @Test
    fun `renameFolder() to existing name should throw FolderAlreadyExistsException`() {
        createFolders("Old", "New")

        assertFailure {
            testSubject.renameFolder(folderServerId = "Old", newFolderServerId = "New", folderPathDelimiter = "/")
        }.isInstanceOf<FolderAlreadyExistsException>()

        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Old", "New")
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder("Old", "New")
    }

    @Test
    fun `renameFolder() when server refuses should throw with server response text`() {
        createFolders("Old")
        server.refuseRenameWith = "Permission denied"

        assertFailure {
            testSubject.renameFolder(folderServerId = "Old", newFolderServerId = "New", folderPathDelimiter = "/")
        }.isInstanceOf<FolderOperationException>()
            .transform { it.serverResponseText }.isEqualTo("Permission denied")

        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder("Old")
    }

    @Test
    fun `deleteFolder() should delete folder on server and locally and unsubscribe`() {
        createFolders("Folder", "Other")
        server.subscriptions += listOf("Folder", "Other")

        testSubject.deleteFolder("Folder")

        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Other")
        assertThat(server.subscriptions).containsExactlyInAnyOrder("Other")
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder("Other")
    }

    @Test
    fun `deleteFolder() when server refuses should throw and keep local folder`() {
        createFolders("Folder")
        server.refuseDeleteWith = "Mailbox is in use"

        assertFailure {
            testSubject.deleteFolder("Folder")
        }.isInstanceOf<FolderOperationException>()
            .transform { it.serverResponseText }.isEqualTo("Mailbox is in use")

        assertThat(server.mailboxes).containsExactlyInAnyOrder("INBOX", "Folder")
        assertThat(backendStorage.getFolderServerIds()).containsExactlyInAnyOrder("Folder")
    }

    @Test
    fun `deleteFolder() with folder already deleted on server should remove it locally`() {
        createFolders("Folder")
        server.mailboxes -= "Folder"

        testSubject.deleteFolder("Folder")

        assertThat(backendStorage.getFolderServerIds()).isEqualTo(emptyList())
    }

    private fun createFolders(vararg serverIds: String) {
        backendStorage.updateFolders {
            for (serverId in serverIds) {
                createFolder(FolderInfo(serverId = serverId, name = serverId, type = FolderType.REGULAR))
            }
        }
        server.mailboxes += serverIds
    }
}

/**
 * Simulates the mailbox and subscription state of an IMAP server with "/" as hierarchy delimiter.
 */
private class FakeImapServer : ImapStore {
    val mailboxes = mutableSetOf("INBOX")
    val subscriptions = mutableSetOf<String>()

    var refuseCreate = false
    var refuseRenameWith: String? = null
    var refuseDeleteWith: String? = null
    var refuseSubscriptionChanges = false

    override val combinedPrefix: String? = null

    override fun getFolder(name: String): ImapFolder = FakeServerFolder(name)

    override fun getFolders(): List<FolderListItem> = throw UnsupportedOperationException("not implemented")

    override fun checkSettings() = throw UnsupportedOperationException("not implemented")

    override fun closeAllConnections() = throw UnsupportedOperationException("not implemented")

    override fun fetchImapPrefix() = throw UnsupportedOperationException("not implemented")

    private inner class FakeServerFolder(serverId: String) : TestImapFolder(serverId) {
        override fun exists(): Boolean = serverId in mailboxes

        override fun create(folderType: FolderType): Boolean {
            if (refuseCreate || serverId in mailboxes) return false
            mailboxes += serverId
            return true
        }

        override fun delete() {
            refuseDeleteWith?.let { throw ImapFolderOperationException("DELETE command failed", it) }
            if (!mailboxes.remove(serverId)) {
                throw ImapFolderOperationException("DELETE command failed", "Mailbox doesn't exist")
            }
        }

        override fun rename(newServerId: String) {
            refuseRenameWith?.let { throw ImapFolderOperationException("RENAME command failed", it) }
            if (serverId !in mailboxes || newServerId in mailboxes) {
                throw ImapFolderOperationException("RENAME command failed", null)
            }

            // RFC 3501, section 6.3.5: inferior hierarchical names are renamed as well
            val renamed = mailboxes.filter { it == serverId || it.startsWith("$serverId/") }
            mailboxes -= renamed.toSet()
            mailboxes += renamed.map { newServerId + it.removePrefix(serverId) }
        }

        override fun subscribe() {
            if (refuseSubscriptionChanges) throw ImapFolderOperationException("SUBSCRIBE command failed", null)
            subscriptions += serverId
        }

        override fun unsubscribe() {
            if (refuseSubscriptionChanges || !subscriptions.remove(serverId)) {
                throw ImapFolderOperationException("UNSUBSCRIBE command failed", null)
            }
        }
    }
}
