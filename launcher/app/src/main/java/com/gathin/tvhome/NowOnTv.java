package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal home's Now on TV row: the TV's own antenna channels, with what is on now where the TV provider has a
 * guide for them. No internet. Reading the tuner's channels takes READ_TV_LISTINGS, granted over adb (pm grant);
 * without it, or with no channels, the list is empty and the row stays hidden. TCL's tuner keeps its programme guide
 * to its own TV app (the provider has no programmes for it), so on TCL TVs the cards show the channel only.
 */
final class NowOnTv {
    private static final String TAG = "TvHome.NowOnTv";
    /** Enough cards for a terrestrial line-up without making a long, heavy row. */
    private static final int MAX_CHANNELS = 40;
    private static final long WINDOW_MS = 6 * 3600_000L;

    static final class Channel {
        long id;
        String number = "", name = "";
        /** What is on now, and after it; title null when the guide has nothing for this channel. */
        String title, description, genre, poster;
        long start, end;
        String nextTitle;
        long nextStart;

        Uri uri() {
            return TvContract.buildChannelUri(id);
        }

        int minutesLeft(long now) {
            return (int) Math.max(0, Math.ceil((end - now) / 60_000.0));
        }

        float progress(long now) {
            return end > start ? Math.max(0f, Math.min(1f, (now - start) / (float) (end - start))) : 0f;
        }
    }

    interface Listener {
        void onNowOnTv(List<Channel> channels);
    }

    private final Context ctx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    NowOnTv(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void close() {
        worker.shutdownNow();
    }

    /** Reads the guide off the main thread and hands the channels to the listener on it. */
    void load(Listener listener) {
        worker.execute(() -> {
            List<Channel> out;
            try {
                out = read(ctx);
            } catch (Throwable t) {
                Log.w(TAG, "guide: " + t);
                out = new ArrayList<>();
            }
            final List<Channel> result = out;
            main.post(() -> listener.onNowOnTv(result));
        });
    }

    private static List<Channel> read(Context ctx) {
        List<Channel> channels = new ArrayList<>();
        Map<Long, Channel> byId = new HashMap<>();
        // Only the TV's own tuner channels: other apps keep preview rows and internet channels in the same table.
        java.util.Set<String> tuners = tunerInputs(ctx);
        String[] cp = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.COLUMN_BROWSABLE};
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI, cp, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.isNull(3) || !tuners.contains(c.getString(3))) continue;
                // Radio stations have nothing to watch (TCL writes its own service types, e.g. EN_TCL_DTV).
                String service = c.isNull(4) ? "" : c.getString(4).toUpperCase(java.util.Locale.ROOT);
                if (service.contains("RADIO") || service.equals(TvContract.Channels.SERVICE_TYPE_AUDIO)) continue;
                if (!c.isNull(5) && c.getInt(5) == 0) continue;
                Channel ch = new Channel();
                ch.id = c.getLong(0);
                ch.number = c.isNull(1) ? "" : c.getString(1);
                ch.name = c.isNull(2) ? "" : c.getString(2).trim();
                channels.add(ch);
                byId.put(ch.id, ch);
            }
        }
        if (channels.isEmpty()) {
            Log.i(TAG, "guide: no channels (tuners " + tuners + ")");
            return channels;
        }
        long now = System.currentTimeMillis();
        Uri programs = TvContract.Programs.CONTENT_URI.buildUpon()
                .appendQueryParameter("start_time", String.valueOf(now))
                .appendQueryParameter("end_time", String.valueOf(now + WINDOW_MS)).build();
        String[] pp = {TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_CANONICAL_GENRE,
                TvContract.Programs.COLUMN_BROADCAST_GENRE, TvContract.Programs.COLUMN_POSTER_ART_URI,
                TvContract.Programs.COLUMN_EPISODE_TITLE};
        int rows = 0;
        try (Cursor c = ctx.getContentResolver().query(programs, pp, null, null,
                TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS)) {
            while (c != null && c.moveToNext()) {
                rows++;
                Channel ch = byId.get(c.getLong(0));
                if (ch == null || c.isNull(1)) continue;
                long start = c.getLong(3), end = c.getLong(4);
                if (start <= now && end > now) {
                    ch.title = c.getString(1);
                    String episode = c.isNull(8) ? null : c.getString(8);
                    if (episode != null && !episode.isEmpty() && !episode.equals(ch.title)) ch.title += " · " + episode;
                    ch.description = c.isNull(2) ? "" : c.getString(2).trim();
                    ch.start = start;
                    ch.end = end;
                    ch.genre = genre(c.isNull(5) ? null : c.getString(5), c.isNull(6) ? null : c.getString(6));
                    ch.poster = c.isNull(7) ? null : c.getString(7);
                } else if (start > now && (ch.nextTitle == null || start < ch.nextStart)) {
                    ch.nextTitle = c.getString(1);
                    ch.nextStart = start;
                }
            }
        }
        // The next programme is the first one after the current one ends (a gap in the guide is allowed).
        for (Channel ch : channels) {
            if (ch.title != null && ch.nextTitle != null && ch.nextStart < ch.end) ch.nextTitle = null;
        }
        Log.i(TAG, "guide: " + channels.size() + " channels, " + rows + " programmes, now on " + count(channels));
        java.util.Collections.sort(channels, (a, b) -> compareNumbers(a.number, b.number));
        return channels.size() > MAX_CHANNELS ? new ArrayList<>(channels.subList(0, MAX_CHANNELS)) : channels;
    }

    /** The TV's own tuner inputs (system apps' TYPE_TUNER inputs, as the Inputs menu's Live TV picks). */
    private static java.util.Set<String> tunerInputs(Context ctx) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return ids;
        for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == android.media.tv.TvInputInfo.TYPE_TUNER
                    && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                ids.add(info.getId());
            }
        }
        return ids;
    }

    private static int count(List<Channel> channels) {
        int n = 0;
        for (Channel ch : channels) if (ch.title != null) n++;
        return n;
    }

    /** "5", "5-1" and "12" in channel order, not text order. */
    private static int compareNumbers(String a, String b) {
        String[] pa = a.split("[^0-9]+"), pb = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : -1;
            long y = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : -1;
            if (x != y) return Long.compare(x, y);
        }
        return a.compareTo(b);
    }

    /** The broadcaster's genre, or the first canonical one ("MOVIES" as "Movies"). */
    private static String genre(String canonical, String broadcast) {
        String g = null;
        if (broadcast != null && !broadcast.isEmpty()) g = TvContract.Programs.Genres.decode(broadcast)[0];
        if ((g == null || g.isEmpty()) && canonical != null && !canonical.isEmpty()) {
            g = TvContract.Programs.Genres.decode(canonical)[0].replace('_', ' ');
            g = g.isEmpty() ? null : g.charAt(0) + g.substring(1).toLowerCase(java.util.Locale.ROOT);
        }
        return g;
    }

    /** "20:00–21:30" in the TV's clock format. */
    static String times(Context ctx, long start, long end) {
        java.text.DateFormat f = android.text.format.DateFormat.getTimeFormat(ctx);
        return f.format(new java.util.Date(start)) + "–" + f.format(new java.util.Date(end));
    }

    static String time(Context ctx, long when) {
        return android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(when));
    }
}
