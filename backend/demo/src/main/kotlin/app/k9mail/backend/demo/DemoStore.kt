package app.k9mail.backend.demo

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val EXTRA_DEMO_FOLDERS = "demoFolders"

/**
 * The demo "server". Its folder hierarchy starts out as the bundled demo mailbox and can then be changed by creating,
 * renaming, and deleting folders. Changes are persisted using [BackendStorage], so they survive app restarts and folder
 * list refreshes.
 *
 * The folder hierarchy is read from [BackendStorage] on every access rather than cached, because several instances
 * exist for the same account: one owned by [DemoBackend] and others used by the demo remote folder operations.
 */
internal class DemoStore(
    private val backendStorage: BackendStorage,
    private val demoDataLoader: DemoDataLoader = DemoDataLoader(),
) {
    private fun loadFolders(): MutableMap<String, DemoStoreFolder> {
        return (loadPersistedFolders() ?: flattenDemoFolders(demoDataLoader.loadFolders())).toMutableMap()
    }

    fun getFolder(folderServerId: String): DemoStoreFolder? {
        return loadFolders()[folderServerId]
    }

    fun getFolderIds(): Set<String> {
        return loadFolders().keys
    }

    fun getInboxFolderId(): String {
        return loadFolders().filterValues { it.type == FolderType.INBOX }.keys.first()
    }

    fun getMessage(folderServerId: String, messageServerId: String): Message {
        val folder = getFolder(folderServerId) ?: error("Folder $folderServerId not found")
        val resourcePath = folder.resourcePath ?: error("Folder $folderServerId has no demo messages")
        return demoDataLoader.loadMessage(resourcePath, messageServerId)
    }

    fun addFolder(folderServerId: String, folder: DemoStoreFolder) {
        val folders = loadFolders()
        check(folderServerId !in folders) { "Folder $folderServerId already exists" }
        folders[folderServerId] = folder
        persistFolders(folders)
    }

    /**
     * Replaces the server IDs and names of existing folders. [renamedFolders] maps old server IDs to new server IDs and
     * names.
     */
    fun renameFolders(renamedFolders: Map<String, Pair<String, String>>) {
        val folders = loadFolders()
        val movedFolders = renamedFolders.map { (oldServerId, newServerIdAndName) ->
            val folder = folders.remove(oldServerId) ?: error("Folder $oldServerId not found")
            val (newServerId, newName) = newServerIdAndName
            newServerId to folder.copy(name = newName)
        }
        folders.putAll(movedFolders)
        persistFolders(folders)
    }

    fun removeFolder(folderServerId: String) {
        val folders = loadFolders()
        folders.remove(folderServerId)
        persistFolders(folders)
    }

    /**
     * Returns the persisted folder hierarchy, or `null` if there is none or it can't be read (e.g. after the format
     * changed). In the latter case the demo mailbox starts over from the bundled demo data.
     */
    private fun loadPersistedFolders(): Map<String, DemoStoreFolder>? {
        val json = backendStorage.getExtraString(EXTRA_DEMO_FOLDERS) ?: return null
        return runCatching { Json.decodeFromString<Map<String, DemoStoreFolder>>(json) }.getOrNull()
    }

    private fun persistFolders(folders: Map<String, DemoStoreFolder>) {
        backendStorage.setExtraString(EXTRA_DEMO_FOLDERS, Json.encodeToString<Map<String, DemoStoreFolder>>(folders))
    }

    // This is a workaround for the fact that the backend doesn't support nested folders
    private fun flattenDemoFolders(
        demoFolders: DemoFolders,
        parentName: String = "",
        parentServerId: String = "",
    ): Map<String, DemoStoreFolder> {
        val flatFolders = mutableMapOf<String, DemoStoreFolder>()
        for ((folderServerId, demoFolder) in demoFolders) {
            val fullName = if (parentName.isEmpty()) {
                demoFolder.name
            } else {
                "$parentName/${demoFolder.name}"
            }
            val fullServerId = if (parentServerId.isEmpty()) {
                folderServerId
            } else {
                "$parentServerId/$folderServerId"
            }
            flatFolders[fullServerId] = DemoStoreFolder(
                name = fullName,
                type = demoFolder.type,
                messageServerIds = demoFolder.messageServerIds,
                resourcePath = fullServerId,
            )

            val subFolders = demoFolder.subFolders
            if (subFolders != null) {
                flatFolders.putAll(
                    from = flattenDemoFolders(
                        demoFolders = subFolders,
                        parentName = fullName,
                        parentServerId = fullServerId,
                    ),
                )
            }
        }
        return flatFolders
    }
}

/**
 * A folder of the demo store.
 *
 * @property resourcePath Location of the folder's messages in the bundled demo mailbox. It doesn't change when the
 *   folder is renamed. `null` for folders created by the user.
 */
@Serializable
internal data class DemoStoreFolder(
    val name: String,
    val type: FolderType,
    val messageServerIds: List<String>,
    val resourcePath: String?,
)
