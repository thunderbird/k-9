package com.fsck.k9.mail.store.imap

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.MessagingException
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import com.fsck.k9.mail.server.ServerSettingsValidationResult
import com.fsck.k9.mail.server.ServerSettingsValidator
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.io.IOException

class ImapServerSettingsValidator(
    private val trustedSocketFactory: TrustedSocketFactory,
    private val oAuth2TokenProvider: OAuth2TokenProvider?,
    private val clientIdAppName: String,
) : ServerSettingsValidator {

    @Suppress("TooGenericExceptionCaught")
    override fun checkServerSettings(serverSettings: ServerSettings): ServerSettingsValidationResult {
        val config = object : ImapStoreConfig {
            override val logLabel = "check"
            override fun isSubscribedFoldersOnly() = false
            override fun clientIdAppName() = clientIdAppName
        }
        val store = RealImapStore(serverSettings, config, trustedSocketFactory, oAuth2TokenProvider)

        return try {
            store.checkSettings()

            ServerSettingsValidationResult.Success
        } catch (e: AuthenticationFailedException) {
            ServerSettingsValidationResult.AuthenticationError(e.messageFromServer)
        } catch (e: CertificateValidationException) {
            ServerSettingsValidationResult.CertificateError(e.certChain.toList())
        } catch (e: NegativeImapResponseException) {
            ServerSettingsValidationResult.ServerError(e.responseText)
        } catch (e: MessagingException) {
            val cause = e.cause
            if (cause is IOException) {
                ServerSettingsValidationResult.NetworkError(cause)
            } else {
                ServerSettingsValidationResult.UnknownError(e)
            }
        } catch (e: IOException) {
            ServerSettingsValidationResult.NetworkError(e)
        } catch (e: Exception) {
            ServerSettingsValidationResult.UnknownError(e)
        }
    }
}