package me.nillerusr;

import android.content.Context;
import android.content.SharedPreferences;

import com.valvesoftware.portal2.R;

public class SteamSessionManager {
    private static final String PREF_NAME = "steam_session";
    private static final String KEY_STEAM_ID = "steam_id64";
    private static final String KEY_PERSONA_NAME = "persona_name";
    private static final String KEY_AVATAR_URL = "avatar_url";
    private static final String KEY_PROFILE_URL = "profile_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_REQUEST_TOKEN = "request_token";

    private final SharedPreferences prefs;

    public SteamSessionManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveSession(SteamProfile profile) {
        prefs.edit()
                .putString(KEY_STEAM_ID, profile.steamId64)
                .putString(KEY_PERSONA_NAME, profile.personaName)
                .putString(KEY_AVATAR_URL, profile.avatarUrl)
                .putString(KEY_PROFILE_URL, profile.profileUrl)
                .apply();
    }

    public void saveAuthData(String username, String requestToken) {
        prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_REQUEST_TOKEN, requestToken)
                .apply();
    }

    public void savePassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password).apply();
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, null);
    }

    public SteamProfile loadSession() {
        String steamId64 = prefs.getString(KEY_STEAM_ID, null);
        if (steamId64 == null) return null;

        SteamProfile profile = new SteamProfile();
        profile.steamId64 = steamId64;
        profile.personaName = prefs.getString(KEY_PERSONA_NAME, null);
        profile.avatarUrl = prefs.getString(KEY_AVATAR_URL, null);
        profile.profileUrl = prefs.getString(KEY_PROFILE_URL, null);
        return profile;
    }

    public boolean isLoggedIn() {
        return prefs.contains(KEY_STEAM_ID);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public String getRequestToken() {
        return prefs.getString(KEY_REQUEST_TOKEN, null);
    }

    public String getSteamId() {
        return prefs.getString(KEY_STEAM_ID, null);
    }

    public void clearSession() {
        prefs.edit().clear().apply();
    }
}
