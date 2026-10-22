package com.gathin.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal home's line under the focused app's name: "Playing now · title" while the app has a playing media
 * session, otherwise "Last opened yesterday, 21:40". Last opened is the later of Android's usage history (needs
 * the usage-access app op, granted over adb) and the launcher's own LastOpened record, so apps started with the
 * remote's own buttons count too. Usage history is read once per Home resume, off the main thread.
 * Media sessions need MediaListener's notification access; without it the line simply never says Playing now.
 */
final class AppInfoLine {
    private final Context ctx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Map<String, Long> lastUsed = Collections.emptyMap();

    AppInfoLine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Re-reads usage history in the background; then runs done on the main thread. */
    void refresh(Runnable done) {
        worker.execute(() -> {
            Map<String, Long> fresh = new HashMap<>();
            try {
                UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
                long now = System.currentTimeMillis();
                Map<String, UsageStats> stats = usm == null ? null
                        : usm.queryAndAggregateUsageStats(now - 60L * 24 * 3600 * 1000, now);
                if (stats != null) {
                    for (Map.Entry<String, UsageStats> e : stats.entrySet()) {
                        long t = e.getValue().getLastTimeUsed();
                        if (t > 0) fresh.put(e.getKey(), t);
                    }
                }
            } catch (SecurityException ignored) {
                // no usage access: LastOpened alone
            }
            lastUsed = fresh;
            if (done != null) main.post(done);
        });
    }

    void close() {
        worker.shutdownNow();
    }

    /** The line for this package, or "" when there is nothing to say. */
    String describe(String pkg, String vpnDotApp, boolean vpnUp) {
        if (pkg == null) return "";
        if (pkg.equals(vpnDotApp)) {
            return ctx.getString(vpnUp ? R.string.info_vpn_connected : R.string.info_vpn_not_connected);
        }
        String playing = playing(pkg);
        if (playing != null) return playing;
        long t = Math.max(LastOpened.get(ctx, pkg), lastUsed.containsKey(pkg) ? lastUsed.get(pkg) : 0L);
        if (t <= 0) return "";
        return ctx.getString(R.string.last_opened, LastOpened.describe(ctx, t));
    }

    private String playing(String pkg) {
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            List<MediaController> sessions = msm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            for (MediaController c : sessions) {
                if (!pkg.equals(c.getPackageName())) continue;
                PlaybackState st = c.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                MediaMetadata md = c.getMetadata();
                CharSequence title = md == null ? null : md.getDescription().getTitle();
                return title == null || title.length() == 0 ? ctx.getString(R.string.info_playing_plain)
                        : ctx.getString(R.string.info_playing, title);
            }
        } catch (SecurityException ignored) {
            // notification access not granted
        }
        return null;
    }
}
