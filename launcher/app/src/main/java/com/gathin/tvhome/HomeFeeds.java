package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal home's content: the Stremio row (Continue watching from the owner's Stremio library, then trending from
 * Stremio's public Cinemeta catalogue), the news row (YouTube news channels' feeds or Google News), the weather
 * (Open-Meteo) and the slideshow's daily pictures (Bing, NASA). One background thread, nothing runs while Home is
 * not on screen: MainActivity asks for a refresh on resume and once a minute, and each feed fetches only when its
 * own copy is older than its interval. The last copy of each is kept on disk, so Home fills in offline too.
 * No keys: the Stremio library alone needs the owner's own auth key, kept on the TV (StremioKey).
 */
final class HomeFeeds {
    private static final String TAG = "TvHome.Feeds";
    static final long WEATHER_MS = 30 * 60_000L, NEWS_MS = 30 * 60_000L, TRENDING_MS = 6 * 3600_000L;
    static final long LIBRARY_MS = 2 * 60_000L, SLIDES_MS = 12 * 3600_000L;

    // ---- settings keys (launcher_settings) ----
    static final String ROW_STREMIO = "row_stremio", ROW_HERO = "row_hero", ROW_NEWS = "row_news", ROW_TV = "row_tv";
    static final String NEWS_COUNTRY = "news_country", NEWS_SOURCE = "news_source";
    /** WEATHER_PLACE: the place picked in Launcher settings, as "name|latitude|longitude". */
    static final String WEATHER_ON = "weather_on", WEATHER_PLACE = "weather_place";
    static final String SOURCE_YOUTUBE = "yt", SOURCE_WEB = "web";

    /** Countries the news row offers: code, name, Google News edition, YouTube news channels. */
    static final String[][] COUNTRIES = {
            {"AL", "Albania", "hl=sq&gl=AL&ceid=AL:sq"},
            {"GR", "Greece", "hl=el&gl=GR&ceid=GR:el"},
            {"GB", "United Kingdom", "hl=en-GB&gl=GB&ceid=GB:en"},
            {"US", "United States", "hl=en-US&gl=US&ceid=US:en"},
    };
    /** YouTube channels with daily news uploads (checked 2026-10-21). Countries without any use web news. */
    private static final String[][] CHANNELS = {
            {"AL", "UCyny-dfYPbE-CjAkzkZqF8A"},   // Top Channel
            {"AL", "UC8X73IwfsLqlTaAHbXG05lg"},   // News24 Albania
            {"AL", "UCk_fYBSdzmwhZEDPG5L4ShQ"},   // Report TV
            {"AL", "UChR-A__NS_C5kHDWj3PeAhw"},   // Euronews Albania
            {"AL", "UCO-B2zbHROkxpMLjwQX6C8A"},   // A2 CNN
            {"AL", "UCrhOdWxeea0IF_H7jV4y7tQ"},   // Syri TV
            {"GR", "UCmHgxU394HiIAsN1fMegqzw"},   // SKAI
            {"GR", "UCllCEPTcZ_GplDaFsdq_utA"},   // OPEN
            {"GR", "UCJEE4jFtkG87uTMHcUFoMPg"},   // Kathimerini
    };
    /**
     * Web news for countries Google News has no edition for (it falls back to US English for Albania): the
     * outlets' own RSS feeds, merged newest first. Checked 2026-10-21.
     */
    private static final String[][] WEB_FEEDS = {
            {"AL", "BalkanWeb", "https://www.balkanweb.com/feed/"},
            {"AL", "Euronews Albania", "https://euronews.al/feed/"},
            {"AL", "Reporter.al", "https://www.reporter.al/feed/"},
            {"AL", "News24", "https://news24.al/feed/"},
    };


