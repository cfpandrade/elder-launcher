package io.github.cfpandrade.elderlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Settings sent by broadcast (ADB, Home Assistant's companion app...), so a change arrives at once. The same settings also
 *  come with the remote configuration each time it is downloaded ({@link RemoteConfig}), in case a broadcast is lost.
 *  See the settings table in the README. */
public class SettingsReceiver extends BroadcastReceiver {
    static final String ACTION = "io.github.cfpandrade.elderlauncher.SETTING";
    static final String[] BOOLEANS = {"auto_answer", "brightness_max", "volume_outside", "block_power_menu", "keep_wireless_debugging"};
    /** key to {minimum, maximum}. */
    static final Map<String, int[]> INTEGERS = new HashMap<>();
    static final String[] TIMES = {"night_from", "night_to"};

    static {
        INTEGERS.put("volume_youtube", new int[]{0, 100});
        INTEGERS.put("night_brightness", new int[]{0, 100});
        INTEGERS.put("screen_timeout", new int[]{0, 3600});
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        Bundle b = i.getExtras();
        if (b == null) return;
        Map<String, Object> m = new HashMap<>();
        for (String k : b.keySet()) m.put(k, b.get(k));
        apply(c, m);
    }

    /** Stores the settings present in the map (the others stay as they are) and puts them into effect. */
    static void apply(Context c, Map<String, ?> m) {
        SharedPreferences.Editor e = Config.prefs(c).edit();
        StringBuilder log = new StringBuilder();
        for (String k : BOOLEANS) {
            Boolean v = bool(m.get(k));
            if (v == null) continue;
            e.putBoolean(k, v);
            log.append(' ').append(k).append('=').append(v);
        }
        for (Map.Entry<String, int[]> en : INTEGERS.entrySet()) {
            Integer v = integer(m.get(en.getKey()), en.getValue());
            if (v == null) continue;
            e.putInt(en.getKey(), v);
            log.append(' ').append(en.getKey()).append('=').append(v);
        }
        for (String k : TIMES) {
            Object o = m.get(k);
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (ScreenControl.minutes(s, -1) < 0) continue;
            e.putString(k, s);
            log.append(' ').append(k).append('=').append(s);
        }
        if (log.length() == 0) return;
        e.apply();
        Log.i(Config.TAG, "settings:" + log);
        ScreenControl.check(c, 0);
        MediaVolume.watch(c);
        PowerMenuGuard.watch(c);
        WirelessDebugging.check(c, 0);
    }

    /** Does any of these settings differ from the stored value? */
    static boolean differ(Context c, Map<String, ?> m) {
        SharedPreferences p = Config.prefs(c);
        for (String k : BOOLEANS) {
            Boolean v = bool(m.get(k));
            if (v != null && (!p.contains(k) || p.getBoolean(k, false) != v)) return true;
        }
        for (Map.Entry<String, int[]> en : INTEGERS.entrySet()) {
            Integer v = integer(m.get(en.getKey()), en.getValue());
            if (v != null && p.getInt(en.getKey(), Integer.MIN_VALUE) != v) return true;
        }
        for (String k : TIMES) {
            Object o = m.get(k);
            if (o != null && !String.valueOf(o).trim().equals(p.getString(k, ""))) return true;
        }
        return false;
    }

    private static Boolean bool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o).trim();
        if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
        if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
        return null;
    }

    private static Integer integer(Object o, int[] range) {
        if (o == null) return null;
        try {
            return Math.max(range[0], Math.min(range[1], Integer.parseInt(String.valueOf(o).trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean autoAnswer(Context c) {
        return Config.prefs(c).getBoolean("auto_answer", false);
    }
}
