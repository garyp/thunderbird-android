package com.fsck.k9.storage.messages

import android.content.ContentValues
import com.fsck.k9.mailstore.LockableDatabase

private val GMAIL_PREFIX_REGEX = "\\[(Gmail|Google Mail)]/".toRegex()

/**
 * Removes the `[Gmail]/` or `[Google Mail]/` prefix Gmail uses for its special folders from a folder's display name.
 */
internal fun removeGmailPrefix(folderName: String): String = folderName.replace(GMAIL_PREFIX_REGEX, "")

internal class FolderNameSanitizer(private val lockableDatabase: LockableDatabase) {
    fun removeGmailPrefixFromFolders() {
        lockableDatabase.execute(false) { db ->
            val cursor = db.query(
                "folders",
                arrayOf("id", "name"),
                "name LIKE ? OR name LIKE ?",
                arrayOf("%[Gmail]/%", "%[Google Mail]/%"),
                null,
                null,
                null,
            )

            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val updatedName = removeGmailPrefix(name)

                val values = ContentValues().apply {
                    put("name", updatedName)
                }

                db.update("folders", values, "id = ?", arrayOf(id.toString()))
            }

            cursor.close()
        }
    }
}
