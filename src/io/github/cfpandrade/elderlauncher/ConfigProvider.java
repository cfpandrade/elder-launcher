package io.github.cfpandrade.elderlauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** How the installer hands files to the app: contacts.json, remote.json and photos/NAME.
 *
 *  Pushing to Android/data does not work on every phone, because Android 11 does not guarantee that ADB can write there.
 *  This provider requires a permission that the ADB shell holds and normal apps cannot get, and the files end up in private
 *  storage, so the server token is out of reach of other apps. It is write-only: nothing can read the token back through it.
 *
 *    adb shell content write --uri content://PACKAGE.config/contacts.json &lt; contacts.json
 *    adb shell content delete --uri content://PACKAGE.config/remote.json
 *    adb shell content query --uri content://PACKAGE.config/remote.json    (exists=1 or exists=0) */
public class ConfigProvider extends ContentProvider {

    static File folder(Context c) {
        File d = new File(c.getFilesDir(), "local");
        new File(d, "photos").mkdirs();
        return d;
    }

    static File contacts(Context c) {
        return new File(folder(c), "contacts.json");
    }

    static File remote(Context c) {
        return new File(folder(c), "remote.json");
    }

    static File photo(Context c, String name) {
        return new File(new File(folder(c), "photos"), name);
    }

    private File file(Uri u) throws FileNotFoundException {
        Context c = getContext();
        List<String> p = u.getPathSegments();
        if (p.size() == 1 && "contacts.json".equals(p.get(0))) return contacts(c);
        if (p.size() == 1 && "remote.json".equals(p.get(0))) return remote(c);
        if (p.size() == 2 && "photos".equals(p.get(0)) && p.get(1).matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"))
            return photo(c, p.get(1));
        throw new FileNotFoundException("not a configuration file: " + u);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (!mode.startsWith("w")) throw new FileNotFoundException("write-only");
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.parseMode("wt"));
    }

    @Override
    public int delete(Uri u, String selection, String[] args) {
        try {
            return file(u).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override
    public Cursor query(Uri u, String[] projection, String selection, String[] args, String order) {
        MatrixCursor m = new MatrixCursor(new String[]{"exists"});
        try {
            m.addRow(new Object[]{file(u).isFile() ? 1 : 0});
        } catch (FileNotFoundException e) {
            m.addRow(new Object[]{0});
        }
        return m;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri u) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int update(Uri u, ContentValues v, String selection, String[] args) {
        return 0;
    }
}
