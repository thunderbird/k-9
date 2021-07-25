package com.fsck.k9.preferences

/**
 * Stores a snapshot of the app's general settings.
 *
 * When adding a setting here, make sure to also add it in these places:
 * - [GeneralSettingsManager] (write function)
 * - [RealGeneralSettingsManager.loadGeneralSettings]
 * - [RealGeneralSettingsManager.writeSettings]
 * - [GeneralSettingsDescriptions]
 */
// TODO: Move over settings from K9
data class GeneralSettings(
    val backgroundSync: BackgroundSync,
    val showAccountListOnStartup: Boolean,
    val showRecentChanges: Boolean
)

enum class BackgroundSync {
    ALWAYS,
    NEVER,
    FOLLOW_SYSTEM_AUTO_SYNC
}
