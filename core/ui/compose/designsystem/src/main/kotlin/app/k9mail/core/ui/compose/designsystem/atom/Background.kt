package app.k9mail.core.ui.compose.designsystem.atom

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.k9mail.core.ui.compose.theme.MainTheme
import app.k9mail.core.ui.compose.theme.PreviewWithThemes
import androidx.compose.material.Surface as MaterialSurface

@Composable
fun Background(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    MaterialSurface(
        modifier = modifier,
        content = content,
        color = MainTheme.colors.background,
    )
}

@Preview(showBackground = true)
@Composable
internal fun BackgroundPreview() {
    PreviewWithThemes {
        Background(
            modifier = Modifier.size(200.dp),
            content = {},
        )
    }
}
