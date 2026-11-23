package net.thunderbird.app.common.feature.mail

import com.fsck.k9.backends.ImapBackendFactory
import com.fsck.k9.mailstore.DefaultSpecialFolderUpdater
import net.thunderbird.app.common.feature.mail.folder.DefaultFolderManagementRepository
import net.thunderbird.app.common.feature.mail.folder.DefaultFolderNameValidator
import net.thunderbird.app.common.feature.mail.folder.FolderHierarchyUpdater
import net.thunderbird.app.common.feature.mail.folder.FolderListRefresher
import net.thunderbird.app.common.feature.mail.folder.MessagingControllerFolderListRefresher
import net.thunderbird.app.common.feature.mail.message.mailMessageModule
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.backend.imap.DefaultImapRemoteFolderCreatorFactory
import net.thunderbird.backend.imap.DefaultImapRemoteFolderDeleterFactory
import net.thunderbird.backend.imap.DefaultImapRemoteFolderRenamerFactory
import net.thunderbird.backend.imap.ImapRemoteFolderCreatorFactory
import net.thunderbird.backend.imap.ImapRemoteFolderDeleterFactory
import net.thunderbird.backend.imap.ImapRemoteFolderRenamerFactory
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.feature.mail.folder.api.FolderNameValidator
import net.thunderbird.feature.mail.folder.api.SpecialFolderUpdater
import net.thunderbird.feature.mail.folder.api.data.repository.FolderManagementRepository
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Qualifiers of the maps of remote folder operation factories for development backends (e.g. `demo`), keyed by
 * protocol. Each app variant provides these maps, like the `developmentBackends` map.
 */
const val DEVELOPMENT_REMOTE_FOLDER_CREATORS = "developmentRemoteFolderCreators"
const val DEVELOPMENT_REMOTE_FOLDER_RENAMERS = "developmentRemoteFolderRenamers"
const val DEVELOPMENT_REMOTE_FOLDER_DELETERS = "developmentRemoteFolderDeleters"

internal val appCommonFeatureMailModule = module {

    includes(mailMessageModule)

    factory<SpecialFolderUpdater.Factory> {
        DefaultSpecialFolderUpdater.Factory(
            accountManager = get(),
            remoteFolderQueryRepository = get(),
            folderDetailsRepository = get(),
            specialFolderSelectionStrategy = get(),
            coroutineScope = get(named("AppCoroutineScope")),
            logger = get(),
        )
    }

    single<ImapRemoteFolderCreatorFactory> {
        DefaultImapRemoteFolderCreatorFactory(
            logger = get(),
            backendFactory = get<ImapBackendFactory>(),
        )
    }

    single<RemoteFolderCreator.Factory> {
        RemoteFolderCreatorResolver(
            accountManager = get(),
            imapFactory = get(),
            developmentFactories = get(named(DEVELOPMENT_REMOTE_FOLDER_CREATORS)),
        )
    }

    single<ImapRemoteFolderRenamerFactory> {
        DefaultImapRemoteFolderRenamerFactory(
            logger = get(),
            backendFactory = get<ImapBackendFactory>(),
        )
    }

    single<RemoteFolderRenamer.Factory> {
        RemoteFolderRenamerResolver(
            accountManager = get(),
            imapFactory = get(),
            developmentFactories = get(named(DEVELOPMENT_REMOTE_FOLDER_RENAMERS)),
        )
    }

    single<ImapRemoteFolderDeleterFactory> {
        DefaultImapRemoteFolderDeleterFactory(
            logger = get(),
            backendFactory = get<ImapBackendFactory>(),
        )
    }

    single<RemoteFolderDeleter.Factory> {
        RemoteFolderDeleterResolver(
            accountManager = get(),
            imapFactory = get(),
            developmentFactories = get(named(DEVELOPMENT_REMOTE_FOLDER_DELETERS)),
        )
    }

    single<FolderNameValidator> { DefaultFolderNameValidator() }

    factory {
        FolderHierarchyUpdater(
            backendStorageFactory = get(),
            remoteFolderCreatorFactory = get(),
            remoteFolderRenamerFactory = get(),
            remoteFolderDeleterFactory = get(),
            folderListRefresher = get(),
            logger = get(),
        )
    }

    factory<FolderListRefresher> {
        MessagingControllerFolderListRefresher(
            accountManager = get(),
            messagingController = get(),
        )
    }

    single<FolderManagementRepository> {
        val developmentProtocols = get<Map<String, RemoteFolderCreator.Factory>>(
            named(DEVELOPMENT_REMOTE_FOLDER_CREATORS),
        ).keys

        DefaultFolderManagementRepository(
            accountManager = get(),
            folderQueryRepository = get(),
            remoteFolderQueryRepository = get(),
            folderHierarchyUpdater = get(),
            folderNameValidator = get(),
            supportedProtocols = setOf(Protocols.IMAP) + developmentProtocols,
            logger = get(),
        )
    }
}
