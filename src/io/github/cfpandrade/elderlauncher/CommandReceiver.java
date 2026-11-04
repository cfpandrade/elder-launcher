package io.github.cfpandrade.elderlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.util.regex.Pattern;

/** Remote commands by broadcast (ADB, Home Assistant's companion app command_broadcast_intent...):
 *  <ul>
 *  <li>{@code VIDEO} with {@code id} (11-character YouTube id) and {@code volume} (% of maximum): turns the screen on, sets
 *      media volume and opens the video full screen (see {@link VideoActivity}).</li>
 *  <li>{@code STOP}, optionally with {@code who}: stops whatever plays and returns home; with {@code who}, shows
 *      "Trying to reach you" with a button to call back (see {@link LookingForYouActivity}).</li>
 *  <li>{@code MEDIA} with {@code command} = pause | resume | stop | lower. "stop" equals STOP without {@code who}. "lower"
 *      (with optional {@code volume}, % of maximum) lowers media for a while (see {@link MediaVolume}).</li>
 *  <li>{@code MESSAGE} with {@code text} and {@code who}: full-screen message read aloud (see {@link MessageActivity}).</li>
 *  </ul>
 *  Anything else is ignored. */
public class CommandReceiver extends BroadcastReceiver {
    static final String VIDEO = "io.github.cfpandrade.elderlauncher.VIDEO";
    static final String STOP = "io.github.cfpandrade.elderlauncher.STOP";
    static final String MEDIA = "io.github.cfpandrade.elderlauncher.MEDIA";
    static final String MESSAGE = "io.github.cfpandrade.elderlauncher.MESSAGE";
    static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private static AudioFocusRequest focus;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (VIDEO.equals(a)) {
            String id = extra(i, "id").trim();
            if (!VIDEO_ID.matcher(id).matches()) {
                Log.w(Config.TAG, "video with an invalid id: ignored");
                return;
            }
            int vol = percent(extra(i, "volume"), MediaVolume.youtubeCap(c));
            Log.i(Config.TAG, "video requested: " + id + " at " + vol + " %");
            Intent v = new Intent(c, VideoActivity.class).putExtra("id", id).putExtra("volume", vol);
            v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            open(c, v);
        } else if (STOP.equals(a)) {
            String who = extra(i, "who").trim();
            Log.i(Config.TAG, "stop what is playing" + (who.isEmpty() ? "" : "; looked for by " + who));
            removeVideo(c, true);
            if (!who.isEmpty()) {
                Intent b = new Intent(c, LookingForYouActivity.class).putExtra("who", who);
                b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                open(c, b);
            }
        } else if (MESSAGE.equals(a)) {
            String text = extra(i, "text").trim(), who = extra(i, "who").trim();
            if (text.isEmpty()) return;
            if (text.length() > 400) text = text.substring(0, 400);
            Log.i(Config.TAG, "on-screen message from " + who);
            removeVideo(c, true);
            Intent m = new Intent(c, MessageActivity.class).putExtra("text", text).putExtra("who", who);
            m.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open(c, m);
        } else if (MEDIA.equals(a)) {
            String command = extra(i, "command").trim().toLowerCase();
            Log.i(Config.TAG, "remote control: " + command);
            // with the built-in video screen open, ONLY that one is controlled: the media key would also resume a YouTube app
            // left paused behind it, and two videos would play at once
            boolean builtIn = VideoActivity.alive() != null;
            if ("pause".equals(command)) {
                if (builtIn) VideoActivity.control("pause");
                else key(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
            } else if ("resume".equals(command)) {
                if (builtIn) VideoActivity.control("resume");
                else key(c, KeyEvent.KEYCODE_MEDIA_PLAY);
            } else if ("stop".equals(command)) {
                removeVideo(c, true);
            } else if ("lower".equals(command)) {
                MediaVolume.loweredRemotely(c, percent(extra(i, "volume"), MediaVolume.youtubeCap(c)));
            }
        }
    }

    private static int percent(String s, int fallback) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Removes the video: pauses what plays, closes the built-in video screen and brings the home screen to the front (YouTube
     *  stays behind, paused). {@code withFocus}: also takes audio focus from whoever holds it; NOT when a call comes in, since
     *  the call's ringtone uses audio focus too. */
    static void removeVideo(Context c, boolean withFocus) {
        if (withFocus) {
            stopAudio(c);
            MediaVolume.stoppedRemotely(c);
        } else key(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
        VideoActivity.close();
        Intent home = new Intent(c, HomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        open(c, home);
    }

    static void key(Context c, int code) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Exception e) {
            Log.w(Config.TAG, "media key " + code, e);
        }
    }

    /** Extras may arrive as String or with whatever type the sender guessed: they are read as text. */
    private static String extra(Intent i, String k) {
        Bundle b = i.getExtras();
        Object o = b == null ? null : b.get(k);
        return o == null ? "" : String.valueOf(o);
    }

    private static void open(Context c, Intent i) {
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Log.e(Config.TAG, "could not open " + i.getComponent(), e);
        }
    }

    /** Pauses what plays (media pause key) and takes audio focus from its holder: YouTube pauses and does not come back alone.
     *  Focus is released a few seconds later, so as not to get in the way of a voice announcement or a call. */
    private static void stopAudio(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        key(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
        try {
            final AudioFocusRequest f = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> { })
                    .build();
            focus = f;
            am.requestAudioFocus(f);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                am.abandonAudioFocusRequest(f);
                if (focus == f) focus = null;
            }, 3000);
        } catch (Exception e) {
            Log.w(Config.TAG, "audio focus", e);
        }
    }
}
