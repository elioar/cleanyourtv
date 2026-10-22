package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The owner's Stremio auth key, for Continue watching: kept only in this app's private storage on the TV, sent
 * only to Stremio's own API. It arrives over adb as a broadcast the shell alone may send (the receiver requires
 * WRITE_SECURE_SETTINGS, which apps cannot hold):
 *   am broadcast -n com.gathin.tvhome/.StremioKey\$Set --es key <auth key>     (an empty key forgets it)
 */
final class StremioKey {
    private static final String PREFS = "stremio";

    private StremioKey() {
    }

    static String get(Context ctx) {
        String k = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("auth", null);
        return k == null || k.isEmpty() ? null : k;
    }

    static void forget(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("auth").commit();
    }

    /** The adb-only way in. */
    public static final class Set extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String key = intent.getStringExtra("key");
            if (key == null || key.trim().isEmpty()) {
                forget(ctx);
                setResultData("forgotten");
                return;
            }
            // Stremio's keys are short hex strings; anything else is a paste error, not something to store.
            if (!key.trim().matches("[A-Za-z0-9]{16,128}")) {
                setResultData("rejected");
                return;
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("auth", key.trim()).commit();
            // The next Home refresh fetches the library at once.
            ctx.getSharedPreferences("home_feeds", Context.MODE_PRIVATE).edit().remove("library").commit();
            setResultData("stored");
        }
    }
}
