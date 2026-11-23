package net.thunderbird.backend.imap

import com.fsck.k9.backend.imap.TestImapFolder
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.store.imap.FolderListItem
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapFolderOperationException
import com.fsck.k9.mail.store.imap.ImapStore

private const val INBOX = "INBOX"

/**
 * Simulates the mailbox and subscription state of an IMAP server with "/" as hierarchy delimiter.
 *
 * Like [com.fsck.k9.mail.store.imap.RealImapStore], folder names passed to [getFolder] get the namespace prefix
 * ([combinedPrefix]) unless they already have it, and [getFolders] returns the full names including the prefix.
 * [mailboxes] and [subscriptions] contain full names.
 */
internal class FakeImapServer(
    override val combinedPrefix: String? = null,
) : ImapStore {
    val mailboxes = mutableSetOf(INBOX)
    val subscriptions = mutableSetOf<String>()

    /**
     * Mailboxes that exist but can't be selected (`\Noselect`), e.g. a parent left behind after deleting it.
     */
    val noSelectMailboxes = mutableSetOf<String>()

    /**
     * Whether mailbox names are case-insensitive, like on many servers.
     */
    var caseInsensitive = false

    var refuseRenameWith: String? = null
    var refuseDeleteWith: String? = null
    var refuseSubscriptionChanges = false

    override fun getFolder(name: String): ImapFolder = FakeServerFolder(prefixed(name))

    override fun getFolders(): List<FolderListItem> {
        return mailboxes.map { FolderListItem(serverId = it, name = it, type = FolderType.REGULAR, "/") }
    }

    override fun checkSettings() = throw UnsupportedOperationException("not implemented")

    override fun closeAllConnections() = throw UnsupportedOperationException("not implemented")

    override fun fetchImapPrefix() = throw UnsupportedOperationException("not implemented")

    private fun prefixed(name: String): String {
        val prefix = combinedPrefix.orEmpty()
        return if (name.equals(INBOX, ignoreCase = true)) name else prefix + name.removePrefix(prefix)
    }

    private fun Set<String>.containsMailbox(name: String): Boolean {
        return if (caseInsensitive) any { it.equals(name, ignoreCase = true) } else name in this
    }

    private inner class FakeServerFolder(serverId: String) : TestImapFolder(serverId) {
        override fun exists(): Boolean = mailboxes.containsMailbox(serverId) && serverId !in noSelectMailboxes

        override fun isListed(): Boolean = mailboxes.containsMailbox(serverId)

        override fun hasSubfolders(): Boolean = mailboxes.any { it.startsWith("$serverId/") }

        override fun create(folderType: FolderType): Boolean {
            if (mailboxes.containsMailbox(serverId)) {
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
            val newName = prefixed(newServerId)
            val isCaseChangeOnly = caseInsensitive && newName.equals(serverId, ignoreCase = true)
            val targetExists = mailboxes.containsMailbox(newName) && !isCaseChangeOnly
            if (serverId !in mailboxes || targetExists) {
                throw ImapFolderOperationException("RENAME command failed", null)
            }

            // RFC 9051, section 6.3.6: inferior hierarchical names are renamed as well
            val renamed = mailboxes.filter { it == serverId || it.startsWith("$serverId/") }
            mailboxes -= renamed.toSet()
            mailboxes += renamed.map { newName + it.removePrefix(serverId) }
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
