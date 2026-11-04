package io.github.cfpandrade.elderlauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Optional ("keep_wireless_debugging", off by default): keeps wireless debugging on and reports its port to the remote server,
 *  so a relative can reconnect over ADB to help. This is remote access to the phone: enable it only on a trusted network.
 *  With WRITE_SECURE_SETTINGS (granted once over ADB) it turns debugging back on when Android turns it off (reboot, Wi-Fi
 *  change). The port changes every time: the phone's own {@code _adb-tls-connect._tcp} mDNS service is looked up and sent to
 *  {@code POST /api/launcher/adb}. ADB still needs a device that was paired with the phone beforehand. */
final class WirelessDebugging {
    private static final String KEY = "adb_wifi_enabled";
    private static final String SERVICE = "_adb-tls-connect._tcp";
    private static final long SEARCH_MS = 15000;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean watching, searching;
    private static String sent;          // "ip:port" the server already has

    private WirelessDebugging() {
    }

    private static boolean canEnable(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Once, from the always-alive service: watches the setting and Wi-Fi. */
    static void watch(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (watching) {
            check(c, 0);
            return;
        }
        watching = true;
        c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY), false, new ContentObserver(h) {
            @Override
            public void onChange(boolean propio) {
                check(c, 2000);
            }
        });
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network n) {
                            h.post(() -> {
                                sent = null;          // another network: the port and IP may differ
                                check(c, 5000);
                            });
                        }
                    });
        } catch (Exception e) {
            Log.w(Config.TAG, "wireless debugging: not watching Wi-Fi", e);
        }
        check(c, 3000);
    }

    /** Turns wireless debugging on if it is off and, shortly after, sends the port to the server. */
    static void check(Context c, long delay) {
        h.postDelayed(() -> {
            if (!Config.prefs(c).getBoolean("keep_wireless_debugging", false)) return;
            if (wifiIp(c) == null) return;          // no Wi-Fi, no wireless debugging
            boolean on = Settings.Global.getInt(c.getContentResolver(), KEY, 0) == 1;
            if (!on) {
                if (!canEnable(c)) {
                    Log.i(Config.TAG, "wireless debugging is off and WRITE_SECURE_SETTINGS is missing");
                    return;
                }
                try {
                    Settings.Global.putInt(c.getContentResolver(), KEY, 1);
                    Log.i(Config.TAG, "wireless debugging: turned back on");
                } catch (Exception e) {
                    Log.w(Config.TAG, "wireless debugging: could not turn it on", e);
                    return;
                }
                search(c, 4000);                    // adbd takes a moment to open the port
            } else search(c, 0);
        }, delay);
    }

    private static void search(Context c, long delay) {
        h.postDelayed(() -> {
            if (searching) return;
            String ip = wifiIp(c);
            if (ip == null) return;
            NsdManager nsd = c.getSystemService(NsdManager.class);
            searching = true;
            final NsdManager.DiscoveryListener[] l = new NsdManager.DiscoveryListener[1];
            Runnable stop = () -> {
                if (!searching) return;
                searching = false;
                try {
                    nsd.stopServiceDiscovery(l[0]);
                } catch (Exception ignored) {
                }
            };
            l[0] = new NsdManager.DiscoveryListener() {
                @Override
                public void onServiceFound(NsdServiceInfo s) {
                    h.post(() -> resolve(c, nsd, s, ip, stop, 0));
                }

                @Override
                public void onServiceLost(NsdServiceInfo s) {
                }

                @Override
                public void onDiscoveryStarted(String t) {
                }

                @Override
                public void onDiscoveryStopped(String t) {
                }

                @Override
                public void onStartDiscoveryFailed(String t, int e) {
                    searching = false;
                    Log.w(Config.TAG, "wireless debugging: port lookup failed (" + e + ")");
                }

                @Override
                public void onStopDiscoveryFailed(String t, int e) {
                }
            };
            try {
                nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l[0]);
                h.postDelayed(stop, SEARCH_MS);
            } catch (Exception e) {
                searching = false;
                Log.w(Config.TAG, "wireless debugging: port lookup failed", e);
            }
        }, delay);
    }

    private static boolean resolving;

    /** Android resolves one service at a time (error 3 if another is running): one after another, with retries. */
    private static void resolve(Context c, NsdManager nsd, NsdServiceInfo s, String ip, Runnable stop, int attempt) {
        if (!searching || attempt > 5) return;
        if (resolving) {
            h.postDelayed(() -> resolve(c, nsd, s, ip, stop, attempt + 1), 1500);
            return;
        }
        resolving = true;
        try {
            nsd.resolveService(s, new NsdManager.ResolveListener() {
                @Override
                public void onServiceResolved(NsdServiceInfo r) {
                    h.post(() -> {
                        resolving = false;
                        InetAddress a = r.getHost();
                        if (a == null || !ip.equals(a.getHostAddress())) return;     // another phone on the network
                        stop.run();
                        send(c, ip, r.getPort());
                    });
                }

                @Override
                public void onResolveFailed(NsdServiceInfo f, int e) {
                    h.post(() -> {
                        resolving = false;
                        if (e == NsdManager.FAILURE_ALREADY_ACTIVE)
                            h.postDelayed(() -> resolve(c, nsd, s, ip, stop, attempt + 1), 1500);
                        else Log.w(Config.TAG, "wireless debugging: could not resolve " + f.getServiceName() + " (" + e + ")");
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
            Log.w(Config.TAG, "wireless debugging: could not resolve", e);
        }
    }

    private static void send(Context c, String ip, int port) {
        String key = ip + ":" + port;
        if (key.equals(sent)) return;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("ip", ip);
                o.put("port", port);
                if (RemoteConfig.send(c, "/api/launcher/adb", o.toString())) {
                    sent = key;
                    Log.i(Config.TAG, "wireless debugging at " + key + ": sent to the server");
                }
            } catch (Exception e) {
                Log.w(Config.TAG, "wireless debugging: could not send the port", e);
            }
        }, "wireless-debugging").start();
    }

    /** The Wi-Fi IPv4 address, or null when not on Wi-Fi. */
    private static String wifiIp(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses())
                    if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
