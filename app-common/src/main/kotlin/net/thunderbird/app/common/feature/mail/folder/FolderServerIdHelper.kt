package net.thunderbird.app.common.feature.mail.folder

import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * Helpers to work with the hierarchical server ID of a folder, e.g. `Parent/Child`.
 */
internal object FolderServerIdHelper {
    fun buildServerId(parentServerId: String?, name: String, folderPathDelimiter: FolderPathDelimiter): String {
        return if (parentServerId == null) name else "$parentServerId$folderPathDelimiter$name"
    }

    fun getParentServerId(serverId: String, folderPathDelimiter: FolderPathDelimiter): String? {
        if (folderPathDelimiter.isEmpty()) return null

        val index = serverId.lastIndexOf(folderPathDelimiter)
        return if (index > 0) serverId.substring(0, index) else null
    }

    fun getName(serverId: String, folderPathDelimiter: FolderPathDelimiter): String {
        val parentServerId = getParentServerId(serverId, folderPathDelimiter) ?: return serverId
        return serverId.substring(parentServerId.length + folderPathDelimiter.length)
    }

    fun isSubfolder(serverId: String, parentServerId: String, folderPathDelimiter: FolderPathDelimiter): Boolean {
        return folderPathDelimiter.isNotEmpty() && serverId.startsWith("$parentServerId$folderPathDelimiter")
    }
}
