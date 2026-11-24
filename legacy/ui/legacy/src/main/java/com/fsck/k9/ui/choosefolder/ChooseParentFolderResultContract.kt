package com.fsck.k9.ui.choosefolder

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.fsck.k9.ui.choosefolder.ChooseFolderActivity.Action

/**
 * Lets the user choose the new parent of a folder that is moved.
 */
class ChooseParentFolderResultContract :
    ActivityResultContract<ChooseParentFolderResultContract.Input, ChooseParentFolderResultContract.Result?>() {

    override fun createIntent(context: Context, input: Input): Intent {
        return ChooseFolderActivity.buildLaunchIntent(
            context = context,
            action = Action.CHOOSE_PARENT,
            accountUuid = input.accountUuid,
            currentFolderId = input.folderId,
            excludedFolderIds = input.excludedFolderIds,
            showTopLevel = input.showTopLevel,
        )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null

        return if (intent.getBooleanExtra(ChooseFolderActivity.RESULT_TOP_LEVEL, false)) {
            Result(parentFolderId = null)
        } else {
            val folderId = intent.getLongExtra(ChooseFolderActivity.RESULT_SELECTED_FOLDER_ID, -1L)
            if (folderId == -1L) null else Result(parentFolderId = folderId)
        }
    }

    /**
     * @param folderId The folder that is moved. It isn't offered as a new parent.
     * @param excludedFolderIds Further folders that aren't offered as a new parent, e.g. the subfolders.
     * @param showTopLevel Whether to offer moving the folder to the top level.
     */
    data class Input(
        val accountUuid: String,
        val folderId: Long,
        val excludedFolderIds: Set<Long>,
        val showTopLevel: Boolean,
    )

    /**
     * @param parentFolderId The ID of the chosen parent folder, or `null` for the top level.
     */
    data class Result(
        val parentFolderId: Long?,
    )
}
