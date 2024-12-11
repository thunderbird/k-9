package com.fsck.k9

import app.k9mail.feature.account.oauth.featureAccountOAuthModule
import app.k9mail.feature.launcher.di.featureLauncherModule
import app.k9mail.legacy.ui.folder.uiFolderModule
import com.fsck.k9.account.accountModule
import com.fsck.k9.activity.activityModule
import com.fsck.k9.contacts.contactsModule
import com.fsck.k9.ui.account.accountUiModule
import com.fsck.k9.ui.base.uiBaseModule
import com.fsck.k9.ui.changelog.changelogUiModule
import com.fsck.k9.ui.choosefolder.chooseFolderUiModule
import com.fsck.k9.ui.compose.composeModule
import com.fsck.k9.ui.endtoend.endToEndUiModule
import com.fsck.k9.ui.folders.foldersUiModule
import com.fsck.k9.ui.identity.identityUiModule
import com.fsck.k9.ui.managefolders.manageFoldersUiModule
import com.fsck.k9.ui.messagedetails.messageDetailsUiModule
import com.fsck.k9.ui.messagelist.messageListUiModule
import com.fsck.k9.ui.messagesource.messageSourceModule
import com.fsck.k9.ui.messageview.messageViewUiModule
import com.fsck.k9.ui.settings.settingsUiModule
import com.fsck.k9.ui.uiModule
import com.fsck.k9.view.viewModule

val uiModules = listOf(
    featureAccountOAuthModule,
    uiBaseModule,
    activityModule,
    uiModule,
    uiFolderModule,
    settingsUiModule,
    endToEndUiModule,
    foldersUiModule,
    messageListUiModule,
    manageFoldersUiModule,
    chooseFolderUiModule,
    contactsModule,
    accountModule,
    composeModule,
    viewModule,
    changelogUiModule,
    messageSourceModule,
    accountUiModule,
    messageDetailsUiModule,
    messageViewUiModule,
    identityUiModule,
    featureLauncherModule,
)
