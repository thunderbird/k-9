package com.fsck.k9.preferences.upgrader

import com.fsck.k9.preferences.Settings.SettingsUpgrader

/**
 * Rewrite `sendClientId` to `sendClientInfo`
 */
class AccountSettingsUpgraderTo91 : SettingsUpgrader {
    override fun upgrade(settings: MutableMap<String, Any?>): Set<String> {
        settings["sendClientInfo"] = settings["sendClientId"]

        return setOf("sendClientId")
    }
}
