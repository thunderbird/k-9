package com.fsck.k9.contacts

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.k9mail.core.android.common.contact.ContactRepository
import app.k9mail.core.common.mail.toEmailAddressOrNull
import timber.log.Timber

internal class ContactPhotoLoader(
    private val contentResolver: ContentResolver,
    private val contactRepository: ContactRepository,
) {
    fun loadContactPhoto(emailAddress: String): Bitmap? {
        val photoUri = getPhotoUri(emailAddress) ?: return null
        return try {
            contentResolver.openInputStream(photoUri).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Timber.e(e, "Couldn't load contact photo: $photoUri")
            null
        }
    }

    private fun getPhotoUri(email: String): Uri? {
        return email.toEmailAddressOrNull()?.let { emailAddress ->
            contactRepository.getContactFor(emailAddress)?.photoUri
        }
    }
}