    /** One title in the Stremio row. */
    static final class Movie {
        String id, type, name, poster, background, logo, description, genres = "", year = "", runtime = "", rating = "";
        /** Continue watching: share watched (0..1), minutes left, the episode's video id; -1 / null otherwise. */
        float progress = -1f;
        int minutesLeft = -1;
        String videoId;

        String deepLink() {
            return "stremio:///detail/" + type + "/" + id + (videoId != null && !videoId.equals(id) ? "/" + videoId : "");
        }
    }

    /** One story or video in the news row. */
    static final class News {
        String title, source, link, videoId, image, description = "";
        long published;
        boolean live;

        String thumbnail() {
            if (image != null && !image.isEmpty()) return image;
            return videoId == null ? null : "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
        }
    }

    static final class Weather {
        String city;
        int temperature;
        int code;
        boolean day;
        /** The forecast panel: feels like, wind (km/h), today's rain chance (%), high and low; -1000 if unknown. */
        int feels = -1000, wind = -1000, rain = -1000, high = -1000, low = -1000;
        final List<Day> days = new ArrayList<>();
    }

    /** One of the next days: its date (yyyy-mm-dd), weather code, high, low and rain chance. */
    static final class Day {
        String date;
        int code, high, low, rain;
    }

    interface Listener {
        void onWeather(Weather w);

        void onStremio(List<Movie> continueWatching, List<Movie> trending);

        void onNews(List<News> items, String label);
    }

    private final Context ctx;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences settings;
    private final SharedPreferences state;

    HomeFeeds(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        settings = this.ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        state = this.ctx.getSharedPreferences("home_feeds", Context.MODE_PRIVATE);
    }

    void close() {
        worker.shutdownNow();
    }

    static boolean on(SharedPreferences s, String key) {
        return s.getBoolean(key, true);
    }

    static String country(SharedPreferences s) {
        return s.getString(NEWS_COUNTRY, "AL");
    }

    static String source(SharedPreferences s) {
        return s.getString(NEWS_SOURCE, SOURCE_YOUTUBE);
    }

    /** The weather place as {key, name, latitude, longitude}, or null until one is picked in Launcher settings. */
    static String[] city(SharedPreferences s) {
        return parsePlace(s.getString(WEATHER_PLACE, null));
    }

    /** "name|latitude|longitude" (or "name=latitude,longitude", as typed over adb) as {key, name, lat, lon}. */
    static String[] parsePlace(String v) {
        if (v == null) return null;
        String[] p = v.contains("|") ? v.split("\\|") : v.replaceFirst("=", "|").replaceFirst(",", "|").split("\\|");
        if (p.length != 3 || p[0].trim().isEmpty()) return null;
        try {
            double lat = Double.parseDouble(p[1].trim()), lon = Double.parseDouble(p[2].trim());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            String name = p[0].trim();
            return new String[]{name.toLowerCase(Locale.ROOT) + "@" + p[1].trim() + "," + p[2].trim(), name, p[1].trim(), p[2].trim()};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String place(String name, double lat, double lon) {
        return name.replace('|', ' ') + "|" + String.format(Locale.ROOT, "%.4f", lat) + "|" + String.format(Locale.ROOT, "%.4f", lon);
    }

    /**
     * Places matching what was typed, from Open-Meteo's free place search: {label, stored value} pairs, the label
     * as "Name, region, country". Network, so never on the main thread.
     */
    static List<String[]> searchPlaces(String query) throws Exception {
        String lang = Locale.getDefault().getLanguage();
        JSONObject j = new JSONObject(Net.getText("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language="
                + lang + "&name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")));
        List<String[]> out = new ArrayList<>();
        JSONArray r = j.optJSONArray("results");
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject p = r.getJSONObject(i);
            String name = p.optString("name");
            if (name.isEmpty() || !p.has("latitude")) continue;
            StringBuilder label = new StringBuilder(name);
            for (String k : new String[]{"admin2", "admin1", "country"}) {
                String part = p.optString(k);
                if (!part.isEmpty() && label.indexOf(part) < 0) label.append(", ").append(part);
            }
            out.add(new String[]{label.toString(), place(name, p.getDouble("latitude"), p.getDouble("longitude"))});
        }
        return out;
    }

