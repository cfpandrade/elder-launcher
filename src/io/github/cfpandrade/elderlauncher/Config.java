package io.github.cfpandrade.elderlauncher;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Configuration (contact buttons and "More" apps) and looking each contact up in the address book.
 *  Sources, first match wins: the last remote download ({@link RemoteConfig}), then
 *  {@code Android/data/<package>/files/contacts.json} (pushed by the installer), then {@code assets/contacts.json}. */
final class Config {
    static final String TAG = "ElderLauncher";
    static final String MIME_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
    static final String MIME_CHAT = "vnd.android.cursor.item/vnd.com.whatsapp.profile";
    static final String WHATSAPP = "com.whatsapp";
    static final String YOUTUBE = "com.google.android.youtube";
    /** A "More" entry that is not an app: opens Developer options at wireless debugging. */
    static final String WIRELESS_DEBUGGING = "android.wireless.debugging";

    private static final String PREFS = "settings";

    static Intent wirelessDebuggingIntent() {
        Intent i = new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS");
        i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** A button on the home screen. */
    static final class Contact {
        String name, lookup, phone, photoName;
        String photoFile;        // photo downloaded from the server (app-private storage)
        String fullPhotoFile;    // the whole, uncropped photo for the call screen
        long contactId = -1;
        long callRow = -1;       // _ID of WhatsApp's voip.call row
        long chatRow = -1;       // _ID of the profile row (opens the chat)
        String jid;              // from WhatsApp's row, if any
        Bitmap photo;

        String state() {
            if (callRow >= 0) return "direct call";
            if (chatRow >= 0 || phone != null) return "chat only";
            if (contactId >= 0) return "contact without WhatsApp";
            return "NOT FOUND";
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream stream) throws java.io.IOException {
        try (InputStream in = stream) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private static String defaultJson(Context c) {
        File local = ConfigProvider.contacts(c);
        if (local.isFile()) {
            try {
                String s = readAll(new FileInputStream(local));
                new JSONObject(s);
                return s;
            } catch (Exception e) {
                Log.w(TAG, "contacts.json is not valid: " + e.getMessage());
            }
        }
        try {
            return readAll(c.getAssets().open("contacts.json"));
        } catch (Exception e) {
            return "{\"contacts\":[],\"youtube\":true}";
        }
    }

    /** The configuration in contacts.json format, from the first source available. */
    static String json(Context c) {
        // without remote.json the phone is standalone: a copy left by a previous server must not hide contacts.json
        String r = RemoteConfig.configured(c) ? RemoteConfig.asContacts(c) : null;
        return r != null ? r : defaultJson(c);
    }

    /** Packages of the "More" apps, in order; null if not configured (then, the defaults). */
    static List<String> more(Context c) {
        try {
            JSONArray a = new JSONObject(json(c)).optJSONArray("more");
            if (a == null) return null;
            List<String> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean youtube(Context c) {
        try {
            return new JSONObject(json(c)).optBoolean("youtube", true);
        } catch (Exception e) {
            return true;
        }
    }

    static List<Contact> contacts(Context c) {
        List<Contact> l = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json(c)).getJSONArray("contacts");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Contact p = new Contact();
                p.name = o.optString("name", "?");
                p.lookup = o.optString("lookup", p.name);
                String t = o.optString("phone", "");
                p.phone = t.isEmpty() ? null : t;
                String f = o.optString("photo", "");
                p.photoName = f.isEmpty() ? null : f;
                String ff = o.optString("photo_file", "");
                p.photoFile = ff.isEmpty() ? null : ff;
                String fo = o.optString("full_photo_file", "");
                p.fullPhotoFile = fo.isEmpty() ? null : fo;
                l.add(p);
            }
        } catch (Exception e) {
            Log.e(TAG, "config", e);
        }
        return l;
    }

    // ---------------------------------------------------------------- address book

    static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /** Fills contactId, WhatsApp rows and photo. Needs READ_CONTACTS. */
    static void resolve(Context c, Contact p) {
        ContentResolver cr = c.getContentResolver();
        try {
            // 1) With a phone number: the WhatsApp row whose jid is that number.
            if (p.phone != null) {
                String d = digits(p.phone);
                whatsappRows(cr, p, ContactsContract.Data.DATA1 + " = ?", new String[]{d + "@s.whatsapp.net"});
                if (p.contactId < 0) {
                    Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(p.phone));
                    try (Cursor k = cr.query(u, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
                        if (k != null && k.moveToFirst()) p.contactId = k.getLong(0);
                    }
                }
            }
            // 2) By name in the address book: exact, then prefix.
            if (p.contactId < 0 && p.lookup != null && !p.lookup.isEmpty()) {
                p.contactId = byName(cr, ContactsContract.Contacts.DISPLAY_NAME + " = ? COLLATE NOCASE", p.lookup);
                if (p.contactId < 0)
                    p.contactId = byName(cr, ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?", p.lookup + "%");
            }
            // 3) WhatsApp rows of that contact.
            if (p.contactId >= 0 && p.callRow < 0) {
                whatsappRows(cr, p, ContactsContract.Data.CONTACT_ID + " = ?", new String[]{String.valueOf(p.contactId)});
            }
            // 4) Photo: the configured one, else the address book's.
            p.photo = photo(c, p);
            if (p.photo == null && p.contactId >= 0) {
                Uri cu = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, p.contactId);
                try (InputStream in = ContactsContract.Contacts.openContactPhotoInputStream(cr, cu, true)) {
                    if (in != null) p.photo = BitmapFactory.decodeStream(in);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "contacts permission missing");
        } catch (Exception e) {
            Log.e(TAG, "resolve " + p.name, e);
        }
        Log.i(TAG, "button \"" + p.name + "\" contact=" + p.contactId + " call=" + p.callRow
                + " chat=" + p.chatRow + " photo=" + (p.photo != null) + " -> " + p.state());
    }

    /** The button photo without using the address book: downloaded from the server, pushed to the app folder
     *  (Android/data/&lt;package&gt;/files/photos/) or bundled in assets/photos. null if none. */
    static Bitmap photo(Context c, Contact p) {
        if (p.photoFile != null) {
            File f = new File(p.photoFile);
            if (f.isFile()) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) return b;
            }
        }
        if (p.photoName == null) return null;
        File ext = ConfigProvider.photo(c, p.photoName);
        if (ext.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(ext.getPath());
            if (b != null) return b;
        }
        try (InputStream in = c.getAssets().open("photos/" + p.photoName)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Log.w(TAG, "photo not found: " + p.photoName);
            return null;
        }
    }

    /** For the call screen: the whole photo at 1080 px at most; else the button photo. */
    static Bitmap callPhoto(Context c, Contact p) {
        Bitmap b = null;
        if (p.fullPhotoFile != null && new File(p.fullPhotoFile).isFile()) b = reduced(p.fullPhotoFile);
        if (b == null && p.photoName != null) {
            File ext = ConfigProvider.photo(c, p.photoName);
            if (ext.isFile()) b = reduced(ext.getPath());
        }
        return b != null ? b : photo(c, p);
    }

    private static Bitmap reduced(String file) {
        try {
            BitmapFactory.Options op = new BitmapFactory.Options();
            op.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file, op);
            if (op.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(op.outWidth, op.outHeight) / (sample * 2) >= 1080) sample *= 2;
            BitmapFactory.Options op2 = new BitmapFactory.Options();
            op2.inSampleSize = sample;
            return BitmapFactory.decodeFile(file, op2);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- creating contacts

    /** If the button's number is not in the address book, adds it under the button's name (without it WhatsApp has no call row
     *  for that number). WhatsApp takes a while to sync it: meanwhile the button opens the chat. Needs WRITE_CONTACTS. */
    static void ensureContact(Context c, Contact p) {
        if (p.phone == null) return;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = c.getContentResolver();
        try {
            Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(p.phone));
            try (Cursor k = cr.query(u, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
                if (k != null && k.moveToFirst()) return;          // already there
            }
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, p.name)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p.phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            Log.w(TAG, "contact added to the address book: \"" + p.name + "\" (WhatsApp needs a while to offer a direct call)");
        } catch (Exception e) {
            Log.e(TAG, "could not add the contact " + p.name, e);
        }
    }

    private static long byName(ContentResolver cr, String where, String arg) {
        try (Cursor k = cr.query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID}, where, new String[]{arg},
                ContactsContract.Contacts.DISPLAY_NAME + " ASC")) {
            if (k != null && k.moveToFirst()) return k.getLong(0);
        }
        return -1;
    }

    private static void whatsappRows(ContentResolver cr, Contact p, String where, String[] args) {
        String w = "(" + where + ") AND " + ContactsContract.Data.MIMETYPE + " IN (?, ?)";
        String[] a = new String[args.length + 2];
        System.arraycopy(args, 0, a, 0, args.length);
        a[args.length] = MIME_CALL;
        a[args.length + 1] = MIME_CHAT;
        try (Cursor k = cr.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID,
                        ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1},
                w, a, null)) {
            while (k != null && k.moveToNext()) {
                String mime = k.getString(2);
                if (MIME_CALL.equals(mime) && p.callRow < 0) p.callRow = k.getLong(0);
                if (MIME_CHAT.equals(mime) && p.chatRow < 0) p.chatRow = k.getLong(0);
                if (p.contactId < 0) p.contactId = k.getLong(1);
                if (p.jid == null) p.jid = k.getString(3);
            }
        }
    }

    /** For diagnostics: every WhatsApp call row in the address book. */
    static String dump(Context c) {
        StringBuilder sb = new StringBuilder();
        try (Cursor k = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID,
                        ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1},
                ContactsContract.Data.MIMETYPE + " = ?", new String[]{MIME_CALL},
                ContactsContract.Data.DISPLAY_NAME + " ASC")) {
            while (k != null && k.moveToNext()) {
                sb.append(k.getString(2)).append("  [").append(k.getString(3)).append("] contact=")
                        .append(k.getLong(1)).append(" row=").append(k.getLong(0)).append('\n');
            }
        } catch (Exception e) {
            sb.append("error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- intents

    static Intent callIntent(Contact p) {
        if (p.callRow >= 0) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, p.callRow), MIME_CALL);
            i.setPackage(WHATSAPP);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        return chatIntent(p);
    }

    static Intent chatIntent(Contact p) {
        if (p.chatRow >= 0) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, p.chatRow), MIME_CHAT);
            i.setPackage(WHATSAPP);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        String d = digits(p.phone);
        if (d.isEmpty() && p.jid != null && p.jid.endsWith("@s.whatsapp.net")) d = digits(p.jid);
        if (!d.isEmpty()) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + d));
            i.setPackage(WHATSAPP);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        return null;
    }
}
