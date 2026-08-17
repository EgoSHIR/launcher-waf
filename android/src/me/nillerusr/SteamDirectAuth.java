package me.nillerusr;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;

import org.json.JSONArray;
import org.json.JSONObject;



public class SteamDirectAuth {
    private static final int TIMEOUT_MS = 20000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String DOMAIN = "https://steamcommunity.com";

    private static List<String> sessionCookies = new ArrayList<String>();

    public static List<String> getSessionCookies() {
        return new ArrayList<String>(sessionCookies);
    }

    private static void setInitialCookies() {
        sessionCookies.clear();
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(32);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 32; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        sessionCookies.add("Steam_Language=english");
        sessionCookies.add("timezoneOffset=0,0");
        sessionCookies.add("sessionid=" + sb.toString());
    }

    private static void addCookies(HttpURLConnection conn) {
        if (sessionCookies.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sessionCookies.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(sessionCookies.get(i));
        }
        conn.setRequestProperty("Cookie", sb.toString());
    }

    private static void extractCookies(HttpURLConnection conn) {
        int idx = 0;
        while (true) {
            String key = conn.getHeaderFieldKey(idx);
            String value = conn.getHeaderField(idx);
            if (key == null && value == null) break;
            if ("Set-Cookie".equalsIgnoreCase(key) && value != null) {
                String cookieVal = value.split(";")[0];
                int eqIdx = cookieVal.indexOf("=");
                if (eqIdx > 0) {
                    String name = cookieVal.substring(0, eqIdx);
                    for (int i = 0; i < sessionCookies.size(); i++) {
                        if (sessionCookies.get(i).startsWith(name + "=")) {
                            sessionCookies.set(i, cookieVal);
                            cookieVal = null;
                            break;
                        }
                    }
                    if (cookieVal != null) sessionCookies.add(cookieVal);
                }
            }
            idx++;
        }
    }

    public static class RSAParams {
        public boolean success;
        public String modulus;
        public String exponent;
        public String timestamp;
    }

