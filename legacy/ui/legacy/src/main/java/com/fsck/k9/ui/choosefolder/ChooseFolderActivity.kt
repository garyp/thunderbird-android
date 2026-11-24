package com.fsck.k9.ui.choosefolder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.FolderIconProvider
import app.k9mail.legacy.ui.folder.FolderNameFormatter
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity
import com.fsck.k9.ui.managefolders.FolderNameDialogFragment
import com.fsck.k9.ui.managefolders.FolderOperation
import com.fsck.k9.ui.managefolders.FolderOperationEvent
import com.fsck.k9.ui.managefolders.setFolderOperationProgressVisible
import com.fsck.k9.ui.managefolders.showFolderOperationResult
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import java.util.Locale
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.mail.folder.FolderType
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

@Suppress("TooManyFunctions")
class ChooseFolderActivity : BaseActivity() {
    private val viewModel: ChooseFolderViewModel by viewModel()
    private val preferences: Preferences by inject()
    private val messagingController: MessagingController by inject()
    private val folderNameFormatter: FolderNameFormatter by inject()
    private val folderIconProvider: FolderIconProvider by inject { parametersOf(theme) }

    private lateinit var recyclerView: RecyclerView
    private lateinit var itemAdapter: ItemAdapter<FolderListItem>
    private lateinit var account: LegacyAccountDto
    private lateinit var action: Action
    private var currentFolderId: Long? = null
    private var excludedFolderIds: Set<Long> = emptySet()
    private var showTopLevel = false
    private var isFolderManagementSupported = false

