package io.github.cfpandrade.elderlauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.regex.Pattern;

/** Watches incoming call notifications (WhatsApp and phone) and opens the big "Answer" screen. Also the always-alive process
 *  that keeps ringer, brightness and volume rules running. */
public class CallWatcherService extends NotificationListenerService {
    /** Notification button labels, in the device's language: extend these for other languages. */
    static final Pattern ANSWER = Pattern.compile("(?i)answer|accept|contestar|responder|aceptar|atender|répondre|annehmen|rispondi");
    static final Pattern DECLINE = Pattern.compile("(?i)decline|reject|hang|ignore|rechazar|colgar|ignorar|cortar|recusar|desligar|refuser|raccrocher|ablehnen|auflegen|rifiuta");

    /** The call ringing now (one at a time). */
    static volatile String key;
    static volatile String caller;
    static volatile PendingIntent answer, decline;
    static volatile long lastPosted;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        CallState.posted(this, sbn);      // a call in progress? (who with, for the remote server)
        Notification n = sbn.getNotification();
        if (n == null || n.actions == null) return;
        boolean callNotification = Notification.CATEGORY_CALL.equals(n.category)
                || sbn.getPackageName().equals(Config.WHATSAPP)
                || sbn.getPackageName().contains("incallui")
                || sbn.getPackageName().contains("dialer")
                || sbn.getPackageName().contains("telecom");
        // a message is not a call: skip the message category, and below any reply-with-text buttons
        if (!callNotification || Notification.CATEGORY_MESSAGE.equals(n.category)) return;
        StringBuilder titles = new StringBuilder();
        for (Notification.Action a : n.actions) titles.append('[').append(a.title).append(']');
        Log.i(Config.TAG, "call notification: " + sbn.getPackageName() + " cat=" + n.category + " actions=" + titles);
        PendingIntent yes = null, no = null;
        for (Notification.Action a : n.actions) {
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;   // reply with text: a message
            String t = String.valueOf(a.title);
            if (yes == null && ANSWER.matcher(t).find()) yes = a.actionIntent;
            else if (no == null && DECLINE.matcher(t).find()) no = a.actionIntent;
        }
        if (yes == null || no == null) return;   // an incoming call has both answer AND decline (ongoing or message: no)
        ringerUp(this);                   // just in case, right when it rings
        boolean isNew = !sbn.getKey().equals(key) || answer == null;
        if (isNew) CommandReceiver.removeVideo(this, false);   // video out (paused, home in front) before the call screen
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        lastPosted = SystemClock.elapsedRealtime();
        key = sbn.getKey();
        caller = title == null ? "" : title.toString();
        answer = yes;
        decline = no;
        Log.i(Config.TAG, "incoming call from " + caller + " (" + sbn.getPackageName() + ")");
        // WhatsApp opens its own call screen right after and would cover ours: it is brought to the front several times during
        // the first seconds, while it keeps ringing
        if (!isNew) return;               // the same call posted again: already on screen
        CallState.ringing(this, caller, sbn.getPackageName());
        final String thisCall = key;
        Handler h = new Handler(Looper.getMainLooper());
        for (long d : new long[]{0, 900, 1800, 3000, 5000}) {
            h.postDelayed(() -> {
                if (thisCall == null || !thisCall.equals(key)) return;
                Intent i = new Intent(this, IncomingCallActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Log.e(Config.TAG, "could not open the call screen", e);
                }
            }, d);
        }
    }

    private ContentObserver volumeObserver;

    private static WeakReference<CallWatcherService> alive = new WeakReference<>(null);

    /** Is any call-in-progress notification left? */
    static boolean anyOngoing() {
        CallWatcherService l = alive.get();
        if (l == null) return false;
        try {
            for (StatusBarNotification s : l.getActiveNotifications()) if (CallState.ongoing(s)) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public void onListenerConnected() {
        alive = new WeakReference<>(this);
        Log.i(Config.TAG, "call watcher connected");
        // the ringer is ALWAYS loud: if it gets lowered, silenced or set to "do not disturb", it is restored at once
        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean self) {
                ringerUp(CallWatcherService.this);
            }
        };
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver);
        getContentResolver().registerContentObserver(Settings.Global.getUriFor("zen_mode"), false, volumeObserver);
        ringerUp(this);
        WirelessDebugging.watch(this);
        ScreenControl.watch(this);
        MediaVolume.watch(this);
        PowerMenuGuard.watch(this);
    }

    @Override
    public void onListenerDisconnected() {
        if (volumeObserver != null) getContentResolver().unregisterContentObserver(volumeObserver);
    }

    @Override
    public void onInterruptionFilterChanged(int filter) {
        ringerUp(this);
    }

    static void ringerUp(NotificationListenerService s) {
        try {
            AudioManager am = (AudioManager) s.getSystemService(AUDIO_SERVICE);
            if (s.getCurrentInterruptionFilter() > NotificationListenerService.INTERRUPTION_FILTER_ALL)
                s.requestInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_ALL);     // no "do not disturb"
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            for (int st : new int[]{AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_VOICE_CALL}) {
                int max = am.getStreamMaxVolume(st);
                if (am.getStreamVolume(st) < max) am.setStreamVolume(st, max, 0);
            }
        } catch (Exception e) {
            Log.w(Config.TAG, "ringer: " + e.getMessage());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        CallState.removed(this, sbn);
        if (!sbn.getKey().equals(key)) return;
        // WhatsApp removes and re-posts the notification while ringing: it only counts as over if no incoming call
        // notification is back within 2 s
        final String was = key;
        final long posted = lastPosted;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (was.equals(key) && lastPosted == posted && !stillRinging()) {
                key = null;
                answer = null;
                decline = null;
                Log.i(Config.TAG, "the call stopped ringing");
                CallState.ringing(this, null, "");
                IncomingCallActivity.close();
            }
        }, 2000);
    }

    /** Is there still a notification with an answer button? */
    private boolean stillRinging() {
        try {
            for (StatusBarNotification s : getActiveNotifications()) {
                Notification n = s.getNotification();
                if (n == null || n.actions == null) continue;
                for (Notification.Action a : n.actions)
                    if (ANSWER.matcher(String.valueOf(a.title)).find()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
