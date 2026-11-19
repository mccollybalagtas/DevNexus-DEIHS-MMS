package com.deihs.mms.model;

/**
 * Internal credential bundle. Only the authentication layer should ever
 * see the hash.
 */
public record UserCredentials(User user, String passwordHash) {
}
