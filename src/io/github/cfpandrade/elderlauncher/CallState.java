package io.github.cfpandrade.elderlauncher;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

/** Who the phone is talking to, from call notifications: the ringing one and the "ongoing call" one that WhatsApp and the
 *  dialer keep posted during the call, titled with the other person's name. Each change goes to
 *  {@code POST /api/launcher/call} when a remote server is configured. */
final class CallState {
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static String key, with, state = "idle";
    private static long since;

    private CallState() {
    }

    private static boolean fromCallApp(StatusBarNotification sbn) {
        String p = sbn.getPackageName();
        return p.equals(Config.WHATSAPP) || p.contains("incallui") || p.contains("dialer") || p.contains("telecom");
    }

    /** A call-in-progress notification: category call, ongoing and without an answer button. */
    static boolean ongoing(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || !fromCallApp(sbn) || !Notification.CATEGORY_CALL.equals(n.category)) return false;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        if (n.actions != null)
            for (Notification.Action a : n.actions) {
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                if (CallWatcherService.ANSWER.matcher(String.valueOf(a.title)).find()) return false;     // ringing, not ongoing
            }
        return true;
    }

    static void posted(Context c, StatusBarNotification sbn) {
        if (!ongoing(sbn)) return;
        Notification n = sbn.getNotification();
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        String name = t == null ? "" : t.toString().trim();
        if (sbn.getKey().equals(key) && "in_call".equals(state) && name.equals(with)) return;
        Log.i(Config.TAG, "call in progress: \"" + name + "\" (" + sbn.getPackageName() + ")");
        boolean isNew = !sbn.getKey().equals(key) || !"in_call".equals(state);
        key = sbn.getKey();
        if (name.isEmpty() && CallWatcherService.caller != null) name = CallWatcherService.caller;
        with = name;
        if (isNew) since = System.currentTimeMillis();
        state = "in_call";
        send(c, sbn.getPackageName());
    }

    static void removed(Context c, StatusBarNotification sbn) {
        if (!sbn.getKey().equals(key) || !"in_call".equals(state)) return;
        final String was = key;
        // WhatsApp sometimes removes and re-posts it: only over if none is ongoing 2 s later
        h.postDelayed(() -> {
            if (!was.equals(key) || !"in_call".equals(state)) return;
            if (CallWatcherService.anyOngoing()) return;
            state = "idle";
            key = null;
            Log.i(Config.TAG, "call with \"" + with + "\" ended");
            send(c, sbn.getPackageName());
        }, 2000);
    }

    /** A call from `who` is ringing (or, with null, stopped ringing unanswered). */
    static void ringing(Context c, String who, String app) {
        if ("in_call".equals(state)) return;
        if (who != null) {
            if ("ringing".equals(state) && who.equals(with)) return;
            state = "ringing";
            with = who;
            since = System.currentTimeMillis();
        } else {
            if (!"ringing".equals(state)) return;
            state = "idle";
        }
        send(c, app);
    }

    private static void send(Context ctx, String app) {
        final Context c = ctx.getApplicationContext();
        final String s = state, n = with == null ? "" : with;
        final long d = since;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("state", s);
                o.put("with", n);
                o.put("since", d);
                o.put("app", Config.WHATSAPP.equals(app) ? "whatsapp" : "phone");
                RemoteConfig.send(c, "/api/launcher/call", o.toString());
            } catch (Exception x) {
                Log.w(Config.TAG, "could not report the call", x);
            }
        }, "call-state").start();
    }
}
