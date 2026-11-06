package io.github.cfpandrade.elderlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Music and videos always play at one level, "volume_youtube" (50 % by default): any change returns to it, so it stays neither
 *  at maximum nor at zero. This applies to every app. YouTube does not report playback reliably (its media session can stay in
 *  state 0 or 1 while it plays), so there is no attempt to guess what is being watched.
 *  The only exception is a voice announcement: the "announce" command raises volume to maximum for {@link #ANNOUNCE_MS}, then
 *  it returns to the level. Ring, notification and call volume are always at maximum, separately
 *  ({@link CallWatcherService#ringerUp}). */
final class MediaVolume {
    static final int DEFAULT_PERCENT = 50;
    private static final String ACTION_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final long DELAY_MS = 1500;           // after the last press of the volume key
    private static final long ANNOUNCE_MS = 60_000;      // how long a voice announcement stays at maximum
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean watching;
    private static Context app;
    private static long announceUntil;
    private static int lastSet = -1;                     // the last value set here (its own broadcast is ignored)
    private static final Runnable apply = () -> apply(app);

    private MediaVolume() {
    }

    /** The level of music and videos, as % of maximum. */
    static int level(Context c) {
        return Math.max(0, Math.min(100, Config.prefs(c).getInt("volume_youtube", DEFAULT_PERCENT)));
    }

    /** Watches every change of media volume and returns it to the level. Safe to call repeatedly. */
    static void watch(Context ctx) {
        app = ctx.getApplicationContext();
        if (!watching) {
            watching = true;
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i.getIntExtra(EXTRA_TYPE, -1) != AudioManager.STREAM_MUSIC) return;
                    int v = i.getIntExtra(EXTRA_VALUE, -1);
                    if (v >= 0 && v == lastSet) return;  // my own change
                    schedule(DELAY_MS);
                }
            }, new IntentFilter(ACTION_CHANGED));
        }
        schedule(0);
    }

    /** A voice announcement is coming: maximum for a while, then back to the level by itself. */
    static void announce(Context ctx) {
        watch(ctx);
        announceUntil = SystemClock.elapsedRealtime() + ANNOUNCE_MS;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            lastSet = max;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
            Log.i(Config.TAG, "media volume: announcement, maximum for " + ANNOUNCE_MS / 1000 + " s");
        } catch (Exception e) {
            Log.w(Config.TAG, "media volume: could not raise it: " + e.getMessage());
        }
        schedule(ANNOUNCE_MS + 100);
    }

    private static void schedule(long delay) {
        h.removeCallbacks(apply);
        h.postDelayed(apply, delay);
    }

    private static void apply(Context c) {
        if (c == null) return;
        long left = announceUntil - SystemClock.elapsedRealtime();
        if (left > 0) {                                  // still announcing: check again when it ends
            schedule(left + 100);
            return;
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int pct = level(c);
            int want = Math.round(max * pct / 100f);
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v == want) return;
            lastSet = want;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
            Log.i(Config.TAG, "media volume: " + v + " to " + want + "/" + max + " (" + pct + " %)");
        } catch (Exception e) {
            Log.w(Config.TAG, "media volume: " + e.getMessage());
        }
    }
}
