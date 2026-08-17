package me.nillerusr;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

public class SteamApiClient {
    private static final int TIMEOUT_MS = 15000;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static void validateOpenID(final Map<String, String> params, final Callback<Boolean> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder postData = new StringBuilder();
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        if (postData.length() > 0) postData.append('&');
                        postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                        postData.append('=');
                        postData.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                    }
                    postData.append("&openid.mode=check_authentication");

                    URL url = new URL("https://steamcommunity.com/openid/login");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");

                    OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                    writer.write(postData.toString());
                    writer.flush();
                    writer.close();

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        callback.onError("Validation failed: HTTP " + responseCode);
                        return;
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    boolean isValid = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("is_valid:true")) {
                            isValid = true;
                            break;
                        }
                    }
                    reader.close();
                    conn.disconnect();

                    callback.onSuccess(isValid);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public static void fetchProfile(final String steamId64, final Callback<SteamProfile> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long deadline = System.currentTimeMillis() + 15000;
                    SteamProfile profile = null;

                    while (System.currentTimeMillis() < deadline) {
                        profile = tryFetchProfileXml(steamId64);
                        if (profile != null) break;

                        profile = tryFetchProfileHtml(steamId64);
                        if (profile != null) break;

                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining > 0) {
                            long delay = Math.min(remaining, 3000);
                            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                        }
                    }

                    if (profile != null && profile.steamId64 != null) {
                        callback.onSuccess(profile);
                    } else {
                        callback.onError("Failed to load profile");
                    }
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    private static SteamProfile tryFetchProfileXml(String steamId64) {
        try {
            URL url = new URL("https://steamcommunity.com/profiles/" + steamId64 + "/?xml=1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            addCookiesToConn(conn);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                conn.disconnect();
                android.util.Log.w("SteamAuth", "XML profile fetch failed: HTTP " + responseCode);
                return null;
            }

            SteamProfile profile = parseProfileXml(conn.getInputStream());
            conn.disconnect();
            if (profile != null && profile.steamId64 != null) {
                android.util.Log.i("SteamAuth", "Profile fetched from XML");
                return profile;
            }
            return null;
        } catch (Exception e) {
            android.util.Log.w("SteamAuth", "XML profile error: " + e.getMessage());
            return null;
        }
    }

    private static SteamProfile tryFetchProfileHtml(String steamId64) {
        try {
            URL url = new URL("https://steamcommunity.com/profiles/" + steamId64);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            addCookiesToConn(conn);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                conn.disconnect();
                android.util.Log.w("SteamAuth", "HTML profile fetch failed: HTTP " + responseCode);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            String html = sb.toString();
            SteamProfile profile = new SteamProfile();
            profile.steamId64 = steamId64;
            profile.profileUrl = "https://steamcommunity.com/profiles/" + steamId64;

            String name = extractMetaTag(html, "og:title");
            if (name != null && !name.isEmpty()) {
                if (name.startsWith("Steam Community :: ")) {
                    name = name.substring("Steam Community :: ".length());
                }
                profile.personaName = name;
            } else {
                name = extractMetaTag(html, "profile_name");
                if (name != null && !name.isEmpty()) {
                    profile.personaName = name;
                } else {
                    profile.personaName = "Steam User";
                }
            }

            String avatar = extractMetaTag(html, "og:image");
            if (avatar != null && !avatar.isEmpty()) {
                profile.avatarUrl = avatar;
            }

            android.util.Log.i("SteamAuth", "Profile fetched from HTML: " + profile.personaName);
            return profile;
        } catch (Exception e) {
            android.util.Log.w("SteamAuth", "HTML profile error: " + e.getMessage());
            return null;
        }
    }

    private static String extractMetaTag(String html, String property) {
        String[] patterns = {
            "<meta[^>]*property=\"" + property + "\"[^>]*content=\"([^\"]+)\"",
            "<meta[^>]*content=\"([^\"]+)\"[^>]*property=\"" + property + "\"",
            "<meta[^>]*name=\"" + property + "\"[^>]*content=\"([^\"]+)\"",
            "<meta[^>]*content=\"([^\"]+)\"[^>]*name=\"" + property + "\""
        };
        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(html);
            if (m.find()) {
                String val = m.group(1);
                val = val.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
                return val;
            }
        }
        return null;
    }

    private static void addCookiesToConn(HttpURLConnection conn) {
        List<String> cookies = SteamDirectAuth.getSessionCookies();
        if (!cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cookies.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(cookies.get(i));
            }
            conn.setRequestProperty("Cookie", sb.toString());
        }
    }

    public static void fetchAvatar(final String avatarUrl, final Callback<byte[]> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(avatarUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream is = conn.getInputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    is.close();
                    conn.disconnect();

                    callback.onSuccess(baos.toByteArray());
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    private static SteamProfile parseProfileXml(InputStream inputStream) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(inputStream, "UTF-8");

            SteamProfile profile = new SteamProfile();
            String currentTag = null;
            int eventType = parser.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                } else if (eventType == XmlPullParser.TEXT && currentTag != null) {
                    String text = parser.getText().trim();
                    if ("steamID64".equals(currentTag)) {
                        profile.steamId64 = text;
                    } else if ("steamID".equals(currentTag)) {
                        profile.personaName = text;
                    } else if ("avatarFull".equals(currentTag)) {
                        profile.avatarUrl = text;
                    } else if ("customURL".equals(currentTag) && text.length() > 0) {
                        profile.profileUrl = "https://steamcommunity.com/id/" + text;
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("profile".equals(parser.getName())) break;
                    currentTag = null;
                }
                eventType = parser.next();
            }

            if (profile.profileUrl == null && profile.steamId64 != null) {
                profile.profileUrl = "https://steamcommunity.com/profiles/" + profile.steamId64;
            }

            return profile;
        } catch (Exception e) {
            return null;
        }
    }
}
