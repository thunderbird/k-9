package com.fsck.k9.preferences.upgrader;


import java.util.Map;
import java.util.Set;

import com.fsck.k9.preferences.Settings.SettingsUpgrader;


/**
 * Upgrades the settings from version 78 to 79.
 *
 * <p>
 * Change default value of {@code registeredNameColor} to have enough contrast in both the light and dark theme.
 * </p>
 */
public class GeneralSettingsUpgraderTo79 implements SettingsUpgrader {

    @Override
    public Set<String> upgrade(Map<String, Object> settings) {
        final Integer registeredNameColorValue = (Integer) settings.get("registeredNameColor");

        if (registeredNameColorValue != null && registeredNameColorValue == 0xFF00008F) {
            settings.put("registeredNameColor", 0xFF1093F5);
        }

        return null;
    }
}
