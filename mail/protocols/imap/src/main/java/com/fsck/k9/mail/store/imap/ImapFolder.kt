package com.fsck.k9.mail.store.imap

import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageRetrievalListener
import com.fsck.k9.mail.Part
import java.io.IOException
import java.util.Date
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag

interface ImapFolder {
    val serverId: String
    val mode: OpenMode?
    val messageCount: Int
    val isOpen: Boolean

    @Throws(MessagingException::class)
    fun exists(): Boolean

    @Throws(MessagingException::class)
    fun open(mode: OpenMode)

    fun close()

    fun getUidValidity(): Long?

    fun getMessage(uid: String): ImapMessage

    @Throws(MessagingException::class)
    fun getUidFromMessageId(messageId: String): String?

    @Throws(MessagingException::class)
    fun getMessages(
        start: Int,
        end: Int,
        earliestDate: Date?,
        listener: MessageRetrievalListener<ImapMessage>?,
    ): List<ImapMessage>

    @Throws(IOException::class, MessagingException::class)
    fun areMoreMessagesAvailable(indexOfOldestMessage: Int, earliestDate: Date?): Boolean

    @Throws(MessagingException::class)
    suspend fun fetch(
        messages: List<ImapMessage>,
        fetchProfile: FetchProfile,
        listener: FetchListener?,
        maxDownloadSize: Int,
    )

    @Throws(MessagingException::class)
    fun fetchPart(
        message: ImapMessage,
        part: Part,
        bodyFactory: BodyFactory,
        maxDownloadSize: Int,
    )

    @Throws(MessagingException::class)
    fun search(
        queryString: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<ImapMessage>

    @Throws(MessagingException::class)
    fun appendMessages(messages: List<Message>): Map<String, String>?

    @Throws(MessagingException::class)
    fun setFlagsForAllMessages(flags: Set<Flag>, value: Boolean)

    @Throws(MessagingException::class)
    fun setFlags(messages: List<ImapMessage>, flags: Set<Flag>, value: Boolean)

    @Throws(MessagingException::class)
    fun copyMessages(messages: List<ImapMessage>, folder: ImapFolder): Map<String, String>?

    @Throws(MessagingException::class)
    fun moveMessages(messages: List<ImapMessage>, folder: ImapFolder): Map<String, String>?

    @Throws(MessagingException::class)
    fun deleteMessages(messages: List<ImapMessage>)

    @Throws(MessagingException::class)
    fun deleteAllMessages()

    @Throws(MessagingException::class)
    fun expunge()

    @Throws(MessagingException::class)
    fun expungeUids(uids: List<String>)

    /**
     * Creates this folder on the IMAP server.
     *
     * @throws MessagingException when fails to create folder on IMAP server.
     */
    @Throws(MessagingException::class)
    fun create(folderType: FolderType = FolderType.REGULAR): Boolean

    /**
     * Permanently deletes this folder on the IMAP server using the `DELETE` command (RFC 3501, section 6.3.4;
     * RFC 9051, section 6.3.5).
     *
     * The server will refuse to delete `INBOX`. Depending on the server, deleting a folder that has subfolders either
     * fails or leaves the folder in place with the `\Noselect` attribute. Callers should make sure the folder has no
     * subfolders before calling this method.
     *
     * @throws ImapFolderOperationException if the server rejected the command.
     * @throws MessagingException in case of a network error.
     */
    @Throws(MessagingException::class)
    fun delete()

    /**
     * Renames this folder on the IMAP server using the `RENAME` command (RFC 3501, section 6.3.5; RFC 9051,
     * section 6.3.6).
     *
     * All subfolders are renamed by the server as well. Renaming `INBOX` is not supported, because it has special
     * semantics (it moves all messages to a new folder instead of renaming `INBOX`).
     *
     * This [ImapFolder] instance keeps referring to the old [serverId]. Use [ImapStore.getFolder] to get an instance
     * for the renamed folder.
     *
     * @param newServerId The new server ID (full hierarchical name, without the namespace prefix).
     *
     * @throws ImapFolderOperationException if the server rejected the command.
     * @throws MessagingException in case of a network error.
     */
    @Throws(MessagingException::class)
    fun rename(newServerId: String)

    /**
     * Adds this folder to the server's set of subscribed folders using the `SUBSCRIBE` command (RFC 3501,
     * section 6.3.6; RFC 9051, section 6.3.7).
     *
     * @throws ImapFolderOperationException if the server rejected the command.
     * @throws MessagingException in case of a network error.
     */
    @Throws(MessagingException::class)
    fun subscribe()

    /**
     * Removes this folder from the server's set of subscribed folders using the `UNSUBSCRIBE` command (RFC 3501,
     * section 6.3.7; RFC 9051, section 6.3.8).
     *
     * @throws ImapFolderOperationException if the server rejected the command.
     * @throws MessagingException in case of a network error.
     */
    @Throws(MessagingException::class)
    fun unsubscribe()
}

interface FetchListener {
    suspend fun onFetchResponse(message: ImapMessage, isFirstResponse: Boolean)
}