    static String countryName(String code) {
        for (String[] c : COUNTRIES) if (c[0].equals(code)) return c[1];
        return code;
    }

    static boolean hasChannels(String code) {
        for (String[] c : CHANNELS) if (c[0].equals(code)) return true;
        return false;
    }

    /** Shows the copies on disk at once, then fetches whatever is due. force: ignore the intervals. */
    void refresh(boolean force) {
        worker.execute(() -> {
            if (on(settings, WEATHER_ON)) weather(force);
            if (on(settings, ROW_STREMIO)) stremio(force);
            if (on(settings, ROW_NEWS)) news(force);
        });
    }

    /** The last copies on disk, delivered right away (used on create, before anything is fetched). */
    void showCached() {
        worker.execute(() -> {
            if (on(settings, WEATHER_ON)) deliverWeather(load("weather.json"));
            if (on(settings, ROW_STREMIO)) deliverStremio(load("stremio.json"));
            if (on(settings, ROW_NEWS)) deliverNews(load("news.json"));
        });
    }

    private boolean due(String key, long interval, boolean force) {
        return force || System.currentTimeMillis() - state.getLong(key, 0) > interval;
    }

    private void done(String key) {
        state.edit().putLong(key, System.currentTimeMillis()).apply();
    }

    // ---- weather ----------------------------------------------------------------------------------

