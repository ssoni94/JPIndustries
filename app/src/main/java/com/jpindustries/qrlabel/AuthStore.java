package com.jpindustries.qrlabel;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public final class AuthStore {
    private static final String PREFS_NAME = "jp_auth";
    private static final String KEY_ACTIVE_USER = "active_user";
    private static final String USER_PREFIX = "user_";

    private final SharedPreferences preferences;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isLoggedIn() {
        return getActiveUser() != null;
    }

    public String getActiveUser() {
        String username = preferences.getString(KEY_ACTIVE_USER, null);
        return username == null || username.trim().isEmpty() ? null : username;
    }

    public boolean signUp(String username, String password) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername.isEmpty() || password == null || password.length() < 4 || userExists(normalizedUsername)) {
            return false;
        }

        String salt = createSalt();
        String hash = hashPassword(password, salt);
        preferences.edit()
                .putString(userKey(normalizedUsername), salt + ":" + hash)
                .putString(KEY_ACTIVE_USER, normalizedUsername)
                .apply();
        return true;
    }

    public boolean login(String username, String password) {
        String normalizedUsername = normalize(username);
        String storedValue = preferences.getString(userKey(normalizedUsername), null);
        if (storedValue == null || password == null) {
            return false;
        }

        String[] parts = storedValue.split(":", 2);
        if (parts.length != 2) {
            return false;
        }

        boolean valid = hashPassword(password, parts[0]).equals(parts[1]);
        if (valid) {
            preferences.edit().putString(KEY_ACTIVE_USER, normalizedUsername).apply();
        }
        return valid;
    }

    public void logout() {
        preferences.edit().remove(KEY_ACTIVE_USER).apply();
    }

    public boolean userExists(String username) {
        return preferences.contains(userKey(normalize(username)));
    }

    private String createSalt() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return toHex(bytes);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private String userKey(String username) {
        return USER_PREFIX + username;
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