    /**
     * Whether the user is choosing the parent of a new folder ("Create folder in…") instead of choosing a folder.
     */
    private var isChoosingNewFolderParent = false
    private var newFolderParentId: Long? = null
    private var newFolderParentName: String? = null
    private var displayFolders: List<DisplayFolder> = emptyList()
    private val exitChoosingNewFolderParentCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            setChoosingNewFolderParent(false)
        }
    }
    private var scrollToFolderId: Long? = null
    private var messageReference: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.folder_list)

        if (!decodeArguments(savedInstanceState)) {
            finish()
            return
        }

        if (savedInstanceState != null) {
            isChoosingNewFolderParent = savedInstanceState.getBoolean(STATE_CHOOSING_NEW_FOLDER_PARENT)
            newFolderParentId = savedInstanceState.getLongOrNull(STATE_NEW_FOLDER_PARENT_ID)
            newFolderParentName = savedInstanceState.getString(STATE_NEW_FOLDER_PARENT_NAME)
        }
        onBackPressedDispatcher.addCallback(this, exitChoosingNewFolderParentCallback)
        exitChoosingNewFolderParentCallback.isEnabled = isChoosingNewFolderParent
        updateTitle()

        initializeActionBar()
        initializeFolderList()

        viewModel.getFolders().observe(this) { folders ->
            updateFolderList(folders)
        }

        val savedShowHiddenFolders = savedInstanceState?.getBoolean(STATE_SHOW_HIDDEN_FOLDERS)
        val showHiddenFolders = savedShowHiddenFolders ?: false

        viewModel.setDisplayMode(account, showHiddenFolders)

        if (action.canCreateFolder) initializeCreateFolder()
    }

    private fun updateTitle() {
        when {
            isChoosingNewFolderParent -> setTitle(R.string.choose_folder_create_folder_in_title)
            action == Action.MOVE -> setTitle(R.string.choose_folder_move_title)
            action == Action.COPY -> setTitle(R.string.choose_folder_copy_title)
            action == Action.CHOOSE_PARENT -> setTitle(R.string.choose_folder_move_folder_title)
            else -> setTitle(R.string.choose_folder_title)
        }
    }

    /**
     * Switches between choosing a folder and choosing the parent of a new folder ("Create folder in…").
     */
    private fun setChoosingNewFolderParent(enabled: Boolean) {
        isChoosingNewFolderParent = enabled
        exitChoosingNewFolderParentCallback.isEnabled = enabled
        updateTitle()
        invalidateOptionsMenu()
        updateFolderList(displayFolders)
    }

    private fun initializeCreateFolder() {
        viewModel.getFolderManagementSupported(account).observe(this) { isSupported ->
            isFolderManagementSupported = isSupported
            invalidateOptionsMenu()
        }

        viewModel.getOperationInProgress().observe(this) { inProgress ->
            setFolderOperationProgressVisible(this, supportFragmentManager, inProgress)
        }

        viewModel.getCreateFolderResults().observe(this) { result ->
            handleCreateFolderResult(result)
        }

        supportFragmentManager.setFragmentResultListener(REQUEST_CREATE_FOLDER, this) { _, result ->
            result.getString(FolderNameDialogFragment.RESULT_FOLDER_NAME)?.let { name ->
                viewModel.createFolder(
                    account = account,
                    parentFolderId = newFolderParentId?.takeUnless { it == TOP_LEVEL_ID },
                    parentDisplayName = newFolderParentName,
                    name = name,
                )
            }
        }
    }

    private fun showCreateFolderDialog(parent: FolderListItem) {
        val isTopLevel = parent.databaseId == TOP_LEVEL_ID
        newFolderParentId = parent.databaseId
        newFolderParentName = if (isTopLevel) null else parent.displayName

        FolderNameDialogFragment.show(
            fragmentManager = supportFragmentManager,
            requestKey = REQUEST_CREATE_FOLDER,
            title = getString(
                if (isTopLevel) R.string.create_folder_dialog_title else R.string.create_subfolder_dialog_title,
            ),
            confirmText = getString(R.string.create_folder_dialog_confirm_action),
            folderPathDelimiter = account.folderPathDelimiter,
            isTopLevel = isTopLevel,
        )
    }

    private fun handleCreateFolderResult(result: CreateFolderResult) {
        when (result) {
            is CreateFolderResult.Created -> {
                val folderId = result.folderId
                if (folderId != null) {
                    // Choose the new folder right away, e.g. to move a message into it.
                    returnResult(folderId, result.displayName)
                } else {
                    // The folder isn't in the folder list yet. The user can choose it once it shows up.
                    showFolderOperationResult(FolderOperationEvent(FolderOperation.CREATE, error = null))
                    setChoosingNewFolderParent(false)
                }
            }
            // Stay in "Create folder in…", so the user can try again.
            is CreateFolderResult.Failed -> showFolderOperationResult(result.event)
        }
    }

    private fun decodeArguments(savedInstanceState: Bundle?): Boolean {
        action = intent.action?.toAction() ?: error("Missing Intent action")

        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT) ?: return false
        account = preferences.getAccount(accountUuid) ?: return false

        messageReference = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE)
        currentFolderId = intent.getLongExtraOrNull(EXTRA_CURRENT_FOLDER_ID)
        excludedFolderIds = intent.getLongArrayExtra(EXTRA_EXCLUDED_FOLDER_IDS)?.toSet().orEmpty()
        showTopLevel = intent.getBooleanExtra(EXTRA_SHOW_TOP_LEVEL, false)

        scrollToFolderId = if (savedInstanceState != null) {
            savedInstanceState.getLongOrNull(STATE_SCROLL_TO_FOLDER_ID)
        } else {
            intent.getLongExtraOrNull(EXTRA_SCROLL_TO_FOLDER_ID)
        }

        return true
    }

    private fun initializeActionBar() {
        val actionBar = supportActionBar ?: error("Action bar missing")
        actionBar.setDisplayHomeAsUpEnabled(true)
        actionBar.setHomeAsUpIndicator(Icons.Outlined.Close)
    }

    private fun initializeFolderList() {
        itemAdapter = ItemAdapter()
        itemAdapter.itemFilter.filterPredicate = ::folderListFilter

        val folderListAdapter = FastAdapter.with(itemAdapter).apply {
            setHasStableIds(true)
            onClickListener = { _, _, item: FolderListItem, _ ->
                if (isChoosingNewFolderParent) {
                    showCreateFolderDialog(parent = item)
                } else {
                    returnResult(item.databaseId, item.displayName)
                }
                true
            }
        }

        recyclerView = findViewById(R.id.folderList)
        recyclerView.adapter = folderListAdapter
    }

    private fun updateFolderList(displayFolders: List<DisplayFolder>) {
        this.displayFolders = displayFolders

        val folderListItems = displayFolders.asSequence()
            .filterNot { it.folder.type == FolderType.OUTBOX }
            .filterNot { it.folder.id == currentFolderId && !canBeNewFolderParent(it) }
            .filterNot { it.folder.id in excludedFolderIds }
            // Local-only folders can't have subfolders
            .filterNot { isChoosingNewFolderParent && it.folder.isLocalOnly }
            .map { displayFolder ->
                val databaseId = displayFolder.folder.id
                val folderIconResource = folderIconProvider.getFolderIcon(displayFolder.folder.type)
                val displayName = folderNameFormatter.displayName(displayFolder.folder)

                FolderListItem(databaseId, folderIconResource, displayName)
            }
            .toList()
            .let { items ->
                if (showTopLevel || isChoosingNewFolderParent) listOf(createTopLevelItem()) + items else items
            }

        itemAdapter.set(folderListItems)

        scrollToFolder(folderListItems)
    }

    /**
     * When moving messages, the folder they are in can still be the parent of a new folder. When moving a folder, the
     * folder itself can't.
     */
    private fun canBeNewFolderParent(displayFolder: DisplayFolder): Boolean {
        return isChoosingNewFolderParent && action != Action.CHOOSE_PARENT && !displayFolder.folder.isLocalOnly
    }

    private fun createTopLevelItem(): FolderListItem {
        return FolderListItem(
            databaseId = TOP_LEVEL_ID,
            folderIconResource = folderIconProvider.getFolderIcon(FolderType.REGULAR),
            displayName = getString(R.string.choose_folder_top_level),
        )
    }

    private fun scrollToFolder(folders: List<FolderListItem>) {
        if (scrollToFolderId == null) return

        val index = folders.indexOfFirst { it.databaseId == scrollToFolderId }
        if (index != -1) {
            recyclerView.scrollToPosition(index)
        }

        scrollToFolderId = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        scrollToFolderId?.let { folderId -> outState.putLong(STATE_SCROLL_TO_FOLDER_ID, folderId) }
        outState.putBoolean(STATE_SHOW_HIDDEN_FOLDERS, viewModel.isShowHiddenFolders)
        outState.putBoolean(STATE_CHOOSING_NEW_FOLDER_PARENT, isChoosingNewFolderParent)
        newFolderParentId?.let { outState.putLong(STATE_NEW_FOLDER_PARENT_ID, it) }
        outState.putString(STATE_NEW_FOLDER_PARENT_NAME, newFolderParentName)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.choose_folder_option, menu)
        configureFolderSearchView(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.toggle_hidden_folders)?.setChecked(viewModel.isShowHiddenFolders)
        menu?.findItem(R.id.create_folder)?.isVisible =
            action.canCreateFolder && isFolderManagementSupported && !isChoosingNewFolderParent
        return super.onPrepareOptionsMenu(menu)
    }

    private fun configureFolderSearchView(menu: Menu) {
        val folderMenuItem = menu.findItem(R.id.filter_folders)
        val folderSearchView = folderMenuItem.actionView as SearchView
        folderSearchView.maxWidth = Int.MAX_VALUE
        folderSearchView.queryHint = getString(R.string.folder_list_filter_hint)
        folderSearchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    itemAdapter.filter(query)
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    itemAdapter.filter(newText)
                    return true
                }
            },
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> if (isChoosingNewFolderParent) setChoosingNewFolderParent(false) else finish()
            R.id.create_folder -> setChoosingNewFolderParent(true)
            R.id.toggle_hidden_folders -> setShowHiddenFolders(item.isChecked.not())
            R.id.list_folders -> refreshFolderList()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refreshFolderList() {
        messagingController.refreshFolderList(account)
    }

    private fun setShowHiddenFolders(enabled: Boolean) {
        viewModel.setDisplayMode(account, enabled)
    }

    private fun returnResult(folderId: Long, displayName: String) {
        val result = Intent().apply {
            if (folderId == TOP_LEVEL_ID) {
                putExtra(RESULT_TOP_LEVEL, true)
            } else {
                putExtra(RESULT_SELECTED_FOLDER_ID, folderId)
            }
            putExtra(RESULT_FOLDER_DISPLAY_NAME, displayName)
            putExtra(RESULT_MESSAGE_REFERENCE, messageReference)
        }

        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun folderListFilter(item: FolderListItem, constraint: CharSequence?): Boolean {
        if (constraint.isNullOrEmpty()) return true

        val locale = Locale.getDefault()
        val displayName = item.displayName.lowercase(locale)
        return constraint.splitToSequence(" ")
            .filter { it.isNotEmpty() }
            .map { it.lowercase(locale) }
            .any { it in displayName }
    }

    private fun Intent.getLongExtraOrNull(name: String): Long? {
        if (!hasExtra(name)) return null

        val value = getLongExtra(name, -1L)
        return if (value != -1L) value else null
    }

    private fun Bundle.getLongOrNull(name: String): Long? {
        return if (containsKey(name)) getLong(name) else null
    }

    private fun String.toAction() = Action.valueOf(this)

    private val Action.canCreateFolder: Boolean
        get() = this == Action.MOVE || this == Action.COPY || this == Action.CHOOSE_PARENT

    enum class Action {
        MOVE,
        COPY,
        CHOOSE,

        /**
         * Choose the new parent of a folder that is moved. Offers a "Top level" entry if requested.
         */
        CHOOSE_PARENT,
    }

    companion object {
        private const val STATE_SCROLL_TO_FOLDER_ID = "scrollToFolderId"
        private const val STATE_SHOW_HIDDEN_FOLDERS = "showHiddenFolders"
        private const val STATE_CHOOSING_NEW_FOLDER_PARENT = "choosingNewFolderParent"
        private const val STATE_NEW_FOLDER_PARENT_ID = "newFolderParentId"
        private const val STATE_NEW_FOLDER_PARENT_NAME = "newFolderParentName"
        private const val EXTRA_ACCOUNT = "accountUuid"
        private const val EXTRA_CURRENT_FOLDER_ID = "currentFolderId"
        private const val EXTRA_SCROLL_TO_FOLDER_ID = "scrollToFolderId"
        private const val EXTRA_MESSAGE_REFERENCE = "messageReference"
        private const val EXTRA_EXCLUDED_FOLDER_IDS = "excludedFolderIds"
        private const val EXTRA_SHOW_TOP_LEVEL = "showTopLevel"
        private const val TOP_LEVEL_ID = -1L
        private const val REQUEST_CREATE_FOLDER = "create_folder"
        const val RESULT_SELECTED_FOLDER_ID = "selectedFolderId"
        const val RESULT_FOLDER_DISPLAY_NAME = "folderDisplayName"
        const val RESULT_MESSAGE_REFERENCE = "messageReference"
        const val RESULT_TOP_LEVEL = "topLevel"

        @JvmStatic
        fun buildLaunchIntent(
            context: Context,
            action: Action,
            accountUuid: String,
            currentFolderId: Long? = null,
            scrollToFolderId: Long? = null,
            messageReference: MessageReference? = null,
            excludedFolderIds: Set<Long> = emptySet(),
            showTopLevel: Boolean = false,
        ): Intent {
            return Intent(context, ChooseFolderActivity::class.java).apply {
                this.action = action.toString()
                putExtra(EXTRA_ACCOUNT, accountUuid)
                currentFolderId?.let { putExtra(EXTRA_CURRENT_FOLDER_ID, currentFolderId) }
                scrollToFolderId?.let { putExtra(EXTRA_SCROLL_TO_FOLDER_ID, scrollToFolderId) }
                messageReference?.let { putExtra(EXTRA_MESSAGE_REFERENCE, it.toIdentityString()) }
                if (excludedFolderIds.isNotEmpty()) putExtra(EXTRA_EXCLUDED_FOLDER_IDS, excludedFolderIds.toLongArray())
                putExtra(EXTRA_SHOW_TOP_LEVEL, showTopLevel)
            }
        }
    }
}
