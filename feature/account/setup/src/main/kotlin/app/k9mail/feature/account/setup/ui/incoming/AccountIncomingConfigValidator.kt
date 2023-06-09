package app.k9mail.feature.account.setup.ui.incoming

import app.k9mail.core.common.domain.usecase.validation.ValidationResult
import app.k9mail.feature.account.setup.domain.usecase.ValidateImapPrefix
import app.k9mail.feature.account.setup.domain.usecase.ValidatePassword
import app.k9mail.feature.account.setup.domain.usecase.ValidatePort
import app.k9mail.feature.account.setup.domain.usecase.ValidateServer
import app.k9mail.feature.account.setup.domain.usecase.ValidateUsername

class AccountIncomingConfigValidator(
    private val serverValidator: ValidateServer = ValidateServer(),
    private val portValidator: ValidatePort = ValidatePort(),
    private val usernameValidator: ValidateUsername = ValidateUsername(),
    private val passwordValidator: ValidatePassword = ValidatePassword(),
    private val imapPrefixValidator: ValidateImapPrefix = ValidateImapPrefix(),
) : AccountIncomingConfigContract.Validator {
    override suspend fun validateServer(server: String): ValidationResult {
        return serverValidator.execute(server)
    }

    override suspend fun validatePort(port: Long?): ValidationResult {
        return portValidator.execute(port)
    }

    override suspend fun validateUsername(username: String): ValidationResult {
        return usernameValidator.execute(username)
    }

    override suspend fun validatePassword(password: String): ValidationResult {
        return passwordValidator.execute(password)
    }

    override suspend fun validateImapPrefix(imapPrefix: String): ValidationResult {
        return imapPrefixValidator.execute(imapPrefix)
    }
}