    private void weather(boolean force) {
        String[] city = city(settings);
        if (city == null) return;
        String key = "weather@" + city[0];
        // A copy from before the forecast panel has no days: fetch once now instead of waiting out the interval.
        String cached = load("weather.json");
        boolean noDays = cached == null || !parseOr(cached).has("days");
        if (!due(key, WEATHER_MS, force || noDays)) return;
        try {
            // The forecast panel's numbers come in the same request: no extra polling.
            JSONObject j = new JSONObject(Net.getText("https://api.open-meteo.com/v1/forecast?latitude=" + city[2]
                    + "&longitude=" + city[3] + "&current=temperature_2m,weather_code,is_day,apparent_temperature,"
                    + "wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                    + "precipitation_probability_max&forecast_days=6&timezone=auto"));
            JSONObject cur = j.getJSONObject("current");
            JSONObject out = new JSONObject().put("city", city[1])
                    .put("t", (int) Math.round(cur.getDouble("temperature_2m")))
                    .put("code", cur.getInt("weather_code")).put("day", cur.optInt("is_day", 1) == 1)
                    .put("feels", (int) Math.round(cur.optDouble("apparent_temperature", -1000)))
                    .put("wind", (int) Math.round(cur.optDouble("wind_speed_10m", -1000)));
            JSONObject daily = j.optJSONObject("daily");
            JSONArray days = new JSONArray();
            if (daily != null) {
                JSONArray time = daily.getJSONArray("time"), code = daily.getJSONArray("weather_code"),
                        max = daily.getJSONArray("temperature_2m_max"), min = daily.getJSONArray("temperature_2m_min"),
                        rain = daily.getJSONArray("precipitation_probability_max");
                for (int i = 0; i < time.length(); i++) {
                    days.put(new JSONObject().put("date", time.getString(i)).put("code", code.optInt(i, 0))
                            .put("hi", (int) Math.round(max.optDouble(i, 0))).put("lo", (int) Math.round(min.optDouble(i, 0)))
                            .put("rain", rain.isNull(i) ? -1 : rain.optInt(i, -1)));
                }
            }
            out.put("days", days);
            save("weather.json", out.toString());
            done(key);
            deliverWeather(out.toString());
        } catch (Exception e) {
            Log.w(TAG, "weather: " + e.getMessage());
        }
    }

    private void deliverWeather(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            Weather w = new Weather();
            String[] city = city(settings);
            if (city == null) return;
            w.city = city[1];
            // A copy for another city is stale: wait for the fetch instead of showing it under the new name.
            if (!w.city.equals(o.optString("city"))) return;
            w.temperature = o.getInt("t");
            w.code = o.getInt("code");
            w.day = o.optBoolean("day", true);
            w.feels = o.optInt("feels", -1000);
            w.wind = o.optInt("wind", -1000);
            JSONArray days = o.optJSONArray("days");
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
            for (int i = 0; days != null && i < days.length(); i++) {
                JSONObject d = days.getJSONObject(i);
                Day day = new Day();
                day.date = d.getString("date");
                day.code = d.getInt("code");
                day.high = d.getInt("hi");
                day.low = d.getInt("lo");
                day.rain = d.optInt("rain", -1);
                int cmp = day.date.compareTo(today);
                if (cmp == 0) {
                    w.high = day.high;
                    w.low = day.low;
                    w.rain = day.rain;
                } else if (cmp > 0 && w.days.size() < 5) {
                    w.days.add(day);
                }
            }
            main.post(() -> listener.onWeather(w));
        } catch (Exception ignored) {
        }
    }

    // ---- stremio ----------------------------------------------------------------------------------

    private void stremio(boolean force) {
        boolean trendingDue = due("trending", TRENDING_MS, force);
        String auth = StremioKey.get(ctx);
        boolean libraryDue = auth != null && due("library", LIBRARY_MS, force);
        if (!trendingDue && !libraryDue) return;
        try {
            JSONObject cached = parseOr(load("stremio.json"));
            JSONArray trending = cached.optJSONArray("trending");
            JSONArray cw = auth == null ? new JSONArray() : cached.optJSONArray("cw");
            if (trendingDue || trending == null) {
                trending = trending();
                done("trending");
            }
            if (libraryDue) {
                cw = library(auth);
                done("library");
            }
            JSONObject out = new JSONObject().put("trending", trending).put("cw", cw == null ? new JSONArray() : cw);
            save("stremio.json", out.toString());
            deliverStremio(out.toString());
        } catch (Exception e) {
            Log.w(TAG, "stremio: " + e.getMessage());
        }
    }

    /** Top movies and series from Cinemeta, alternating, 16 in all. */
    private JSONArray trending() throws Exception {
        JSONArray movies = new JSONObject(Net.getText("https://v3-cinemeta.strem.io/catalog/movie/top.json")).getJSONArray("metas");
        JSONArray series = new JSONObject(Net.getText("https://v3-cinemeta.strem.io/catalog/series/top.json")).getJSONArray("metas");
        JSONArray out = new JSONArray();
        for (int i = 0; i < 8; i++) {
            if (i < movies.length()) out.put(slim(movies.getJSONObject(i)));
            if (i < series.length()) out.put(slim(series.getJSONObject(i)));
        }
        return out;
    }

    /**
     * Continue watching: the owner's library items with a position, newest first, each completed from Cinemeta
     * (description, rating, artwork). The auth key goes only to Stremio's own API.
     */
    private JSONArray library(String auth) throws Exception {
        JSONObject req = new JSONObject().put("authKey", auth).put("collection", "libraryItem")
                .put("all", true).put("ids", new JSONArray());
        JSONObject res = new JSONObject(Net.postJson("https://api.strem.io/api/datastoreGet", req.toString()));
        JSONArray items = res.optJSONArray("result");
        List<JSONObject> watching = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            JSONObject st = it.optJSONObject("state");
            if (st == null || it.optBoolean("removed") && !it.optBoolean("temp")) continue;
            long offset = st.optLong("timeOffset"), duration = st.optLong("duration");
            String type = it.optString("type");
            if (offset <= 0 || duration <= 0 || offset > duration * 0.95) continue;
            if (!type.equals("movie") && !type.equals("series")) continue;
            watching.add(it);
        }
        Collections.sort(watching, (a, b) -> lastWatched(b).compareTo(lastWatched(a)));
        JSONArray out = new JSONArray();
        for (JSONObject it : watching.subList(0, Math.min(10, watching.size()))) {
            JSONObject st = it.getJSONObject("state");
            JSONObject m;
            try {
                m = slim(new JSONObject(Net.getText("https://v3-cinemeta.strem.io/meta/" + it.optString("type") + "/"
                        + it.optString("_id") + ".json")).getJSONObject("meta"));
            } catch (Exception e) {
                m = new JSONObject().put("id", it.optString("_id")).put("type", it.optString("type"))
                        .put("name", it.optString("name")).put("poster", it.optString("poster"));
            }
            long offset = st.optLong("timeOffset"), duration = st.optLong("duration");
            m.put("progress", offset / (double) duration).put("left", (int) ((duration - offset) / 60_000));
            if (!st.optString("video_id").isEmpty()) m.put("video", st.optString("video_id"));
            out.put(m);
        }
        return out;
    }

