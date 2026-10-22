package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Control center's sleep timer: one alarm, no service. When it fires the TV goes to standby through
 * HomeKeyService (as Turn off now does), or through the system's own sleep action if that service is not bound.
 * A reboot clears the timer, as a TV's own sleep timer does.
 */
final class SleepTimer {
    private static final String PREFS = "sleep_timer";
    private static final String AT = "at";
    static final int[] CHOICES = {0, 15, 30, 60, 90};

    private SleepTimer() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, Fired.class);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Minutes left, rounded up; 0 when no timer runs. */
    static int minutesLeft(Context ctx) {
        long at = prefs(ctx).getLong(AT, 0);
        long left = at - System.currentTimeMillis();
        if (at == 0 || left <= 0) return 0;
        return (int) ((left + 59_999) / 60_000);
    }

    /** The CHOICES entry the running timer was set to, for cycling with OK; 0 when off. */
    static int chosen(Context ctx) {
        return prefs(ctx).getInt("chosen", 0);
    }

    /** Starts (or replaces) the timer; 0 turns it off. */
    static void set(Context ctx, int minutes) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(ctx);
        if (am != null) am.cancel(pi);
        if (minutes <= 0) {
            prefs(ctx).edit().remove(AT).remove("chosen").apply();
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(ctx).edit().putLong(AT, at).putInt("chosen", minutes).apply();
        if (am != null) am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Standby now: HomeKeyService if bound, otherwise the system's sleep action. */
    static void sleep(Context ctx) {
        if (HomeKeyService.sleepNow()) return;
        try {
            ctx.startActivity(new Intent("com.google.android.pano.action.SLEEP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** The alarm. */
    public static final class Fired extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            prefs(ctx).edit().remove(AT).remove("chosen").apply();
            sleep(ctx);
        }
    }
}
