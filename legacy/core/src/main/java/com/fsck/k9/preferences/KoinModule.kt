package com.fsck.k9.preferences

import com.fsck.k9.Preferences
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val preferencesModule = module {
    factory {
        SettingsExporter(
            contentResolver = get(),
            preferences = get(),
            folderSettingsProvider = get(),
            folderRepository = get(),
            notificationSettingsUpdater = get(),
        )
    }
    factory { FolderSettingsProvider(folderRepository = get()) }
    factory<AccountManager> { get<Preferences>() }
    single {
        RealGeneralSettingsManager(
            preferences = get(),
            coroutineScope = get(named("AppCoroutineScope")),
        )
    } bind GeneralSettingsManager::class

    factory { SettingsFileParser() }

    factory { GeneralSettingsValidator() }
    factory { GeneralSettingsUpgrader() }
    factory { GeneralSettingsWriter(preferences = get(), generalSettingsManager = get()) }

    factory { AccountSettingsValidator() }
    factory { AccountSettingsUpgrader() }
    factory {
        AccountSettingsWriter(
            preferences = get(),
            localFoldersCreator = get(),
            clock = get(),
            serverSettingsSerializer = get(),
            context = get(),
        )
    }

    factory {
        SettingsImporter(
            settingsFileParser = get(),
            generalSettingsValidator = get(),
            accountSettingsValidator = get(),
            generalSettingsUpgrader = get(),
            accountSettingsUpgrader = get(),
            generalSettingsWriter = get(),
            accountSettingsWriter = get(),
        )
    }
}
