package app.k9mail.core.ui.compose.designsystem.template

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.k9mail.core.ui.compose.common.PreviewDevices
import app.k9mail.core.ui.compose.common.window.WindowSizeClass
import app.k9mail.core.ui.compose.common.window.getWindowSizeInfo
import app.k9mail.core.ui.compose.designsystem.atom.Surface
import app.k9mail.core.ui.compose.theme.K9Theme
import app.k9mail.core.ui.compose.theme.MainTheme

/**
 * The [ResponsiveContent] composable automatically adapts its child content to different screen sizes and resolutions,
 * providing a responsive layout for a better user experience.
 *
 * It uses the [WindowSizeClass] (Compact, Medium, or Expanded) to make appropriate layout adjustments.
 *
 * @param modifier The modifier to be applied to the layout.
 * @param content The content to be displayed.
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val windowSizeClass = getWindowSizeInfo()

    when (windowSizeClass.screenWidthSizeClass) {
        WindowSizeClass.Compact -> CompactContent(modifier = modifier, content = content)
        WindowSizeClass.Medium -> MediumContent(modifier = modifier, content = content)
        WindowSizeClass.Expanded -> ExpandedContent(modifier = modifier, content = content)
    }
}

@Composable
private fun CompactContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
    ) {
        content()
    }
}

@Composable
private fun MediumContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier.requiredWidth(WindowSizeClass.COMPACT_MAX_WIDTH.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ExpandedContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (getWindowSizeInfo().screenHeightSizeClass) {
        WindowSizeClass.Compact -> MediumContent(modifier, content)
        WindowSizeClass.Medium -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(modifier),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    modifier = Modifier.requiredWidth(WindowSizeClass.MEDIUM_MAX_WIDTH.dp),
                    elevation = MainTheme.elevations.raised,
                ) {
                    content()
                }
            }
        }
        WindowSizeClass.Expanded -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(modifier),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .requiredWidth(WindowSizeClass.MEDIUM_MAX_WIDTH.dp)
                        .requiredHeight(WindowSizeClass.MEDIUM_MAX_HEIGHT.dp),
                    elevation = MainTheme.elevations.raised,
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
@PreviewDevices
internal fun ResponsiveContentPreview() {
    K9Theme {
        Surface {
            ResponsiveContent {
                Surface(
                    color = MainTheme.colors.info,
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
    }
}
