package com.fsck.k9.ui.messageview

import com.fsck.k9.Account
import com.fsck.k9.Identity
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.buildMessage
import com.fsck.k9.ui.helper.AddressFormatter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val IDENTITY_ADDRESS = "me@domain.example"

class DisplayRecipientsExtractorTest {
    private val account = Account("uuid").apply {
        identities += Identity(
            email = IDENTITY_ADDRESS
        )
    }

    private val addressFormatter = object : AddressFormatter {
        override fun getDisplayName(address: Address): CharSequence {
            return when (address.address) {
                IDENTITY_ADDRESS -> "me"
                "user1@domain.example" -> "Contact One"
                "user2@domain.example" -> "Contact Two"
                "user3@domain.example" -> "Contact Three"
                "user4@domain.example" -> "Contact Four"
                else -> address.personal ?: address.address
            }
        }

        override fun getDisplayNameOrNull(address: Address): CharSequence? {
            error("Not implemented")
        }
    }

    private val displayRecipientsExtractor = DisplayRecipientsExtractor(
        addressFormatter,
        maxNumberOfDisplayRecipients = 5
    )

    @Test
    fun `single recipient is identity address`() {
        val message = buildMessage {
            header("To", "Test User <$IDENTITY_ADDRESS>")
        }

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients).isEqualTo(
            DisplayRecipients(recipientNames = listOf("me"), numberOfRecipients = 1)
        )
    }

    @Test
    fun `single recipient is a contact`() {
        val message = buildMessage {
            header("To", "User 1 <user1@domain.example>")
        }

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients).isEqualTo(
            DisplayRecipients(recipientNames = listOf("Contact One"), numberOfRecipients = 1)
        )
    }

    @Test
    fun `single recipient is not a contact`() {
        val message = buildMessage {
            header("To", "Alice <alice@domain.example>")
        }

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients).isEqualTo(
            DisplayRecipients(recipientNames = listOf("Alice"), numberOfRecipients = 1)
        )
    }

    @Test
    fun `single recipient without name and not a contact`() {
        val message = buildMessage {
            header("To", "alice@domain.example")
        }

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients).isEqualTo(
            DisplayRecipients(recipientNames = listOf("alice@domain.example"), numberOfRecipients = 1)
        )
    }

    @Test
    fun `three unknown recipients`() {
        val message = buildMessage {
            header("To", "Unknown 1 <unknown1@domain.example>, Unknown 2 <unknown2@domain.example>")
            header("Cc", "Unknown 3 <unknown3@domain.example>")
        }

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients).isEqualTo(
            DisplayRecipients(
                recipientNames = listOf("Unknown 1", "Unknown 2", "Unknown 3"),
                numberOfRecipients = 3
            )
        )
    }

    @Test
    fun `recipients spread across To and Cc header`() {
        val message = buildMessage {
            header("To", "user1@domain.example, Alice <alice@domain.example>, $IDENTITY_ADDRESS")
            header("Cc", "user2@domain.example, User 4 <user4@domain.example>, someone.else@domain.example")
            header("Bcc", "hidden@domain.example")
        }

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients).isEqualTo(
            DisplayRecipients(
                recipientNames = listOf("me", "Contact One", "Alice", "Contact Two", "Contact Four"),
                numberOfRecipients = 7
            )
        )
    }

    @Test
    fun `100 recipients, AddressFormatter_getDisplayName() should only be called maxNumberOfDisplayRecipients times`() {
        val recipients = (1..100).joinToString(separator = ", ") { "unknown$it@domain.example" }
        val message = buildMessage {
            header("To", recipients)
        }
        var numberOfTimesCalled = 0
        val addressFormatter = object : AddressFormatter {
            override fun getDisplayName(address: Address): CharSequence {
                numberOfTimesCalled++
                return address.address
            }

            override fun getDisplayNameOrNull(address: Address): CharSequence? {
                error("Not implemented")
            }
        }
        val displayRecipientsExtractor = DisplayRecipientsExtractor(
            addressFormatter,
            maxNumberOfDisplayRecipients = 5
        )

        val displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account)

        assertThat(displayRecipients.numberOfRecipients).isEqualTo(100)
        assertThat(numberOfTimesCalled).isEqualTo(5)
    }
}
