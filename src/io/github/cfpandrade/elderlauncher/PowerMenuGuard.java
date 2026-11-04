package io.github.cfpandrade.elderlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.regex.Pattern;

/** Optional ("block_power_menu", off by default): closes the power-off / restart / airplane mode menu of the side button.
 *  Android does not let an app prevent that menu, but an accessibility service sees the window as soon as it appears and can
 *  dismiss it with "back". The menu is recognized by its labels (a "power off" button next to "restart" or "airplane mode") or
 *  by its window class (GlobalActions). Holding the button for many seconds still forces a restart: that is hardware.
 *  The launcher enables the service itself using WRITE_SECURE_SETTINGS, and re-enables it if Android removes it. */
public class PowerMenuGuard extends AccessibilityService {
    private static final String ENABLED_SERVICES = "enabled_accessibility_services";
    /** Labels shown by the system in the device's language: extend these for other languages. */
    private static final Pattern POWER_OFF = Pattern.compile("(?i)^(power off|shut ?down|turn off|apagar|apagar el (teléfono|dispositivo)|desligar)$");
    private static final Pattern OTHER = Pattern.compile("(?i)^(restart|reboot|airplane mode|flight mode|reiniciar|modo avión|modo avion|modo avião)$");
    private static final int MAX_NODES = 300;           // the menu is small; a long list is not walked in full
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean watching, warned;
    private static Context app;
    private static final Runnable sync = () -> sync(app);
    private long closedAt;

    static boolean active(Context c) {
        return Config.prefs(c).getBoolean("block_power_menu", false);
    }

    private static boolean canEnable(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** From the always-alive service and on every settings change: keeps the accessibility service in line with the setting. */
    static void watch(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (!watching) {
            watching = true;
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(ENABLED_SERVICES), false, new ContentObserver(h) {
                @Override
                public void onChange(boolean self) {
                    h.removeCallbacks(sync);
                    h.postDelayed(sync, 2000);
                }
            });
        }
        app = c;
        h.removeCallbacks(sync);
        h.post(sync);
    }

    private static void sync(Context c) {
        if (c == null) return;
        try {
            String mine = new ComponentName(c, PowerMenuGuard.class).flattenToString();
            String list = Settings.Secure.getString(c.getContentResolver(), ENABLED_SERVICES);
            if (list == null) list = "";
            StringBuilder others = new StringBuilder();
            boolean enabled = false;
            for (String s : list.split(":")) {
                if (s.equals(mine)) enabled = true;
                else if (!s.isEmpty()) others.append(others.length() == 0 ? "" : ":").append(s);
            }
            boolean wanted = active(c);
            if (enabled == wanted) return;
            if (!canEnable(c)) {
                if (!warned) Log.w(Config.TAG, "power menu: missing WRITE_SECURE_SETTINGS (pm grant " + c.getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS)");
                warned = true;
                return;
            }
            if (wanted) {
                Settings.Secure.putString(c.getContentResolver(), ENABLED_SERVICES, list.isEmpty() ? mine : list + ":" + mine);
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } else {
                Settings.Secure.putString(c.getContentResolver(), ENABLED_SERVICES, others.toString());
            }
            Log.i(Config.TAG, "power menu: accessibility service " + (wanted ? "enabled" : "disabled"));
        } catch (Exception e) {
            Log.w(Config.TAG, "power menu: could not change the accessibility service: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the service

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(Config.TAG, "power menu: watching");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || !active(this)) return;
        String pkg = String.valueOf(e.getPackageName());
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = String.valueOf(e.getClassName());
            if (isSystem(pkg)) Log.d(Config.TAG, "power menu: window " + pkg + " " + cls);
            if (cls.toLowerCase().contains("globalaction")) {
                close("class " + cls);
                return;
            }
        }
        if (menuShown()) close("labels");
    }

    private static boolean isSystem(String pkg) {
        return pkg.contains("systemui") || "android".equals(pkg);
    }

    /** Is the power menu on screen? Its buttons are looked for in the system windows. */
    private boolean menuShown() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Exception e) {
            return false;
        }
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root;
            try {
                root = w.getRoot();
            } catch (Exception e) {
                continue;
            }
            if (root == null) continue;
            if (!isSystem(String.valueOf(root.getPackageName()))) continue;
            boolean[] seen = new boolean[2];
            search(root, seen, new int[]{0});
            if (seen[0] && seen[1]) return true;
        }
        return false;
    }

    private static void search(AccessibilityNodeInfo n, boolean[] seen, int[] count) {
        if (n == null || ++count[0] > MAX_NODES || (seen[0] && seen[1])) return;
        for (CharSequence t : new CharSequence[]{n.getText(), n.getContentDescription()}) {
            if (t == null) continue;
            String s = t.toString().trim();
            if (!seen[0] && POWER_OFF.matcher(s).matches()) seen[0] = true;
            else if (!seen[1] && OTHER.matcher(s).matches()) seen[1] = true;
        }
        int children = n.getChildCount();
        for (int i = 0; i < children; i++) search(n.getChild(i), seen, count);
    }

    private void close(String why) {
        long now = SystemClock.elapsedRealtime();
        if (now - closedAt < 400) return;
        closedAt = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(Config.TAG, "power menu: closed (" + why + ")");
        // if "back" was not enough, once more; if that fails too, leave a trace in the log
        h.postDelayed(() -> {
            if (!menuShown()) return;
            performGlobalAction(GLOBAL_ACTION_BACK);
            h.postDelayed(() -> {
                if (menuShown()) Log.w(Config.TAG, "power menu: still there after two \"back\" actions");
            }, 600);
        }, 500);
    }

    @Override
    public void onInterrupt() {
    }
}
