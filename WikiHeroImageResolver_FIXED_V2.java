package com.iandev.masterkit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;

/**
 * Loads consistent hero icons from MLBB Wiki API.
 * The complete hero list is downloaded only once per app process.
 */
public final class WikiHeroImageResolver {

    private static final String HEROES_URL =
            "https://mlbb-wiki-api.vercel.app/api/heroes";

    private static final HashMap<String, String> ICONS = new HashMap<>();
    private static boolean loaded = false;

    private WikiHeroImageResolver() {
    }

    public static synchronized String getCached(String heroName) {
        String key = normalize(heroName);
        String value = ICONS.get(key);
        return value == null ? "" : value;
    }

    public static String getIconBlocking(String heroName) {
        String key = normalize(heroName);
        if (key.isEmpty()) return "";

        String candidate;

        synchronized (WikiHeroImageResolver.class) {

            if (!loaded) {
                loadAllIcons();
            }

            candidate = ICONS.get(key);
        }

        if (candidate == null || candidate.trim().isEmpty()) {
            return "";
        }

        /*
         * Some Wiki records point to moved/deleted files.
         * Return the URL only when it really responds as an image.
         */
        if (!isWorkingImage(candidate)) {

            synchronized (WikiHeroImageResolver.class) {
                ICONS.remove(key);
            }

            return "";
        }

        return candidate;
    }

    private static void loadAllIcons() {
        HttpURLConnection connection = null;
        InputStream stream = null;
        BufferedReader reader = null;

        try {
            URL url = new URL(HEROES_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MasterKit-Android");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return;

            stream = connection.getInputStream();
            reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));

            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);

            JSONObject root = new JSONObject(body.toString());
            JSONArray data = root.optJSONArray("data");
            if (data == null) return;

            for (int i = 0; i < data.length(); i++) {
                JSONObject hero = data.optJSONObject(i);
                if (hero == null) continue;

                String name = hero.optString("hero_name", "").trim();
                String icon = hero.optString("icon", "").trim();

                if (!name.isEmpty() && isHttp(icon)) {
                    ICONS.put(normalize(name), cleanWikiUrl(icon));
                }
            }

            loaded = !ICONS.isEmpty();

        } catch (Exception ignored) {
            // Existing MasterKit images remain as fallback.
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (stream != null) stream.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private static String cleanWikiUrl(String url) {
        String clean = url == null ? "" : url.trim();
        return isHttp(clean) ? clean : "";
    }

    private static boolean isWorkingImage(String url) {

        HttpURLConnection connection = null;
        InputStream stream = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(true);

            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android; MasterKit)"
            );

            int code = connection.getResponseCode();

            if (code < 200 || code >= 300) {
                return false;
            }

            String contentType = connection.getContentType();

            if (contentType != null
                    && !contentType.toLowerCase(Locale.US)
                    .startsWith("image/")) {
                return false;
            }

            stream = connection.getInputStream();

            byte[] header = new byte[12];
            int read = stream.read(header);

            if (read < 4) {
                return false;
            }

            boolean png =
                    (header[0] & 0xFF) == 0x89
                            && header[1] == 0x50
                            && header[2] == 0x4E
                            && header[3] == 0x47;

            boolean jpeg =
                    (header[0] & 0xFF) == 0xFF
                            && (header[1] & 0xFF) == 0xD8
                            && (header[2] & 0xFF) == 0xFF;

            boolean webp =
                    read >= 12
                            && header[0] == 'R'
                            && header[1] == 'I'
                            && header[2] == 'F'
                            && header[3] == 'F'
                            && header[8] == 'W'
                            && header[9] == 'E'
                            && header[10] == 'B'
                            && header[11] == 'P';

            return png || jpeg || webp;

        } catch (Exception ignored) {
            return false;

        } finally {
            try {
                if (stream != null) stream.close();
            } catch (Exception ignored) {
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isHttp(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.startsWith("https://") || clean.startsWith("http://");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim()
                .toLowerCase(Locale.US)
                .replace("&", "and")
                .replace("’", "")
                .replace("'", "")
                .replace(".", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }
}
