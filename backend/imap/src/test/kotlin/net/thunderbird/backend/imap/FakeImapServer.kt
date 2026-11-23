package net.thunderbird.backend.imap

import com.fsck.k9.backend.imap.TestImapFolder
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.store.imap.FolderListItem
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore

/**
 * Simulates the mailbox and subscription state of an IMAP server with "/" as hierarchy delimiter.
 */
internal class FakeImapServer : ImapStore {
    val mailboxes = mutableSetOf("INBOX")
    val subscriptions = mutableSetOf<String>()

    var refuseRenameWith: String? = null
    var refuseDeleteWith: String? = null
    var refuseSubscriptionChanges = false

    override val combinedPrefix: String? = null

    override fun getFolder(name: String): ImapFolder = FakeServerFolder(name)

    override fun getFolders(): List<FolderListItem> {
        return mailboxes.map { FolderListItem(serverId = it, name = it, type = FolderType.REGULAR, "/") }
    }

    override fun checkSettings() = throw UnsupportedOperationException("not implemented")

    override fun closeAllConnections() = throw UnsupportedOperationException("not implemented")

    override fun fetchImapPrefix() = throw UnsupportedOperationException("not implemented")

    private inner class FakeServerFolder(serverId: String) : TestImapFolder(serverId) {
        override fun exists(): Boolean = serverId in mailboxes

        override fun create(folderType: FolderType): Boolean {
            if (serverId in mailboxes) {
                throw ImapFolderOperationException("CREATE command failed", "Mailbox already exists")
            }
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

            // RFC 9051, section 6.3.6: inferior hierarchical names are renamed as well
            val renamed = mailboxes.filter { it == serverId || it.startsWith("$serverId/") }
            mailboxes -= renamed.toSet()
            mailboxes += renamed.map { newServerId + it.removePrefix(serverId) }
        }

        override fun subscribe() {
            if (refuseSubscriptionChanges) throw ImapFolderOperationException("SUBSCRIBE command failed", null)
            subscriptions += serverId
        }

        override fun unsubscribe() {
            // Like many servers, return OK even when the name isn't subscribed
            if (refuseSubscriptionChanges) throw ImapFolderOperationException("UNSUBSCRIBE command failed", null)
            subscriptions -= serverId
        }

        override fun isSubscribed(): Boolean = serverId in subscriptions
    }
}
