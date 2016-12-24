package com.fsck.k9.notification;


import com.fsck.k9.Account;


class NotificationIds {
    static final int OFFSET_SEND_FAILED_NOTIFICATION = 0;
    static final int OFFSET_CERTIFICATE_ERROR_INCOMING = 1;
    static final int OFFSET_CERTIFICATE_ERROR_OUTGOING = 2;
    static final int OFFSET_AUTHENTICATION_ERROR_INCOMING = 3;
    static final int OFFSET_AUTHENTICATION_ERROR_OUTGOING = 4;
    static final int OFFSET_FETCHING_MAIL = 5;
    static final int OFFSET_NEW_MAIL_SUMMARY = 6;

    static final int OFFSET_NEW_MAIL_STACKED = 7;


    static final int NUMBER_OF_DEVICE_NOTIFICATIONS = 7;
    static final int NUMBER_OF_STACKED_NOTIFICATIONS = NotificationData.MAX_NUMBER_OF_STACKED_NOTIFICATIONS;

    static final int OFFSET_NEW_MAIL_SNOOZED = OFFSET_NEW_MAIL_STACKED + NUMBER_OF_STACKED_NOTIFICATIONS;
    static final int NUMBER_OF_SNOOZED_NOTIFICATIONS = 10;

    static final int NUMBER_OF_NOTIFICATIONS_PER_ACCOUNT = NUMBER_OF_DEVICE_NOTIFICATIONS +
            NUMBER_OF_STACKED_NOTIFICATIONS + NUMBER_OF_SNOOZED_NOTIFICATIONS;
            

    public static int getNewMailSummaryNotificationId(Account account) {
        return getBaseNotificationId(account) + OFFSET_NEW_MAIL_SUMMARY;
    }

    public static int getNewMailStackedNotificationId(Account account, int index) {
        if (index < 0 || index >= NUMBER_OF_STACKED_NOTIFICATIONS) {
            throw new IndexOutOfBoundsException("Invalid value: " + index);
        }

        return getBaseNotificationId(account) + OFFSET_NEW_MAIL_STACKED + index;
    }

    public static int getFetchingMailNotificationId(Account account) {
        return getBaseNotificationId(account) + OFFSET_FETCHING_MAIL;
    }

    public static int getSendFailedNotificationId(Account account) {
        return getBaseNotificationId(account) + OFFSET_SEND_FAILED_NOTIFICATION;
    }

    public static int getCertificateErrorNotificationId(Account account, boolean incoming) {
        int offset = incoming ? OFFSET_CERTIFICATE_ERROR_INCOMING : OFFSET_CERTIFICATE_ERROR_OUTGOING;
        return getBaseNotificationId(account) + offset;
    }

    public static int getAuthenticationErrorNotificationId(Account account, boolean incoming) {
        int offset = incoming ? OFFSET_AUTHENTICATION_ERROR_INCOMING : OFFSET_AUTHENTICATION_ERROR_OUTGOING;
        return getBaseNotificationId(account) + offset;
    }

    private static int getBaseNotificationId(Account account) {
        return account.getAccountNumber() * NUMBER_OF_NOTIFICATIONS_PER_ACCOUNT;
    }

    public static int getNewSnoozedMessageId(Account account, int index) {
        // just recycle/overwrite if they have too many snoozes
        index = index % NUMBER_OF_SNOOZED_NOTIFICATIONS;

        return getBaseNotificationId(account) + OFFSET_NEW_MAIL_SNOOZED + index;
    }
}