    public static class LoginResult {
        public boolean success;
        public boolean requiresTwoFactor;
        public boolean requiresCaptcha;
        public boolean requiresEmailAuth;
        public String message;
        public String steamId;
        public String requestToken;
        public String emailSteamId;
        public Map<String, String> transferParams;
        public List<String> transferUrls;
        public String captchaGid;
        public String twoFactorCode;
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static void getRSAParams(final String username, final Callback<RSAParams> callback) {
        setInitialCookies();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(DOMAIN + "/login/getrsakey/");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Referer", DOMAIN + "/login/home/?goto=");
                    addCookies(conn);

                    long now = System.currentTimeMillis();
                    String postData = "donotcache=" + now + "&username=" + URLEncoder.encode(username, "UTF-8");
                    OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                    writer.write(postData);
                    writer.flush();
                    writer.close();

                    int code = conn.getResponseCode();
                    extractCookies(conn);
                    android.util.Log.i("SteamAuth", "getrsakey response code: " + code);

                    BufferedReader reader;
                    if (code >= 400) {
                        java.io.InputStream err = conn.getErrorStream();
                        if (err != null) {
                            reader = new BufferedReader(new InputStreamReader(err));
                        } else {
                            conn.disconnect();
                            callback.onError("HTTP " + code + " (no body)");
                            return;
                        }
                    } else {
                        reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    }
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();

                    String body = sb.toString();
                    android.util.Log.i("SteamAuth", "getrsakey body: " + body);

                    if (code != 200) {
                        callback.onError("HTTP " + code + ": " + body);
                        return;
                    }

                    JSONObject json = new JSONObject(body);
                    RSAParams params = new RSAParams();
                    params.success = json.optBoolean("success");
                    params.modulus = json.optString("publickey_mod");
                    params.exponent = json.optString("publickey_exp");
                    params.timestamp = json.optString("timestamp");

                    if (params.success && params.modulus != null && params.exponent != null
                            && !params.modulus.isEmpty() && !params.exponent.isEmpty()) {
                        callback.onSuccess(params);
                    } else {
                        callback.onError("RSA key fetch failed");
                    }
                } catch (final Exception e) {
                    android.util.Log.e("SteamAuth", "getRSAParams error", e);
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public static String encryptPassword(String password, String modHex, String expHex) throws Exception {
        BigInteger modulus = new BigInteger(modHex, 16);
        BigInteger exponent = new BigInteger(expHex, 16);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PublicKey key = factory.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(password.getBytes("UTF-8"));

        String b64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
        return URLEncoder.encode(b64, "UTF-8");
    }

    public static byte[] encryptPasswordRaw(String password, String modHex, String expHex) throws Exception {
        BigInteger modulus = new BigInteger(modHex, 16);
        BigInteger exponent = new BigInteger(expHex, 16);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PublicKey key = factory.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(password.getBytes("UTF-8"));
    }

    public static void doLogin(final String username, final String encryptedPass,
                                final String timestamp, final String twoFactorCode,
                                final String emailAuthCode, final String captchaGid,
                                final String captchaText, final String emailSteamId,
                                final Callback<LoginResult> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    StringBuilder postData = new StringBuilder();
                    postData.append("donotcache=").append(now);
                    postData.append("&username=").append(URLEncoder.encode(username, "UTF-8"));
                    postData.append("&password=").append(encryptedPass);
                    postData.append("&rsatimestamp=").append(URLEncoder.encode(timestamp, "UTF-8"));
                    postData.append("&remember_login=true");
                    postData.append("&loginfriendlyname=");
                    postData.append("&emailsteamid=").append(emailSteamId != null ? URLEncoder.encode(emailSteamId, "UTF-8") : "");

                    if (twoFactorCode != null && !twoFactorCode.isEmpty()) {
                        postData.append("&twofactorcode=").append(URLEncoder.encode(twoFactorCode, "UTF-8"));
                    }
                    if (emailAuthCode != null && !emailAuthCode.isEmpty()) {
                        postData.append("&emailauth=").append(URLEncoder.encode(emailAuthCode, "UTF-8"));
                    }
                    if (captchaGid != null && !captchaGid.isEmpty() && !"-1".equals(captchaGid)) {
                        postData.append("&captchagid=").append(URLEncoder.encode(captchaGid, "UTF-8"));
                        postData.append("&captcha_text=").append(captchaText != null ? URLEncoder.encode(captchaText, "UTF-8") : "");
                    } else {
                        postData.append("&captchagid=-1&captcha_text=");
                    }

                    URL url = new URL(DOMAIN + "/login/dologin/");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setDoInput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Referer", DOMAIN + "/login/home/?goto=");
                    addCookies(conn);

                    OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                    writer.write(postData.toString());
                    writer.flush();
                    writer.close();

                    int code = conn.getResponseCode();
                    extractCookies(conn);
                    android.util.Log.i("SteamAuth", "dologin response code: " + code);

                    BufferedReader reader;
                    if (code >= 400) {
                        java.io.InputStream err = conn.getErrorStream();
                        if (err != null) {
                            reader = new BufferedReader(new InputStreamReader(err));
                        } else {
                            conn.disconnect();
                            callback.onError("HTTP " + code + " (no body)");
                            return;
                        }
                    } else {
                        reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    }
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();

                    String body = sb.toString();
                    android.util.Log.i("SteamAuth", "dologin body: " + body);

                    if (code >= 400) {
                        callback.onError("HTTP " + code + ": " + body);
                        return;
                    }

                    JSONObject json = new JSONObject(body);
                    final LoginResult result = new LoginResult();
                    result.success = json.optBoolean("success");
                    result.message = json.optString("message");
                    result.twoFactorCode = twoFactorCode;

                    boolean loginComplete = json.optBoolean("login_complete", false);

                    if (result.success && loginComplete) {
                        JSONObject transfer = json.optJSONObject("transfer_parameters");
                        if (transfer != null) {
                            result.steamId = transfer.optString("steamid");
                            if (result.steamId == null || result.steamId.isEmpty()) {
                                result.steamId = transfer.optString("steamID");
                            }
                            result.transferParams = new LinkedHashMap<String, String>();
                            Iterator<String> keys = transfer.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                result.transferParams.put(key, transfer.optString(key));
                            }
                        }

                        if (result.steamId == null || result.steamId.isEmpty()) {
                            String oauthStr = json.optString("oauth");
                            if (oauthStr != null && !oauthStr.isEmpty()) {
                                try {
                                    JSONObject oauth = new JSONObject(oauthStr);
                                    result.steamId = oauth.optString("steamid");
                                    if (result.steamId == null || result.steamId.isEmpty()) {
                                        result.steamId = oauth.optString("steamID");
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w("SteamAuth", "Failed to parse oauth: " + oauthStr);
                                }
                            }
                        }

                        if (result.steamId == null || result.steamId.isEmpty()) {
                            for (String cookie : sessionCookies) {
                                if (cookie.startsWith("steamLogin=") || cookie.startsWith("steamLoginSecure=")) {
                                    String val = cookie.substring(cookie.indexOf('=') + 1);
                                    try {
                                        val = java.net.URLDecoder.decode(val, "UTF-8");
                                    } catch (Exception ignored) {}
                                    int sep = val.indexOf("||");
                                    if (sep > 0) {
                                        result.steamId = val.substring(0, sep);
                                        result.requestToken = val.substring(sep + 2);
                                        android.util.Log.i("SteamAuth", "Extracted steamId from cookie: " + result.steamId);
                                        break;
                                    }
                                }
                            }
                        }

                        result.transferUrls = new ArrayList<String>();
                        JSONArray urls = json.optJSONArray("transfer_urls");
                        if (urls != null) {
                            for (int i = 0; i < urls.length(); i++) {
                                result.transferUrls.add(urls.optString(i));
                            }
                        }

                    } else {
                        result.requiresTwoFactor = json.optBoolean("requires_twofactor");
                        result.requiresCaptcha = json.optBoolean("captcha_needed");
                        result.requiresEmailAuth = json.optBoolean("emailauth_needed");
                        result.captchaGid = json.optString("captcha_gid");
                        result.emailSteamId = json.optString("emailsteamid");
                        if (!loginComplete && result.success) {
                            result.success = false;
                        }
                    }

                    callback.onSuccess(result);
                } catch (final Exception e) {
                    android.util.Log.e("SteamAuth", "doLogin error", e);
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public static class JwtResult {
        public String accessToken;
        public String refreshToken;
        public String requestToken; // token for native game (JWT with "client" aud, or fallback)
    }

    public static String jwtSub(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE);
            String json = new String(decoded, "UTF-8");
            return new JSONObject(json).optString("sub");
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isLikelyJwt(String token) {
        return token != null && token.startsWith("ey");
    }

    private static String getSessionId() {
        for (String c : sessionCookies) {
            if (c.startsWith("sessionid=")) {
                String val = c.substring(c.indexOf('=') + 1);
                try { val = java.net.URLDecoder.decode(val, "UTF-8"); } catch (Exception ignored) {}
                return val;
            }
        }
        return null;
    }

    // protobuf wire format helpers
    private static final int WIRETYPE_VARINT = 0;
    private static final int WIRETYPE_FIXED64 = 1;
    private static final int WIRETYPE_LENGTH_DELIMITED = 2;

    private static int varintLen(long v) {
        int n = 1;
        while ((v & ~0x7FL) != 0) { n++; v >>>= 7; }
        return n;
    }

    private static void writeVarint(byte[] buf, int[] off, long v) {
        while ((v & ~0x7FL) != 0) {
            buf[off[0]++] = (byte)((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[off[0]++] = (byte)(v & 0x7F);
    }

    private static byte[] protoField(int fieldNum, int wireType, long value) {
        long tag = (fieldNum << 3) | wireType;
        int tagLen = varintLen(tag);
        int valLen;
        if (wireType == WIRETYPE_VARINT) {
            valLen = varintLen(value);
        } else if (wireType == WIRETYPE_FIXED64) {
            valLen = 8;
        } else {
            valLen = 0;
        }
        byte[] buf = new byte[tagLen + valLen];
        int[] off = new int[]{0};
        writeVarint(buf, off, tag);
        if (wireType == WIRETYPE_VARINT) {
            writeVarint(buf, off, value);
        } else if (wireType == WIRETYPE_FIXED64) {
            for (int i = 0; i < 8; i++) {
                buf[off[0]++] = (byte)(value & 0xFF);
                value >>= 8;
            }
        }
        return buf;
    }

    private static byte[] protoField(int fieldNum, int wireType, boolean value) {
        return protoField(fieldNum, wireType, value ? 1L : 0L);
    }

    private static byte[] protoField(int fieldNum, int wireType, byte[] value) {
        long tag = (fieldNum << 3) | wireType;
        int tagLen = varintLen(tag);
        int lenLen = varintLen(value.length);
        byte[] buf = new byte[tagLen + lenLen + value.length];
        int[] off = new int[]{0};
        writeVarint(buf, off, tag);
        writeVarint(buf, off, value.length);
        System.arraycopy(value, 0, buf, off[0], value.length);
        return buf;
    }

    private static byte[] protoField(int fieldNum, int wireType, String value) {
        try {
            return protoField(fieldNum, wireType, value.getBytes("UTF-8"));
        } catch (Exception e) { return null; }
    }

    private static byte[] protoConcat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) if (p != null) total += p.length;
        byte[] result = new byte[total];
        int off = 0;
        for (byte[] p : parts) if (p != null) { System.arraycopy(p, 0, result, off, p.length); off += p.length; }
        return result;
    }

    private static JSONObject apiPostProto(String endpoint, byte[] protoBytes) {
        return apiPostProto(endpoint, protoBytes, null);
    }

    private static JSONObject apiPostProto(String endpoint, byte[] protoBytes, String accessToken) {
        try {
            String b64 = Base64.encodeToString(protoBytes, Base64.NO_WRAP);
            String postData = "input_protobuf_encoded=" + URLEncoder.encode(b64, "UTF-8");

            android.util.Log.i("SteamAuth", "apiPostProto " + endpoint + " b64=" + b64);
            URL url = new URL("https://api.steampowered.com/" + endpoint + "?format=json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Origin", "https://steamcommunity.com");
            conn.setRequestProperty("Referer", "https://steamcommunity.com/");
            if (accessToken != null && !accessToken.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            addCookies(conn);

            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
            writer.write(postData);
            writer.flush();
            writer.close();

            int code = conn.getResponseCode();
            extractCookies(conn);

            BufferedReader reader;
            if (code >= 400) {
                java.io.InputStream err = conn.getErrorStream();
                if (err != null) {
                    reader = new BufferedReader(new InputStreamReader(err));
                } else {
                    conn.disconnect();
                    return null;
                }
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            String rawResp = sb.toString();
            android.util.Log.i("SteamAuth", "apiPostProto " + endpoint + " resp (" + code + "): " + rawResp);

            if (code >= 400) return null;

            JSONObject json = new JSONObject(rawResp);
            return json.optJSONObject("response");
        } catch (Exception e) {
            android.util.Log.w("SteamAuth", "apiPostProto " + endpoint + " error: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject apiGet(String endpoint) throws Exception {
        String sep = endpoint.contains("?") ? "&" : "?";
        URL url = new URL("https://api.steampowered.com/" + endpoint + sep + "format=json");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        addCookies(conn);

        int code = conn.getResponseCode();
        extractCookies(conn);

        BufferedReader reader;
        if (code >= 400) return null;
        reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        return new JSONObject(sb.toString()).optJSONObject("response");
    }

    // Encode a nested protobuf message (DeviceDetails)
    private static byte[] encodeDeviceDetails() {
        byte[] f1 = protoField(1, WIRETYPE_LENGTH_DELIMITED, "Portal 2 Android");
        byte[] f2 = protoField(2, WIRETYPE_VARINT, 1L);
        return protoConcat(f1, f2);
    }

    public static void exchangeForJwt(final String steamId,
                                       final String password,
                                       final String username,
                                       final String twoFactorCode,
                                       final String guardData,
                                       final Callback<JwtResult> callback) {
        android.util.Log.i("SteamAuth", "exchangeForJwt user=" + username + " guardData=" + (guardData != null ? "yes" : "no"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.util.Log.i("SteamAuth", "JWT: getrsakey");
                    String mod = null, exp = null, ts = null;
                    {
                        long now = System.currentTimeMillis();
                        StringBuilder postData = new StringBuilder();
                        postData.append("donotcache=").append(now);
                        postData.append("&username=").append(URLEncoder.encode(username, "UTF-8"));

                        URL url = new URL(DOMAIN + "/login/getrsakey/");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(TIMEOUT_MS);
                        conn.setReadTimeout(TIMEOUT_MS);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("User-Agent", USER_AGENT);
                        conn.setRequestProperty("Referer", DOMAIN + "/login/home/?goto=");
                        addCookies(conn);

                        OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                        writer.write(postData.toString());
                        writer.flush();
                        writer.close();

                        int code = conn.getResponseCode();
                        extractCookies(conn);

                        BufferedReader reader = code >= 400
                            ? new BufferedReader(new InputStreamReader(conn.getErrorStream()))
                            : new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        conn.disconnect();

                        String body = sb.toString();
                        if (code != 200) { callback.onError("HTTP " + code); return; }
                        JSONObject json = new JSONObject(body);
                        if (!json.optBoolean("success")) { callback.onError("rsa fail"); return; }
                        mod = json.optString("publickey_mod");
                        exp = json.optString("publickey_exp");
                        ts = json.optString("timestamp");
                        if (mod == null || mod.isEmpty()) { callback.onError("no mod"); return; }
                    }

                    String encrypted = encryptPassword(password, mod, exp);
                    String encB64 = java.net.URLDecoder.decode(encrypted, "UTF-8");

                    byte[] authProto = protoConcat(
                        protoField(1, WIRETYPE_LENGTH_DELIMITED, "Portal 2 Android"),
                        protoField(2, WIRETYPE_LENGTH_DELIMITED, username),
                        protoField(3, WIRETYPE_LENGTH_DELIMITED, encB64),
                        protoField(4, WIRETYPE_VARINT, Long.parseLong(ts)),
                        protoField(5, WIRETYPE_VARINT, 1L),
                        protoField(6, WIRETYPE_VARINT, 1L),
                        protoField(7, WIRETYPE_VARINT, 1L),
                        protoField(8, WIRETYPE_LENGTH_DELIMITED, "Client"),
                        protoField(9, WIRETYPE_LENGTH_DELIMITED, encodeDeviceDetails()),
                        protoField(11, WIRETYPE_VARINT, 0L));

                    if (guardData != null && !guardData.isEmpty()) {
                        authProto = protoConcat(authProto,
                            protoField(10, WIRETYPE_LENGTH_DELIMITED, guardData));
                    }

                    JSONObject authResp = apiPostProto("IAuthenticationService/BeginAuthSessionViaCredentials/v1", authProto);
                    if (authResp == null) { callback.onError("auth session fail"); return; }

                    android.util.Log.i("SteamAuth", "BeginAuthSession resp: " + authResp.toString());

                    String clientId = authResp.optString("client_id");
                    String requestIdHex = authResp.optString("request_id");

                    if (clientId == null || clientId.isEmpty()
                            || requestIdHex == null || requestIdHex.isEmpty()) {
                        callback.onError("no client_id/request_id");
                        return;
                    }

                    byte[] requestIdBytes = android.util.Base64.decode(requestIdHex, android.util.Base64.DEFAULT);
                    long clientIdLong = new java.math.BigInteger(clientId).longValue();

                    if (twoFactorCode != null && !twoFactorCode.isEmpty()) {
                        byte[] guardProto = protoConcat(
                            protoField(1, WIRETYPE_VARINT, clientIdLong),
                            protoField(2, WIRETYPE_LENGTH_DELIMITED, requestIdBytes),
                            protoField(3, WIRETYPE_LENGTH_DELIMITED, twoFactorCode),
                            protoField(4, WIRETYPE_VARINT, 1));
                        JSONObject guardResp = apiPostProto("IAuthenticationService/UpdateAuthSessionWithSteamGuardCode/v1", guardProto);
                        android.util.Log.i("SteamAuth", "guard resp: " + (guardResp != null ? guardResp.toString() : "null"));
                    }

                    int maxPoll = 60;
                    int delay = 1000;
                    for (int i = 0; i < maxPoll; i++) {
                        try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                        byte[] pollBytes = protoConcat(
                            protoField(1, WIRETYPE_VARINT, clientIdLong),
                            protoField(2, WIRETYPE_LENGTH_DELIMITED, requestIdBytes));
                        JSONObject pollResp = apiPostProto("IAuthenticationService/PollAuthSessionStatus/v1", pollBytes);
                        if (pollResp == null) continue;
                        String errorCode = pollResp.optString("error_code");
                        if (errorCode != null && !errorCode.isEmpty()) {
                            if ("2".equals(errorCode)) {
                                callback.onError("needs 2fa");
                            } else {
                                callback.onError("poll error: " + errorCode);
                            }
                            return;
                        }
                        String refreshToken = pollResp.optString("refresh_token");
                        String accessToken = pollResp.optString("access_token");
                        if (refreshToken != null && refreshToken.startsWith("ey")) {
                            android.util.Log.i("SteamAuth", "JWT obtained from poll, access_token=" + (accessToken != null && accessToken.startsWith("ey") ? "yes" : "no"));

                            String newSteamId = pollResp.optString("steamid");
                            if (newSteamId == null || newSteamId.isEmpty()) {
                                String sub = jwtSub(refreshToken);
                                if (sub != null) newSteamId = sub;
                            }

                            // Generate token with "client" audience for native game
                            String requestToken = null;
                            if (refreshToken != null) {
                                byte[] appProto = protoConcat(
                                    protoField(1, WIRETYPE_LENGTH_DELIMITED, refreshToken),
                                    protoField(2, WIRETYPE_VARINT, newSteamId != null ? Long.parseLong(newSteamId) : 0L),
                                    protoField(3, WIRETYPE_VARINT, 620L));
                                JSONObject appResp = apiPostProto("IAuthenticationService/GenerateAccessTokenForApp/v1", appProto);
                                if (appResp != null) {
                                    String token = appResp.optString("access_token");
                                    if (token != null && token.startsWith("ey")) {
                                        requestToken = token;
                                        android.util.Log.i("SteamAuth", "App token (client aud) obtained from GenerateAccessTokenForApp");
                                    } else {
                                        android.util.Log.w("SteamAuth", "No app token in response: " + appResp.toString());
                                    }
                                } else {
                                    android.util.Log.w("SteamAuth", "GenerateAccessTokenForApp returned null");
                                }
                            }
                            if (requestToken == null)
                                requestToken = refreshToken;

                            JwtResult r = new JwtResult();
                            r.refreshToken = refreshToken;
                            r.accessToken = accessToken;
                            r.requestToken = requestToken;
                            callback.onSuccess(r);
                            return;
                        }
                    }
                    callback.onError("poll timeout");
                } catch (final Exception e) {
                    android.util.Log.e("SteamAuth", "jwt error", e);
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public static void completeTransfers(final List<String> transferUrls,
                                           final Map<String, String> transferParams) {
        if (transferUrls == null || transferUrls.isEmpty()) return;
        final List<Thread> threads = new ArrayList<Thread>();
        for (final String urlStr : transferUrls) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        StringBuilder postData = new StringBuilder();
                        for (Map.Entry<String, String> entry : transferParams.entrySet()) {
                            if (postData.length() > 0) postData.append('&');
                            postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                            postData.append('=');
                            postData.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                        }

                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(TIMEOUT_MS);
                        conn.setReadTimeout(TIMEOUT_MS);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("User-Agent", USER_AGENT);
                        addCookies(conn);

                        OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                        writer.write(postData.toString());
                        writer.flush();
                        writer.close();

                        int code = conn.getResponseCode();
                        extractCookies(conn);
                        conn.disconnect();
                    } catch (Exception e) {
                        android.util.Log.w("SteamAuth", "transfer failed " + urlStr, e);
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
    }

    public static String getJwtFromCookies() {
        for (String cookie : sessionCookies) {
            if (cookie.startsWith("steamRefresh_steam=")) {
                String val = cookie.substring(cookie.indexOf('=') + 1);
                try { val = java.net.URLDecoder.decode(val, "UTF-8"); } catch (Exception ignored) {}
                if (val != null && val.startsWith("ey")) return val;
            }
        }
        return null;
    }
}
