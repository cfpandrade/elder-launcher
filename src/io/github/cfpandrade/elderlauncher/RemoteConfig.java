package io.github.cfpandrade.elderlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Optional: buttons and settings managed from a control plane, a service you host on your own server (the API is
 *  described in the README). Without remote.json the phone is standalone and this class does nothing.
 *
 *  The address and token live in remote.json, {"url": "https://...", "token": "..."}, which the installer hands over
 *  through ConfigProvider; never in the code.
 *  What is downloaded (config.json and photos) is stored in app-private storage: without network the last download is used.
 *  It also reports the apps that can be opened, so the server can offer them for the "More" page. */
final class RemoteConfig {
    private static final int TIMEOUT_MS = 15000;
    /** Never offered for "More": this app and Home Assistant's companion app. */
    private static final Set<String> EXCLUDED = new HashSet<>(java.util.Arrays.asList(
            "io.github.cfpandrade.elderlauncher", "io.homeassistant.companion.android"));

    private static boolean appsSent;         // once per process start, even if the server already has them

    private RemoteConfig() {
    }

    private static File folder(Context c) {
        File d = new File(c.getFilesDir(), "remote");
        new File(d, "photos").mkdirs();
        return d;
    }

    private static File configFile(Context c) {
        return new File(folder(c), "config.json");
    }

    private static File photo(Context c, String hash) {
        return new File(new File(folder(c), "photos"), hash + ".jpg");
    }

