package com.fsck.k9.mailstore

import com.fsck.k9.mail.FolderClass

data class FolderSettings(
    val visibleLimit: Int,
    val displayClass: FolderClass,
    val syncClass: FolderClass,
    val notifyClass: FolderClass,
    val pushClass: FolderClass,
    val inTopGroup: Boolean,
    val integrate: Boolean,
)
