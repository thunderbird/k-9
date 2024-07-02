package com.fsck.k9.preferences.upgrader;


import java.util.Map;
import java.util.Set;

import com.fsck.k9.preferences.AppTheme;
import com.fsck.k9.preferences.Settings.SettingsUpgrader;
import com.fsck.k9.preferences.SubTheme;


/**
 * Set <em>messageViewTheme</em> to {@link SubTheme#USE_GLOBAL} if <em>messageViewTheme</em> has the same value as
 * <em>theme</em>.
 */
public class GeneralSettingsUpgraderTo24 implements SettingsUpgrader {

    @Override
    public Set<String> upgrade(Map<String, Object> settings) {
        SubTheme messageViewTheme = (SubTheme) settings.get("messageViewTheme");
        AppTheme theme = (AppTheme) settings.get("theme");
        if ((theme == AppTheme.LIGHT && messageViewTheme == SubTheme.LIGHT) ||
            (theme == AppTheme.DARK && messageViewTheme == SubTheme.DARK)) {
            settings.put("messageViewTheme", SubTheme.USE_GLOBAL);
        }

        return null;
    }
}