    /** {url, token} from remote.json, or null if missing or invalid. */
    private static String[] target(Context c) {
        File f = ConfigProvider.remote(c);
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(read(new java.io.FileInputStream(f)));
            String url = o.optString("url", "").trim();
            String token = o.optString("token", "").trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (!url.startsWith("https://")) {          // Android blocks cleartext traffic for this app
                Log.w(Config.TAG, "remote.json: the url must start with https://");
                return null;
            }
            if (token.isEmpty()) return null;
            return new String[]{url, token};
        } catch (Exception e) {
            Log.w(Config.TAG, "remote.json is not valid: " + e.getMessage());
            return null;
        }
    }

    static boolean configured(Context c) {
        return target(c) != null;
    }

    // ---------------------------------------------------------------- reading what was downloaded

    /** The last download in contacts.json format; null if nothing was ever downloaded. */
    static String asContacts(Context c) {
        File f = configFile(c);
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(read(new java.io.FileInputStream(f)));
            JSONArray bs = o.getJSONArray("buttons");
            JSONArray out = new JSONArray();
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                JSONObject p = new JSONObject();
                String name = b.optString("name", "?");
                p.put("name", name);
                String lookup = b.optString("lookup", "");
                p.put("lookup", lookup.isEmpty() ? name : lookup);
                p.put("phone", b.optString("phone", ""));
                String hash = b.optString("photo", "");
                if (!hash.isEmpty() && !"null".equals(hash)) p.put("photo_file", photo(c, hash).getPath());
                String full = b.optString("full_photo", "");
                if (!full.isEmpty() && !"null".equals(full)) p.put("full_photo_file", photo(c, full).getPath());
                out.put(p);
            }
            JSONObject r = new JSONObject();
            r.put("contacts", out);
            r.put("youtube", true);
            if (o.has("more")) r.put("more", o.getJSONArray("more"));
            return r.toString();
        } catch (Exception e) {
            Log.e(Config.TAG, "downloaded config is unreadable", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- download

    /** Downloads the config and any changed photos. Returns true if something changed. Background thread. */
    static synchronized boolean sync(Context c) {
        String[] d = target(c);
        if (d == null) return false;
        try {
            String fresh = read(open(d, "/api/launcher", null));
            JSONObject o = new JSONObject(fresh);
            if (!o.optBoolean("ok", false)) throw new IllegalStateException("response without ok");
            JSONArray bs = o.getJSONArray("buttons");
            int newPhotos = 0;
            Set<String> used = new HashSet<>();
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                // the card (button) and the whole photo (call screen)
                for (String field : new String[]{"photo", "full_photo"}) {
                    String hash = b.optString(field, "");
                    if (hash.isEmpty() || "null".equals(hash)) continue;
                    if (!hash.matches("[a-f0-9]{16}")) throw new IllegalStateException("unexpected photo hash");
                    used.add(hash + ".jpg");
                    File f = photo(c, hash);
                    if (f.isFile()) continue;
                    // If a photo fails nothing changes: everything is retried together next time
                    File tmp = new File(f.getPath() + ".tmp");
                    String path = "/api/launcher/photo/" + b.getString("id") + ("full_photo".equals(field) ? "?type=full" : "");
                    try (InputStream in = open(d, path, null);
                         OutputStream out = new FileOutputStream(tmp)) {
                        copy(in, out);
                    }
                    android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
                    op.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(tmp.getPath(), op);
                    if (op.outWidth <= 0) {
                        tmp.delete();
                        throw new IllegalStateException("invalid photo for " + b.optString("name"));
                    }
                    if (!tmp.renameTo(f)) throw new IllegalStateException("could not store the photo");
                    newPhotos++;
                }
            }
            File cfg = configFile(c);
            String old = cfg.isFile() ? read(new java.io.FileInputStream(cfg)) : null;
            boolean changed = !fresh.equals(old);
            if (changed) {
                File tmp = new File(cfg.getPath() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(fresh.getBytes(StandardCharsets.UTF_8));
                }
                tmp.renameTo(cfg);
            }
            File[] all = new File(folder(c), "photos").listFiles();
            if (all != null) for (File f : all) if (!used.contains(f.getName())) f.delete();
            // Settings from the server win over anything received by broadcast
            java.util.Map<String, Object> settings = new java.util.HashMap<>();
            for (String k : SettingsReceiver.BOOLEANS) if (o.has(k)) settings.put(k, o.optBoolean(k));
            for (String k : SettingsReceiver.INTEGERS.keySet()) if (o.has(k)) settings.put(k, o.optInt(k));
            for (String k : SettingsReceiver.TIMES) if (o.has(k)) settings.put(k, o.optString(k));
            if (SettingsReceiver.differ(c, settings))
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> SettingsReceiver.apply(c, settings));
            Log.i(Config.TAG, "remote: config downloaded (" + bs.length() + " buttons, " + newPhotos + " new photos"
                    + (changed ? ", changed" : ", unchanged") + ")");
            sendApps(c, d, o.optString("apps_hash", ""));
            return changed || newPhotos > 0;
        } catch (Exception e) {
            Log.w(Config.TAG, "remote: not downloaded (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); keeping the last copy");
            return false;
        }
    }

    // ---------------------------------------------------------------- apps for "More"

    /** The apps that can be opened (disabled ones do not show up), minus the excluded ones. */
    private static List<ResolveInfo> apps(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = c.getPackageManager().queryIntentActivities(i, 0);
        List<ResolveInfo> l = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (EXCLUDED.contains(p) || !seen.add(p)) continue;
            if (!r.activityInfo.enabled || !r.activityInfo.applicationInfo.enabled) continue;
            l.add(r);
        }
        return l;
    }

    private static void sendApps(Context c, String[] d, String serverHash) {
        try {
            PackageManager pm = c.getPackageManager();
            List<ResolveInfo> l = apps(c);
            List<String> lines = new ArrayList<>();
            for (ResolveInfo r : l) lines.add(r.activityInfo.packageName + "|" + r.loadLabel(pm));
            lines.add(Config.WIRELESS_DEBUGGING + "|Wireless debugging");
            Collections.sort(lines);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (String s : lines) md.update((s + "\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (byte b : md.digest()) h.append(String.format("%02x", b));
            String hash = h.toString();
            if (appsSent && hash.equals(serverHash)) return;
            JSONArray a = new JSONArray();
            for (ResolveInfo r : l) {
                JSONObject o = new JSONObject();
                o.put("package", r.activityInfo.packageName);
                o.put("name", String.valueOf(r.loadLabel(pm)));
                String ic = icon(r.loadIcon(pm));
                if (ic != null) o.put("icon", ic);
                a.put(o);
            }
            // an entry that is not an app: opens wireless debugging
            ResolveInfo dbg = pm.resolveActivity(Config.wirelessDebuggingIntent(), 0);
            JSONObject od = new JSONObject();
            od.put("package", Config.WIRELESS_DEBUGGING);
            od.put("name", "Wireless debugging");
            String icd = dbg != null ? icon(dbg.loadIcon(pm)) : null;
            if (icd != null) od.put("icon", icd);
            a.put(od);
            JSONObject body = new JSONObject();
            body.put("hash", hash);
            body.put("apps", a);
            read(open(d, "/api/launcher/apps", body.toString()));
            appsSent = true;
            Log.i(Config.TAG, "remote: app list sent (" + l.size() + ")");
        } catch (Exception e) {
            Log.w(Config.TAG, "remote: could not send the app list: " + e.getMessage());
        }
    }

    private static String icon(Drawable dr) {
        try {
            int t = 96;
            Bitmap b = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            dr.setBounds(0, 0, t, t);
            dr.draw(cv);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- http

    /** POST of a JSON body with the launcher token. true on HTTP 200. */
    static boolean send(Context c, String path, String body) {
        String[] d = target(c);
        if (d == null) return false;
        try {
            read(open(d, path, body));
            return true;
        } catch (Exception e) {
            Log.w(Config.TAG, "remote: could not send " + path + " (" + e.getMessage() + ")");
            return false;
        }
    }

    /** GET (or POST with JSON if body is not null) with the launcher token. Throws unless HTTP 200. */
    private static InputStream open(String[] d, String path, String body) throws Exception {
        HttpURLConnection k = (HttpURLConnection) new URL(d[0] + path).openConnection();
        k.setConnectTimeout(TIMEOUT_MS);
        k.setReadTimeout(TIMEOUT_MS);
        k.setUseCaches(false);
        k.setRequestProperty("X-Launcher-Token", d[1]);
        k.setRequestProperty("Accept", "application/json, image/jpeg");
        if (body != null) {
            k.setRequestMethod("POST");
            k.setDoOutput(true);
            k.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = k.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = k.getResponseCode();
        if (code != 200) {
            k.disconnect();
            throw new IllegalStateException("HTTP " + code + " on " + path);
        }
        return k.getInputStream();
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(i, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
