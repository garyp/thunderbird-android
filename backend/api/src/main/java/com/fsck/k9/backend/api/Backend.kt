package com.fsck.k9.backend.api

import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

interface Backend {
    val supportsFlags: Boolean
    val supportsExpunge: Boolean
    val supportsMove: Boolean
    val supportsCopy: Boolean
    val supportsUpload: Boolean
    val supportsTrashFolder: Boolean
    val supportsSearchByDate: Boolean
    val supportsFolderSubscriptions: Boolean

    /**
     * Whether this backend supports creating, renaming, and deleting folders on the server.
     */
    val supportsFolderManagement: Boolean
    val isPushCapable: Boolean

    @Throws(MessagingException::class)
    fun refreshFolderList(): FolderPathDelimiter?

    /**
     * Creates a folder on the server and adds it to the local folder list.
     *
     * @param folderServerId The server ID of the new folder. For a subfolder this is the server ID of the parent
     *   folder, followed by the path delimiter and the name of the new folder.
     *
     * @throws FolderAlreadyExistsException if a folder with that server ID already exists on the server.
     * @throws FolderOperationException if the server refused to create the folder.
     * @throws MessagingException in case of other errors, e.g. network errors.
     */
    @Throws(MessagingException::class)
    fun createFolder(folderServerId: String)

    /**
     * Renames a folder on the server and updates the local folder list, keeping the locally stored messages and
     * folder settings.
     *
     * Subfolders are renamed as well.
     *
     * @param folderPathDelimiter The delimiter separating levels in the folder hierarchy. Used to find subfolders.
     *
     * @throws FolderAlreadyExistsException if a folder named [newFolderServerId] already exists on the server.
     * @throws FolderOperationException if the server refused to rename the folder.
     * @throws MessagingException in case of other errors, e.g. network errors.
     */
    @Throws(MessagingException::class)
    fun renameFolder(folderServerId: String, newFolderServerId: String, folderPathDelimiter: FolderPathDelimiter)

    /**
     * Deletes a folder on the server and removes it, including all locally stored messages, from the local folder
     * list.
     *
     * @throws FolderOperationException if the server refused to delete the folder.
     * @throws MessagingException in case of other errors, e.g. network errors.
     */
    @Throws(MessagingException::class)
    fun deleteFolder(folderServerId: String)

    // TODO: Add a way to cancel the sync process
    fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener)

    @Throws(MessagingException::class)
    fun downloadMessage(syncConfig: SyncConfig, folderServerId: String, messageServerId: String)

    @Throws(MessagingException::class)
    fun downloadMessageStructure(folderServerId: String, messageServerId: String)

    @Throws(MessagingException::class)
    suspend fun downloadCompleteMessage(folderServerId: String, messageServerId: String)

    @Throws(MessagingException::class)
    fun setFlag(folderServerId: String, messageServerIds: List<String>, flag: Flag, newState: Boolean)

    @Throws(MessagingException::class)
    fun markAllAsRead(folderServerId: String)

    @Throws(MessagingException::class)
    fun expunge(folderServerId: String)

    @Throws(MessagingException::class)
    fun deleteMessages(folderServerId: String, messageServerIds: List<String>)

    @Throws(MessagingException::class)
    fun deleteAllMessages(folderServerId: String)

    @Throws(MessagingException::class)
    fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>?

    @Throws(MessagingException::class)
    fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>?

    @Throws(MessagingException::class)
    fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>?

    @Throws(MessagingException::class)
    fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String>

    @Throws(MessagingException::class)
    fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory)

    @Throws(MessagingException::class)
    fun findByMessageId(folderServerId: String, messageId: String): String?

    @Throws(MessagingException::class)
    fun uploadMessage(folderServerId: String, message: Message): String?

    @Throws(MessagingException::class)
    fun sendMessage(message: Message)

    fun createPusher(callback: BackendPusherCallback): BackendPusher
}
