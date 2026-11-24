package app.k9mail.dev

import app.k9mail.autodiscovery.api.AutoDiscovery
import app.k9mail.autodiscovery.demo.DemoAutoDiscovery
import app.k9mail.backend.demo.DemoRemoteFolderCreatorFactory
import app.k9mail.backend.demo.DemoRemoteFolderDeleterFactory
import app.k9mail.backend.demo.DemoRemoteFolderRenamerFactory
import net.thunderbird.app.common.feature.mail.DEVELOPMENT_REMOTE_FOLDER_CREATORS
import net.thunderbird.app.common.feature.mail.DEVELOPMENT_REMOTE_FOLDER_DELETERS
import net.thunderbird.app.common.feature.mail.DEVELOPMENT_REMOTE_FOLDER_RENAMERS
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.folder.RemoteFolderDeleter
import net.thunderbird.backend.api.folder.RemoteFolderRenamer
import net.thunderbird.core.featureflag.inject.qualifier.InjectQualifier
import net.thunderbird.core.featureflag.provider.CatalogFeatureFlagProvider
import net.thunderbird.core.featureflag.provider.RuntimeDebugOverrideFeatureFlagProvider
import org.koin.core.module.Module
import org.koin.core.qualifier.named

fun Module.developmentModuleAdditions() {
    single {
        DemoBackendFactory(
            backendStorageFactory = get(),
        )
    }
    single<Map<String, BackendFactory>>(named("developmentBackends")) {
        mapOf("demo" to get<DemoBackendFactory>())
    }
    single<Map<String, RemoteFolderCreator.Factory>>(named(DEVELOPMENT_REMOTE_FOLDER_CREATORS)) {
        mapOf("demo" to DemoRemoteFolderCreatorFactory(backendStorageFactory = get()))
    }
    single<Map<String, RemoteFolderRenamer.Factory>>(named(DEVELOPMENT_REMOTE_FOLDER_RENAMERS)) {
        mapOf("demo" to DemoRemoteFolderRenamerFactory(backendStorageFactory = get()))
    }
    single<Map<String, RemoteFolderDeleter.Factory>>(named(DEVELOPMENT_REMOTE_FOLDER_DELETERS)) {
        mapOf("demo" to DemoRemoteFolderDeleterFactory(backendStorageFactory = get()))
    }
    single<List<AutoDiscovery>>(named("extraAutoDiscoveries")) {
        listOf(DemoAutoDiscovery())
    }
    single {
        RuntimeDebugOverrideFeatureFlagProvider(
            configStore = get(),
            logger = get(),
        )
    }
    single<CatalogFeatureFlagProvider>(named(InjectQualifier.InMemory)) {
        get<RuntimeDebugOverrideFeatureFlagProvider>()
    }
}
