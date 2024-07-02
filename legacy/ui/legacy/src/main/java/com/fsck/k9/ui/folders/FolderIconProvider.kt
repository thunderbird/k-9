package com.fsck.k9.ui.folders

import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import com.fsck.k9.mailstore.FolderType

class FolderIconProvider {
    fun getFolderIcon(type: FolderType): Int = when (type) {
        FolderType.INBOX -> Icons.Outlined.Inbox
        FolderType.OUTBOX -> Icons.Outlined.Outbox
        FolderType.SENT -> Icons.Outlined.Send
        FolderType.TRASH -> Icons.Outlined.Delete
        FolderType.DRAFTS -> Icons.Outlined.Draft
        FolderType.ARCHIVE -> Icons.Outlined.Archive
        FolderType.SPAM -> Icons.Outlined.Report
        FolderType.REGULAR -> Icons.Outlined.Folder
    }
}
