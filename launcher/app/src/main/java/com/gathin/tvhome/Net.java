package com.gathin.tvhome;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Minimal home's network and disk cache: plain HTTPS GETs (and the one POST Stremio's library needs), every call
 * from a background thread, with short timeouts so a dead network never holds anything up. Downloads land in
 * cacheDir/home, capped at MAX_CACHE_BYTES (oldest files go first), so Android may also clear it any time.
 */
final class Net {
    private static final int TIMEOUT_MS = 8000;
    private static final long MAX_CACHE_BYTES = 40L * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (Linux; Android 11; Smart TV) CleanYourTV";

    private Net() {
    }

    static String getText(String url) throws IOException {
        return new String(get(url, null), StandardCharsets.UTF_8);
    }

    static String postJson(String url, String body) throws IOException {
        return new String(get(url, body), StandardCharsets.UTF_8);
    }

    /** GET (or POST with a JSON body), following redirects across hosts and from http to https, up to 5. */
    static byte[] get(String url, String jsonBody) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            try {
                if (jsonBody != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code + " for " + url);
                try (InputStream in = c.getInputStream()) {
                    return readAll(in, 12 * 1024 * 1024);
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects: " + url);
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    // ---- disk cache ------------------------------------------------------------------------------

    static File cacheDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "home");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File cacheFile(Context ctx, String key) {
        return new File(cacheDir(ctx), sha1(key));
    }

    /** The cached copy of url, downloading it once if needed; null when offline and never cached. */
    static File cached(Context ctx, String url) {
        File f = cacheFile(ctx, url);
        if (f.isFile() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());   // recently used: trimmed last
            return f;
        }
        try {
            byte[] data = get(url, null);
            write(f, data);
            trim(ctx);
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    static String readFile(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static synchronized void trim(Context ctx) {
        File[] files = cacheDir(ctx).listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_CACHE_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
