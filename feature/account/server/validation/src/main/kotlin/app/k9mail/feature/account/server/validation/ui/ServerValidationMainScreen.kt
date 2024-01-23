package app.k9mail.feature.account.server.validation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.k9mail.core.ui.compose.common.annotation.PreviewDevices
import app.k9mail.core.ui.compose.common.mvi.observeWithoutEffect
import app.k9mail.core.ui.compose.designsystem.template.Scaffold
import app.k9mail.core.ui.compose.theme.K9Theme
import app.k9mail.core.ui.compose.theme.ThunderbirdTheme
import app.k9mail.feature.account.common.ui.AppTitleTopHeader
import app.k9mail.feature.account.common.ui.WizardNavigationBar
import app.k9mail.feature.account.common.ui.WizardNavigationBarState
import app.k9mail.feature.account.oauth.ui.preview.PreviewAccountOAuthViewModel
import app.k9mail.feature.account.server.validation.ui.ServerValidationContract.Event
import app.k9mail.feature.account.server.validation.ui.ServerValidationContract.ViewModel
import app.k9mail.feature.account.server.validation.ui.fake.FakeIncomingServerValidationViewModel
import app.k9mail.feature.account.server.validation.ui.fake.FakeOutgoingServerValidationViewModel

@Composable
internal fun ServerValidationMainScreen(
    viewModel: ViewModel,
    modifier: Modifier = Modifier,
) {
    val (state, dispatch) = viewModel.observeWithoutEffect()

    Scaffold(
        topBar = {
            AppTitleTopHeader()
        },
        bottomBar = {
            WizardNavigationBar(
                onNextClick = { dispatch(Event.OnNextClicked) },
                onBackClick = { dispatch(Event.OnBackClicked) },
                state = WizardNavigationBarState(
                    showNext = state.value.isSuccess,
                ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        ServerValidationContent(
            onEvent = { dispatch(it) },
            state = state.value,
            isIncomingValidation = viewModel.isIncomingValidation,
            oAuthViewModel = viewModel.oAuthViewModel,
            contentPadding = innerPadding,
        )
    }
}

@Composable
@PreviewDevices
internal fun IncomingServerValidationScreenK9Preview() {
    K9Theme {
        ServerValidationMainScreen(
            viewModel = FakeIncomingServerValidationViewModel(
                oAuthViewModel = PreviewAccountOAuthViewModel(),
            ),
        )
    }
}

@Composable
@PreviewDevices
internal fun IncomingServerValidationScreenThunderbirdPreview() {
    ThunderbirdTheme {
        ServerValidationMainScreen(
            viewModel = FakeIncomingServerValidationViewModel(
                oAuthViewModel = PreviewAccountOAuthViewModel(),
            ),
        )
    }
}

@Composable
@PreviewDevices
internal fun AccountOutgoingValidationScreenK9Preview() {
    K9Theme {
        ServerValidationMainScreen(
            viewModel = FakeOutgoingServerValidationViewModel(
                oAuthViewModel = PreviewAccountOAuthViewModel(),
            ),
        )
    }
}

@Composable
@PreviewDevices
internal fun AccountOutgoingValidationScreenThunderbirdPreview() {
    ThunderbirdTheme {
        ServerValidationMainScreen(
            viewModel = FakeOutgoingServerValidationViewModel(
                oAuthViewModel = PreviewAccountOAuthViewModel(),
            ),
        )
    }
}
