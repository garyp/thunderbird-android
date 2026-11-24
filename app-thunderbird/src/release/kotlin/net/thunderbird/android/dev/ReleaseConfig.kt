package net.thunderbird.android.dev

import app.k9mail.autodiscovery.api.AutoDiscovery
import net.thunderbird.app.common.feature.mail.DEVELOPMENT_REMOTE_FOLDER_CREATORS
import net.thunderbird.app.common.feature.mail.DEVELOPMENT_REMOTE_FOLDER_DELETERS
import net.thunderbird.app.common.feature.mail.DEVELOPMENT_REMOTE_FOLDER_RENAMERS
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import org.koin.core.module.Module
import org.koin.core.qualifier.named

fun Module.developmentModuleAdditions() {
    single<Map<String, BackendFactory>>(named("developmentBackends")) {
        emptyMap()
    }
    single<Map<String, RemoteFolderCreator.Factory>>(named(DEVELOPMENT_REMOTE_FOLDER_CREATORS)) {
        emptyMap()
    }
    single<Map<String, RemoteFolderRenamer.Factory>>(named(DEVELOPMENT_REMOTE_FOLDER_RENAMERS)) {
        emptyMap()
    }
    single<Map<String, RemoteFolderDeleter.Factory>>(named(DEVELOPMENT_REMOTE_FOLDER_DELETERS)) {
        emptyMap()
    }
    single<List<AutoDiscovery>>(named("extraAutoDiscoveries")) {
        emptyList()
    }
}
