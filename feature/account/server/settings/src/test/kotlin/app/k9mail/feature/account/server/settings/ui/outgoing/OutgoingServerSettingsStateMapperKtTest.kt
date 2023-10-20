package app.k9mail.feature.account.server.settings.ui.outgoing

import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.common.domain.entity.AuthenticationType
import app.k9mail.feature.account.common.domain.entity.ConnectionSecurity
import app.k9mail.feature.account.common.domain.entity.MailConnectionSecurity
import app.k9mail.feature.account.common.domain.input.NumberInputField
import app.k9mail.feature.account.common.domain.input.StringInputField
import app.k9mail.feature.account.server.settings.ui.outgoing.OutgoingServerSettingsContract.State
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ServerSettings
import org.junit.Test

class OutgoingServerSettingsStateMapperKtTest {

    @Test
    fun `should map to state with email as username when server settings are null`() {
        val accountState = AccountState(
            emailAddress = "test@example.com",
            outgoingServerSettings = null,
        )

        val result = accountState.toOutgoingServerSettingsState()

        assertThat(result).isEqualTo(State(username = StringInputField(value = "test@example.com")))
    }

    @Test
    fun `should map from SMTP server settings to state`() {
        val accountState = AccountState(
            outgoingServerSettings = SMTP_SERVER_SETTINGS,
        )

        val result = accountState.toOutgoingServerSettingsState()

        assertThat(result).isEqualTo(OUTGOING_STATE)
    }

    @Test
    fun `should map state to server settings`() {
        val outgoingState = OUTGOING_STATE

        val result = outgoingState.toServerSettings()

        assertThat(result).isEqualTo(SMTP_SERVER_SETTINGS)
    }

    private companion object {
        private val OUTGOING_STATE = State(
            server = StringInputField(value = "smtp.example.org"),
            port = NumberInputField(value = 587),
            security = ConnectionSecurity.TLS,
            authenticationType = AuthenticationType.PasswordCleartext,
            username = StringInputField(value = "user"),
            password = StringInputField(value = "password"),
            clientCertificateAlias = null,
        )

        private val SMTP_SERVER_SETTINGS = ServerSettings(
            type = "smtp",
            host = "smtp.example.org",
            port = 587,
            connectionSecurity = MailConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = "user",
            password = "password",
            clientCertificateAlias = null,
        )
    }
}
