package com.fsck.k9.ui.compose

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Parcelable
import androidx.core.content.IntentCompat
import com.fsck.k9.activity.MessageCompose
import org.openintents.openpgp.util.OpenPgpApi

@Suppress("NestedBlockDepth", "MaxLineLength")
class IntentDataMapper {

    fun initFromIntent(intent: Intent): IntentData {
        val action: String? = intent.action
        var intentData = IntentData()

        if (
            Intent.ACTION_VIEW == action ||
            Intent.ACTION_SENDTO == action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == action
        ) {
            intent.data?.let { data ->
                intentData = intentData.copy(mailToUri = data)
            }
        }

        if ((
            Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action ||
                Intent.ACTION_SENDTO == action || Intent.ACTION_VIEW == action
            )
        ) {
            intentData = intentData.copy(
                startedByExternalIntent = true,
                extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                intentType = intent.type,
            )

            if ((Intent.ACTION_SEND == action)) {
                val extraStream = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                )
                intentData = intentData.copy(extraStream = extraStream)
            } else {
                val list: List<Parcelable>? = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Parcelable::class.java,
                )
                list?.let {
                    for (parcelable in it) {
                        intentData = intentData.copy(extraStream = parcelable as Uri)
                    }
                }
            }
            intentData = intentData.copy(
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                shouldInitFromSendOrViewIntent = true,
            )
        }

        if ((MessageCompose.ACTION_AUTOCRYPT_PEER == action)) {
            intentData = intentData.copy(
                trustId = intent.getStringExtra(OpenPgpApi.EXTRA_AUTOCRYPT_PEER_ID),
                startedByExternalIntent = true,
            )
        }
        return intentData
    }
}

data class IntentData(
    val startedByExternalIntent: Boolean = false,
    val shouldInitFromSendOrViewIntent: Boolean = false,
    val mailToUri: Uri? = null,
    val extraText: CharSequence? = null,
    val intentType: String? = null,
    val extraStream: Uri? = null,
    val subject: String? = null,
    val trustId: String? = null,
)
