package app.k9mail.feature.account.setup.ui.options

import app.k9mail.core.common.domain.usecase.validation.ValidationResult
import app.k9mail.core.ui.compose.common.mvi.UnidirectionalViewModel
import app.k9mail.feature.account.setup.domain.entity.EmailCheckFrequency
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import app.k9mail.feature.account.setup.domain.input.StringInputField

interface AccountOptionsContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect> {
        fun initState(state: State)
    }

    data class State(
        val accountName: StringInputField = StringInputField(),
        val displayName: StringInputField = StringInputField(),
        val emailSignature: StringInputField = StringInputField(),
        val checkFrequency: EmailCheckFrequency = EmailCheckFrequency.DEFAULT,
        val messageDisplayCount: EmailDisplayCount = EmailDisplayCount.DEFAULT,
        val showNotification: Boolean = false,
    )

    sealed class Event {
        data class OnAccountNameChanged(val accountName: String) : Event()
        data class OnDisplayNameChanged(val displayName: String) : Event()
        data class OnEmailSignatureChanged(val emailSignature: String) : Event()
        data class OnCheckFrequencyChanged(val checkFrequency: EmailCheckFrequency) : Event()
        data class OnMessageDisplayCountChanged(val messageDisplayCount: EmailDisplayCount) : Event()
        data class OnShowNotificationChanged(val showNotification: Boolean) : Event()

        object OnNextClicked : Event()
        object OnBackClicked : Event()
    }

    sealed class Effect {
        object NavigateNext : Effect()
        object NavigateBack : Effect()
    }

    interface Validator {
        suspend fun validateAccountName(accountName: String): ValidationResult
        suspend fun validateDisplayName(displayName: String): ValidationResult
        suspend fun validateEmailSignature(emailSignature: String): ValidationResult
    }
}
