package app.k9mail.feature.account.setup.ui.outgoing

import androidx.lifecycle.viewModelScope
import app.k9mail.core.common.domain.usecase.validation.ValidationResult
import app.k9mail.core.ui.compose.common.mvi.BaseViewModel
import app.k9mail.feature.account.setup.domain.entity.ConnectionSecurity
import app.k9mail.feature.account.setup.domain.entity.toSmtpDefaultPort
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Effect
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.ClientCertificateChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.ImapAutoDetectNamespaceChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.OnBackClicked
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.OnNextClicked
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.PasswordChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.PortChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.SecurityChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.ServerChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.UseCompressionChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Event.UsernameChanged
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.State
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.Validator
import app.k9mail.feature.account.setup.ui.outgoing.AccountOutgoingConfigContract.ViewModel
import kotlinx.coroutines.launch

class AccountOutgoingConfigViewModel(
    initialState: State = State(),
    private val validator: Validator,
) : BaseViewModel<State, Event, Effect>(initialState), ViewModel {

    override fun initState(state: State) {
        updateState {
            state.copy()
        }
    }

    override fun event(event: Event) {
        when (event) {
            is ServerChanged -> updateState { it.copy(server = it.server.updateValue(event.server)) }
            is SecurityChanged -> updateSecurity(event.security)
            is PortChanged -> updateState { it.copy(port = it.port.updateValue(event.port)) }
            is UsernameChanged -> updateState { it.copy(username = it.username.updateValue(event.username)) }
            is PasswordChanged -> updateState { it.copy(password = it.password.updateValue(event.password)) }
            is ClientCertificateChanged -> updateState { it.copy(clientCertificate = event.clientCertificate) }
            is ImapAutoDetectNamespaceChanged -> updateState { it.copy(imapAutodetectNamespaceEnabled = event.enabled) }
            is UseCompressionChanged -> updateState { it.copy(useCompression = event.useCompression) }

            OnNextClicked -> submit()
            OnBackClicked -> navigateBack()
        }
    }

    private fun updateSecurity(security: ConnectionSecurity) {
        updateState {
            it.copy(
                security = security,
                port = it.port.updateValue(security.toSmtpDefaultPort()),
            )
        }
    }

    private fun submit() {
        viewModelScope.launch {
            val serverResult = validator.validateServer(state.value.server.value)
            val portResult = validator.validatePort(state.value.port.value)
            val usernameResult = validator.validateUsername(state.value.username.value)
            val passwordResult = validator.validatePassword(state.value.password.value)

            val hasError = listOf(serverResult, portResult, usernameResult, passwordResult)
                .any { it is ValidationResult.Failure }

            updateState {
                it.copy(
                    server = it.server.updateFromValidationResult(serverResult),
                    port = it.port.updateFromValidationResult(portResult),
                    username = it.username.updateFromValidationResult(usernameResult),
                    password = it.password.updateFromValidationResult(passwordResult),
                )
            }

            if (!hasError) {
                navigateNext()
            }
        }
    }

    private fun navigateBack() = emitEffect(Effect.NavigateBack)

    private fun navigateNext() = emitEffect(Effect.NavigateNext)
}
