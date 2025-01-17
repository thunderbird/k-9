package app.k9mail.core.ui.theme.api

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

const val SELECTED_COLOR_ALPHA = 0.1f
const val ACTIVE_COLOR_ALPHA = 0.15f

const val MAX_ALPHA = 255

object ThemeColorHelper {

    fun getSelectedColor(@ColorInt color: Int): Int {
        return ColorUtils.setAlphaComponent(color, (SELECTED_COLOR_ALPHA * MAX_ALPHA).toInt())
    }

    fun getActiveColor(@ColorInt color: Int): Int {
        return ColorUtils.setAlphaComponent(color, (ACTIVE_COLOR_ALPHA * MAX_ALPHA).toInt())
    }
}
