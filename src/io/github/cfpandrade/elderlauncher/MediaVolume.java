package io.github.cfpandrade.elderlauncher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/** Media volume depending on what is happening:
 *  <ul>
 *  <li>Watching YouTube (the app or the built-in video screen): any change returns to the cap ("volume_youtube", 50 % by
 *      default).</li>
 *  <li>Outside YouTube: maximum, so voice announcements can be heard ("volume_outside", on by default). A remote "lower"
 *      command is honored for a while ({@link #HONOR_MS}).</li>
 *  </ul>
 *  Ring, notification and call volume are always at maximum, separately ({@link CallWatcherService#ringerUp}).
 *  YouTube is detected through its media session, which a notification listener may read: playing or paused counts; if it
 *  was stopped remotely it does not count until it plays again. Opening YouTube from its button counts from that moment. */
final class MediaVolume {
    static final int DEFAULT_YOUTUBE_PERCENT = 50;
    private static final String ACTION_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final long DELAY_MS = 1500;           // after the last press of the volume key
    private static final long GRACE_MS = 90_000;         // from opening YouTube until something starts playing
    private static final long HONOR_MS = 10 * 60_000;    // how long a remote "lower" is honored outside YouTube
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean watching;
    private static Context app;
    private static long openedAt;                        // YouTube opened from its button (0 = no)
    private static boolean stoppedRemotely;              // a paused YouTube does not count until it plays again
    private static long honorUntil;
    private static int lastSet = -1;                     // the last value set here (its own broadcast is ignored)
    private static MediaSessionManager sessions;
    private static ComponentName listener;
    private static MediaController youtube;              // the YouTube session being followed
    private static final Runnable apply = () -> apply(app);

    private static final MediaController.Callback stateChange = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            schedule(0);
        }

        @Override
        public void onSessionDestroyed() {
            schedule(0);
        }
    };

    private MediaVolume() {
    }

    /** The YouTube cap, as % of maximum. */
    static int youtubeCap(Context c) {
        return Math.max(0, Math.min(100, Config.prefs(c).getInt("volume_youtube", DEFAULT_YOUTUBE_PERCENT)));
    }

    static boolean maxOutside(Context c) {
        return Config.prefs(c).getBoolean("volume_outside", true);
    }

    /** Once, from the service: every change of media volume and of media sessions is checked. */
    static void watch(Context ctx) {
        app = ctx.getApplicationContext();
        if (watching) {
            schedule(0);
            return;
        }
        watching = true;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getIntExtra(EXTRA_TYPE, -1) != AudioManager.STREAM_MUSIC) return;
                int v = i.getIntExtra(EXTRA_VALUE, -1);
                if (v >= 0 && v == lastSet) return;      // my own change
                schedule(DELAY_MS);
            }
        }, new IntentFilter(ACTION_CHANGED));
        try {
            sessions = app.getSystemService(MediaSessionManager.class);
            listener = new ComponentName(app, CallWatcherService.class);
            sessions.addOnActiveSessionsChangedListener(l -> {
                follow(l);
                schedule(0);
            }, listener, h);
            follow(sessions.getActiveSessions(listener));
        } catch (Exception e) {
            Log.w(Config.TAG, "volume: cannot read media sessions (" + e.getMessage() + ")");
            sessions = null;
        }
        schedule(0);
    }

    /** Follows playing/paused changes of the YouTube session, if any. */
    private static void follow(List<MediaController> active) {
        MediaController found = null;
        if (active != null)
            for (MediaController m : active)
                if (Config.YOUTUBE.equals(m.getPackageName())) {
                    found = m;
                    break;
                }
        if (youtube != null && (found == null || !youtube.getSessionToken().equals(found.getSessionToken()))) {
            try {
                youtube.unregisterCallback(stateChange);
            } catch (Exception ignored) {
            }
            youtube = null;
        }
        if (found != null && youtube == null) {
            youtube = found;
            youtube.registerCallback(stateChange, h);
        }
    }

    /** From the home screen: check now (leaving YouTube if it was open without playing anything). */
    static void check(Context ctx) {
        app = ctx.getApplicationContext();
        openedAt = 0;
        schedule(0);
    }

    /** YouTube was just opened from its button: apply the cap before anything plays. */
    static void youtubeOpened(Context ctx) {
        app = ctx.getApplicationContext();
        openedAt = SystemClock.elapsedRealtime();
        stoppedRemotely = false;
        schedule(0);
    }

    /** The video was stopped remotely (STOP, message): even if YouTube stays paused behind, it no longer counts, and what comes
     *  next (a voice announcement, a message) must be heard, so a remote "lower" stops being honored. */
    static void stoppedRemotely(Context ctx) {
        app = ctx.getApplicationContext();
        stoppedRemotely = true;
        openedAt = 0;
        honorUntil = 0;
        schedule(0);
    }

    /** A remote "lower" command: media volume to that %, honored for a while outside YouTube. */
    static void loweredRemotely(Context ctx, int pct) {
        app = ctx.getApplicationContext();
        honorUntil = SystemClock.elapsedRealtime() + HONOR_MS;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.round(max * Math.max(0, Math.min(100, pct)) / 100f);
            lastSet = v;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Log.i(Config.TAG, "media volume: lowered remotely to " + v + "/" + max + " (" + pct + " %), honored for "
                    + HONOR_MS / 60_000 + " min");
        } catch (Exception e) {
            Log.w(Config.TAG, "media volume: could not lower it: " + e.getMessage());
        }
        schedule(0);
    }

    private static void schedule(long delay) {
        h.removeCallbacks(apply);
        h.postDelayed(apply, delay);
    }

    static boolean watchingYoutube() {
        if (VideoActivity.alive() != null) return true;
        if (openedAt != 0 && SystemClock.elapsedRealtime() - openedAt < GRACE_MS) return true;
        MediaController yt = youtube;
        if (yt == null) return false;
        PlaybackState ps;
        try {
            ps = yt.getPlaybackState();
        } catch (Exception e) {
            return false;
        }
        int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
            stoppedRemotely = false;
            openedAt = 0;
            return true;
        }
        if (st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_CONNECTING) return !stoppedRemotely;
        return false;
    }

    private static void apply(Context c) {
        if (c == null) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int want;
            String why;
            if (watchingYoutube()) {
                int pct = youtubeCap(c);
                want = Math.round(max * pct / 100f);
                why = "watching YouTube, " + pct + " %";
            } else if (maxOutside(c)) {
                if (SystemClock.elapsedRealtime() < honorUntil) return;     // just lowered remotely
                want = max;
                why = "outside YouTube, maximum";
            } else return;
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v == want) return;
            lastSet = want;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
            Log.i(Config.TAG, "media volume: " + v + " to " + want + "/" + max + " (" + why + ")");
        } catch (Exception e) {
            Log.w(Config.TAG, "media volume: " + e.getMessage());
        }
    }
}