    private static String lastWatched(JSONObject it) {
        JSONObject st = it.optJSONObject("state");
        String t = st == null ? "" : st.optString("lastWatched", "");
        return t.isEmpty() ? it.optString("_mtime", "") : t;
    }

    /** Just the fields the row and the hero use. */
    private static JSONObject slim(JSONObject m) throws Exception {
        String id = m.optString("imdb_id", m.optString("id"));
        JSONObject o = new JSONObject().put("id", id).put("type", m.optString("type"))
                .put("name", m.optString("name")).put("description", m.optString("description"))
                .put("year", m.optString("releaseInfo", m.optString("year")))
                .put("runtime", m.optString("runtime")).put("rating", m.optString("imdbRating"));
        JSONArray g = m.optJSONArray("genres");
        if (g == null) g = m.optJSONArray("genre");
        StringBuilder genres = new StringBuilder();
        for (int i = 0; g != null && i < Math.min(2, g.length()); i++) {
            if (genres.length() > 0) genres.append(" · ");
            genres.append(g.optString(i));
        }
        o.put("genres", genres.toString());
        return o;
    }

    private void deliverStremio(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            List<Movie> cw = movies(o.optJSONArray("cw"));
            if (StremioKey.get(ctx) == null) cw.clear();
            List<Movie> trending = movies(o.optJSONArray("trending"));
            main.post(() -> listener.onStremio(cw, trending));
        } catch (Exception ignored) {
        }
    }

    private static List<Movie> movies(JSONArray a) {
        List<Movie> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null || m.optString("id").isEmpty()) continue;
            Movie x = new Movie();
            x.id = m.optString("id");
            x.type = m.optString("type", "movie");
            x.name = m.optString("name");
            // Artwork straight from Stremio's image server, at the size each place shows it.
            x.poster = "https://images.metahub.space/poster/small/" + x.id + "/img";
            x.background = "https://images.metahub.space/background/medium/" + x.id + "/img";
            x.logo = "https://images.metahub.space/logo/medium/" + x.id + "/img";
            x.description = m.optString("description");
            x.genres = m.optString("genres");
            x.year = m.optString("year");
            x.runtime = m.optString("runtime");
            x.rating = m.optString("rating");
            if (m.has("progress")) {
                x.progress = (float) m.optDouble("progress", -1);
                x.minutesLeft = m.optInt("left", -1);
            }
            x.videoId = m.optString("video", null);
            out.add(x);
        }
        return out;
    }

    // ---- news -------------------------------------------------------------------------------------

    private void news(boolean force) {
        String code = country(settings);
        boolean yt = SOURCE_YOUTUBE.equals(source(settings)) && hasChannels(code);
        String key = "news.v2@" + code + (yt ? "/yt" : "/web");
        if (!due(key, NEWS_MS, force)) return;
        try {
            List<News> items = yt ? youtube(code) : web(code);
            if (items.isEmpty()) return;
            JSONArray a = new JSONArray();
            for (News n : items) {
                a.put(new JSONObject().put("title", n.title).put("source", n.source).put("link", n.link)
                        .put("video", n.videoId == null ? "" : n.videoId).put("published", n.published)
                        .put("live", n.live).put("description", n.description)
                        .put("image", n.image == null ? "" : n.image));
            }
            String json = new JSONObject().put("key", key).put("items", a).toString();
            save("news.json", json);
            done(key);
            deliverNews(json);
        } catch (Exception e) {
            Log.w(TAG, "news: " + e.getMessage());
        }
    }

    private void deliverNews(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            String code = country(settings);
            boolean yt = SOURCE_YOUTUBE.equals(source(settings)) && hasChannels(code);
            // A copy for another country or source is stale: wait for the fetch.
            if (!o.optString("key").equals("news.v2@" + code + (yt ? "/yt" : "/web"))) return;
            JSONArray a = o.getJSONArray("items");
            List<News> items = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                News n = new News();
                n.title = j.optString("title");
                n.source = j.optString("source");
                n.link = j.optString("link");
                n.videoId = j.optString("video").isEmpty() ? null : j.optString("video");
                n.published = j.optLong("published");
                n.live = j.optBoolean("live");
                n.description = j.optString("description");
                n.image = j.optString("image").isEmpty() ? null : j.optString("image");
                items.add(n);
            }
            String label = ctx.getString(yt ? R.string.news_label_youtube : R.string.news_label_web, countryName(code));
            main.post(() -> listener.onNews(items, label));
        } catch (Exception ignored) {
        }
    }

    /** The newest uploads of the country's news channels, merged, newest first. */
    private List<News> youtube(String code) {
        List<News> all = new ArrayList<>();
        for (String[] ch : CHANNELS) {
            if (!ch[0].equals(code)) continue;
            try {
                all.addAll(parseYoutube(Net.getText("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch[1])));
            } catch (Exception e) {
                Log.w(TAG, "channel " + ch[1] + ": " + e.getMessage());
            }
        }
        Collections.sort(all, (a, b) -> Long.compare(b.published, a.published));
        return new ArrayList<>(all.subList(0, Math.min(15, all.size())));
    }

    private static List<News> parseYoutube(String xml) throws Exception {
        List<News> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        String channel = "";
        News cur = null;
        boolean inAuthor = false;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("entry")) cur = new News();
                else if (n.equals("author")) inAuthor = true;
                else if (cur == null && n.equals("title")) channel = p.nextText();
                else if (cur != null && n.equals("yt:videoId")) cur.videoId = p.nextText();
                else if (cur != null && n.equals("title")) cur.title = p.nextText();
                else if (cur != null && n.equals("name") && inAuthor) cur.source = p.nextText();
                else if (cur != null && n.equals("published")) {
                    try {
                        cur.published = iso.parse(p.nextText()).getTime();
                    } catch (Exception ignored) {
                    }
                } else if (cur != null && n.equals("media:description")) {
                    cur.description = p.nextText();
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (p.getName().equals("author")) inAuthor = false;
                if (p.getName().equals("entry") && cur != null) {
                    if (cur.source == null) cur.source = channel;
                    cur.link = "https://www.youtube.com/watch?v=" + cur.videoId;
                    cur.live = cur.title != null && (cur.title.contains("LIVE") || cur.title.contains("🔴"));
                    if (cur.videoId != null && cur.title != null) out.add(cur);
                    cur = null;
                }
            }
        }
        return out;
    }

    /** Google News top stories for the country: "Headline - Source", with the source split off. */
    private List<News> web(String code) throws Exception {
        List<News> own = new ArrayList<>();
        boolean hasOwn = false;
        for (String[] f : WEB_FEEDS) {
            if (!f[0].equals(code)) continue;
            hasOwn = true;
            try {
                own.addAll(parseRss(Net.getText(f[2]), f[1]));
            } catch (Exception e) {
                Log.w(TAG, "feed " + f[1] + ": " + e.getMessage());
            }
        }
        if (hasOwn) {
            Collections.sort(own, (a, b) -> Long.compare(b.published, a.published));
            return new ArrayList<>(own.subList(0, Math.min(15, own.size())));
        }
        String edition = COUNTRIES[0][2];
        for (String[] c : COUNTRIES) if (c[0].equals(code)) edition = c[2];
        String xml = Net.getText("https://news.google.com/rss?" + edition);
        List<News> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        News cur = null;
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < 15; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("item")) cur = new News();
                else if (cur != null && n.equals("title")) cur.title = p.nextText();
                else if (cur != null && n.equals("link")) cur.link = p.nextText();
                else if (cur != null && n.equals("source")) cur.source = p.nextText();
                else if (cur != null && n.equals("pubDate")) {
                    try {
                        cur.published = rfc.parse(p.nextText()).getTime();
                    } catch (Exception ignored) {
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.getName().equals("item") && cur != null) {
                if (cur.title != null) {
                    if (cur.source != null && cur.title.endsWith(" - " + cur.source)) {
                        cur.title = cur.title.substring(0, cur.title.length() - cur.source.length() - 3);
                    }
                    if (cur.source == null) cur.source = "";
                    out.add(cur);
                }
                cur = null;
            }
        }
        return out;
    }

    /** A site's own RSS: title, link, date, the first picture (media:content, enclosure or an img), the summary. */
    private static List<News> parseRss(String xml, String source) throws Exception {
        List<News> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        News cur = null;
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        java.util.regex.Pattern img = java.util.regex.Pattern.compile("<img[^>]+src=\"([^\"]+)\"");
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < 10; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("item")) {
                    cur = new News();
                    cur.source = source;
                } else if (cur != null && n.equals("title")) {
                    cur.title = android.text.Html.fromHtml(p.nextText(), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
                } else if (cur != null && n.equals("link")) {
                    cur.link = p.nextText().trim();
                } else if (cur != null && n.equals("pubDate")) {
                    try {
                        cur.published = rfc.parse(p.nextText().trim()).getTime();
                    } catch (Exception ignored) {
                    }
                } else if (cur != null && (n.equals("media:content") || n.equals("media:thumbnail") || n.equals("enclosure"))) {
                    String url = p.getAttributeValue(null, "url");
                    String type = p.getAttributeValue(null, "type");
                    if (cur.image == null && url != null && (type == null || type.startsWith("image"))) cur.image = url;
                } else if (cur != null && (n.equals("description") || n.equals("content:encoded"))) {
                    String html = p.nextText();
                    if (cur.image == null) {
                        java.util.regex.Matcher m = img.matcher(html);
                        if (m.find()) cur.image = m.group(1);
                    }
                    if (cur.description.isEmpty()) {
                        cur.description = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                                .toString().replace('\uFFFC', ' ').replaceAll("\\s+", " ").trim();
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.getName().equals("item") && cur != null) {
                if (cur.title != null && cur.link != null) out.add(cur);
                cur = null;
            }
        }
        return out;
    }

    // ---- slideshow pictures -----------------------------------------------------------------------

    static final String SLIDES_BING = "bing", SLIDES_NASA = "nasa", SLIDES_BOTH = "both";

    /**
     * Downloads the daily pictures (Bing's last 8, NASA's last 8 images of the day) into the slideshow folder,
     * downscaled to 1280 px, and writes the slideshow list. Runs on the caller's thread (settings run it in the
     * background). False when nothing could be fetched.
     */
    static boolean buildWebSlides(Context ctx, String source) {
        File dir = new File(HomeWallpaper.dir(ctx), "web");
        if (!dir.isDirectory()) dir.mkdirs();
        List<String> urls = new ArrayList<>();
        try {
            if (!SLIDES_NASA.equals(source)) {
                JSONArray imgs = new JSONObject(Net.getText(
                        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US")).getJSONArray("images");
                for (int i = 0; i < imgs.length(); i++) {
                    urls.add("https://www.bing.com" + imgs.getJSONObject(i).getString("urlbase") + "_1280x720.jpg");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bing: " + e.getMessage());
        }
        try {
            if (!SLIDES_BING.equals(source)) {
                String rss = Net.getText("https://www.nasa.gov/feeds/iotd-feed/");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<enclosure url=\"([^\"]+\\.(?:jpg|jpeg|png))\"").matcher(rss);
                int n = 0;
                List<String> nasa = new ArrayList<>();
                while (m.find() && n++ < 8) nasa.add(m.group(1));
                // Alternate with Bing's when both are on.
                List<String> merged = new ArrayList<>();
                for (int i = 0; i < Math.max(urls.size(), nasa.size()); i++) {
                    if (i < urls.size()) merged.add(urls.get(i));
                    if (i < nasa.size()) merged.add(nasa.get(i));
                }
                urls = merged;
            }
        } catch (Exception e) {
            Log.w(TAG, "nasa: " + e.getMessage());
        }
        List<File> files = new ArrayList<>();
        for (String url : urls) {
            File f = new File(dir, Net.sha1(url) + ".jpg");
            if (!f.isFile()) {
                File raw = Net.cached(ctx, url);
                if (raw == null) continue;
                Bitmap b = ImageLoader.decode(raw, 1280, 720);
                if (b == null) continue;
                try (FileOutputStream out = new FileOutputStream(f)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 86, out);
                } catch (Exception e) {
                    continue;
                } finally {
                    b.recycle();
                    raw.delete();   // the full-size download is not needed again
                }
            }
            files.add(f);
        }
        if (files.isEmpty()) return false;
        // Pictures that dropped out of the feeds go.
        File[] old = dir.listFiles();
        for (File f : old == null ? new File[0] : old) if (!files.contains(f)) f.delete();
        try (FileWriter w = new FileWriter(new File(HomeWallpaper.dir(ctx), "slides.txt"))) {
            for (File f : files) w.write(f.getPath() + "\n");
        } catch (Exception e) {
            return false;
        }
        ctx.getSharedPreferences("home_feeds", Context.MODE_PRIVATE).edit()
                .putLong("slides", System.currentTimeMillis()).apply();
        return true;
    }

    /** Fetches new daily pictures when the slideshow uses them and the last fetch is over SLIDES_MS old. */
    void refreshSlidesIfDue() {
        String src = settings.getString(HomeWallpaper.SOURCE_KEY, SLIDES_BOTH);
        if (!HomeWallpaper.MODE_SLIDESHOW.equals(settings.getString(HomeWallpaper.MODE_KEY, ""))
                || HomeWallpaper.SOURCE_PHOTOS.equals(src) || !due("slides", SLIDES_MS, false)) return;
        worker.execute(() -> buildWebSlides(ctx, src));
    }

    // ---- disk copies ------------------------------------------------------------------------------

    private File file(String name) {
        File d = new File(ctx.getFilesDir(), "home_feeds");
        if (!d.isDirectory()) d.mkdirs();
        return new File(d, name);
    }

    private void save(String name, String json) {
        try {
            Net.write(file(name), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String load(String name) {
        return Net.readFile(file(name));
    }

    private static JSONObject parseOr(String json) {
        try {
            return json == null ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** "25 min ago" style, for the news cards and hero. */
    static String ago(Context ctx, long when) {
        if (when <= 0) return "";
        return android.text.format.DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE)
                .toString();
    }

    static String date(long when) {
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(when));
    }
}
