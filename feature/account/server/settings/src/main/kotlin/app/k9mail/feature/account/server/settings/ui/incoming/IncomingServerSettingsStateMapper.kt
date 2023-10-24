package app.k9mail.feature.account.server.settings.ui.incoming

import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.common.domain.entity.IncomingProtocolType
import app.k9mail.feature.account.common.domain.entity.toAuthType
import app.k9mail.feature.account.common.domain.entity.toAuthenticationType
import app.k9mail.feature.account.common.domain.entity.toConnectionSecurity
import app.k9mail.feature.account.common.domain.entity.toMailConnectionSecurity
import app.k9mail.feature.account.common.domain.input.NumberInputField
import app.k9mail.feature.account.common.domain.input.StringInputField
import app.k9mail.feature.account.server.settings.ui.incoming.IncomingServerSettingsContract.State
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.store.imap.ImapStoreSettings
import com.fsck.k9.mail.store.imap.ImapStoreSettings.autoDetectNamespace
import com.fsck.k9.mail.store.imap.ImapStoreSettings.isSendClientId
import com.fsck.k9.mail.store.imap.ImapStoreSettings.isUseCompression
import com.fsck.k9.mail.store.imap.ImapStoreSettings.pathPrefix

fun AccountState.toIncomingServerSettingsState() = incomingServerSettings?.toIncomingServerSettingsState()
    ?: State(username = StringInputField(value = emailAddress ?: ""))

private fun ServerSettings.toIncomingServerSettingsState(): State {
    return State(
        protocolType = IncomingProtocolType.fromName(type),
        server = StringInputField(value = host ?: ""),
        security = connectionSecurity.toConnectionSecurity(),
        port = NumberInputField(value = port.toLong()),
        authenticationType = authenticationType.toAuthenticationType(),
        username = StringInputField(value = username),
        password = StringInputField(value = password ?: ""),
        clientCertificateAlias = clientCertificateAlias,
        imapAutodetectNamespaceEnabled = autoDetectNamespace,
        imapPrefix = StringInputField(value = pathPrefix ?: ""),
        imapUseCompression = isUseCompression,
        imapSendClientId = isSendClientId,
    )
}

internal fun State.toServerSettings(): ServerSettings {
    return ServerSettings(
        type = protocolType.defaultName,
        host = server.value,
        port = port.value!!.toInt(),
        connectionSecurity = security.toMailConnectionSecurity(),
        authenticationType = authenticationType.toAuthType(),
        username = username.value,
        password = if (authenticationType.isPasswordRequired) password.value else null,
        clientCertificateAlias = clientCertificateAlias,
        extra = createExtras(),
    )
}

private fun State.createExtras(): Map<String, String?> {
    return if (protocolType == IncomingProtocolType.IMAP) {
        ImapStoreSettings.createExtra(
            autoDetectNamespace = imapAutodetectNamespaceEnabled,
            pathPrefix = if (imapAutodetectNamespaceEnabled) null else imapPrefix.value,
            useCompression = imapUseCompression,
            sendClientId = imapSendClientId,
        )
    } else {
        emptyMap()
    }
}
