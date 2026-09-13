package com.lvfast.streaming.ops;

/** Raised when an operator attempts to revoke the final ADMIN account. */
public class LastAdminRoleException extends RuntimeException {
    public LastAdminRoleException(String username) {
        super("Refusing to revoke ADMIN from '" + username + "': it is the last administrator");
    }
}
