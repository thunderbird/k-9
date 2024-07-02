package com.fsck.k9.mailstore;


import com.fsck.k9.Account;


/**
 * Helper to allow accessing classes and methods that aren't visible or accessible to the 'migrations' package
 */
public interface MigrationsHelper {
    Account getAccount();
    void saveAccount();
}
