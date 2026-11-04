package io.github.cfpandrade.elderlauncher;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;

/** Keeps the screen readable:
 *  <ul>
 *  <li>Brightness always at maximum ("brightness_max"): if it gets lowered, it comes back a few seconds later, and adaptive
 *      brightness is turned off so Android does not lower it either.</li>
 *  <li>Dimmer at night ("night_brightness" = % of maximum, 0 = same as daytime; from "night_from" to "night_to", HH:MM).</li>
 *  <li>Time until the screen turns off ("screen_timeout" = seconds, 0 = leave it alone).</li>
 *  </ul>
 *  Watched from the always-alive service ({@link CallWatcherService}) and also when returning to the home screen and when the
 *  screen turns on. Requires WRITE_SETTINGS, granted once over ADB:
 *  {@code appops set io.github.cfpandrade.elderlauncher WRITE_SETTINGS allow}. */
final class ScreenControl {
    private static final long DELAY_MS = 3000;           // after the last touch on the brightness bar
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean watching, warned;
    private static Context app;
    private static final Runnable apply = () -> apply(app);
    private static final Runnable boundary = () -> check(app, 0);     // night starts or ends

    private ScreenControl() {
    }

    static boolean active(Context c) {
        return Config.prefs(c).getBoolean("brightness_max", true);
    }

    /** Night brightness as % of maximum; 0 = same as daytime. */
    static int nightLevel(Context c) {
        int v = Config.prefs(c).getInt("night_brightness", 0);
        return v <= 0 || v >= 100 ? 0 : v;
    }

    /** Seconds until the screen turns off; 0 = leave it alone. */
    static int screenTimeout(Context c) {
        return Math.max(0, Math.min(3600, Config.prefs(c).getInt("screen_timeout", 0)));
    }

    /** "HH:MM" to minutes since midnight; the fallback if malformed. */
    static int minutes(String time, int fallback) {
        try {
            String[] p = time.trim().split(":");
            int hh = Integer.parseInt(p[0]), mm = Integer.parseInt(p[1]);
            if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return fallback;
            return hh * 60 + mm;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int nightFrom(Context c) {
        return minutes(Config.prefs(c).getString("night_from", "22:00"), 22 * 60);
    }

    private static int nightTo(Context c) {
        return minutes(Config.prefs(c).getString("night_to", "07:00"), 7 * 60);
    }

    private static int now() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
    }

    /** Is it night now (and is a night brightness set)? */
    static boolean isNight(Context c) {
        if (nightLevel(c) == 0) return false;
        int d = nightFrom(c), f = nightTo(c), n = now();
        if (d == f) return false;
        return d < f ? n >= d && n < f : n >= d || n < f;
    }

    /** Milliseconds until night next starts or ends. */
    private static long untilBoundary(Context c) {
        int n = now();
        long best = 24 * 60;
        for (int m : new int[]{nightFrom(c), nightTo(c)}) {
            long left = ((m - n) % (24 * 60) + 24 * 60) % (24 * 60);
            if (left == 0) left = 24 * 60;
            best = Math.min(best, left);
        }
        Calendar k = Calendar.getInstance();
        return best * 60_000L - k.get(Calendar.SECOND) * 1000L + 1500;
    }

    static boolean canWrite(Context c) {
        try {
            return Settings.System.canWrite(c);
        } catch (Exception e) {
            return false;
        }
    }

    /** This device's brightness setting maximum (usually 255; some go up to 2047 or 4095). */
    static int maximum() {
        try {
            int id = Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            int m = id != 0 ? Resources.getSystem().getInteger(id) : 0;
            if (m > 0) return m;
        } catch (Exception ignored) {
        }
        return 255;
    }

    /** Once, from the service: every change of brightness, adaptive mode or timeout is checked a few seconds later; screen on
     *  and clock changes are checked at once. */
    static void watch(Context ctx) {
        app = ctx.getApplicationContext();
        if (watching) {
            check(app, 0);
            return;
        }
        watching = true;
        ContentObserver o = new ContentObserver(h) {
            @Override
            public void onChange(boolean self) {
                check(app, DELAY_MS);
            }
        };
        ContentResolver cr = app.getContentResolver();
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, o);
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, o);
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, o);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                check(app, 0);
            }
        }, f);
        check(app, 0);
    }

    static void check(Context ctx, long delay) {
        if (ctx == null) return;
        app = ctx.getApplicationContext();
        h.removeCallbacks(apply);
        h.postDelayed(apply, delay);
    }

    private static void apply(Context c) {
        if (c == null) return;
        boolean brightness = active(c);
        int timeout = screenTimeout(c);
        if (!brightness && timeout == 0) return;
        if (!canWrite(c)) {
            if (!warned) Log.w(Config.TAG, "screen: missing WRITE_SETTINGS (appops set " + c.getPackageName() + " WRITE_SETTINGS allow)");
            warned = true;
            return;
        }
        ContentResolver cr = c.getContentResolver();
        if (brightness) try {
            int max = maximum();
            int target = max;
            String why = "maximum";
            if (isNight(c)) {
                int pct = nightLevel(c);
                target = Math.max(1, Math.round(max * pct / 100f));
                why = "night, " + pct + " %";
            }
            int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            int v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL || v != target) {
                if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target);
                // Android 11 also stores brightness as a 0..1 float; both are set in case that one wins
                Settings.System.putFloat(cr, "screen_brightness_float", max > 1 ? (target - 1) / (float) (max - 1) : 1f);
                Log.i(Config.TAG, "brightness: " + v + (mode != 0 ? " (adaptive)" : "") + " to " + target + "/" + max + " (" + why + ")");
            }
            h.removeCallbacks(boundary);
            if (nightLevel(c) != 0) h.postDelayed(boundary, untilBoundary(c));
        } catch (Exception e) {
            Log.w(Config.TAG, "brightness: could not set it: " + e.getMessage());
        }
        if (timeout > 0) try {
            int current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1);
            if (current != timeout * 1000) {
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, timeout * 1000);
                Log.i(Config.TAG, "screen: turns off after " + timeout + " s (was " + current / 1000 + " s)");
            }
        } catch (Exception e) {
            Log.w(Config.TAG, "screen: could not set the timeout: " + e.getMessage());
        }
    }
}
