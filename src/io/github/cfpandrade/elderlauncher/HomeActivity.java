package io.github.cfpandrade.elderlauncher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home screen: big WhatsApp call buttons and YouTube. Tap twice to use, so a stray touch does nothing. */
public class HomeActivity extends Activity {
    private static final long DOUBLE_TAP_WINDOW = 2500;     // ms between the first and second tap
    private static final long MAX_TAP = 700;                // longer = long press, does nothing
    private static final long SYNC_EVERY = 10 * 60 * 1000;  // the server is checked every 10 min at most

    // Palette: night-blue background, dark blue cards, WhatsApp green for "call"
    private static final int BG_TOP = 0xFF10244A;
    private static final int BG_BOTTOM = 0xFF050A14;
    private static final int CARD = 0xFF1B2E52;
    private static final int CARD_BORDER = 0xFF3A5587;
    private static final int CARD_APP = 0xFF2A2F3A;
    private static final int CARD_NAV = 0xFF24467A;
    private static final int YOUTUBE_RED = 0xFFC4170C;
    private static final int GREEN = 0xFF25D366;
    private static final int DARK_GREEN = 0xFF128C5A;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFE53935;

    private static final Typeface BOLD = loadFont();

    static Typeface font() {
        return BOLD;
    }

    /** The system's Roboto Bold, loaded from its file: MIUI's default font has no bold and fakes it with a hard-to-read outline. */
    private static Typeface loadFont() {
        for (String f : new String[]{"/system/fonts/Roboto-Bold.ttf", "/system/fonts/Roboto-Medium.ttf"}) {
            try {
                if (new java.io.File(f).isFile()) return Typeface.createFromFile(f);
            } catch (Exception ignored) {
            }
        }
        return Typeface.DEFAULT;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Tile> tiles = new ArrayList<>();
    private Tile selected;
    private long selectedAt;
    private TextView hint;
    private LinearLayout header;
    private TextView battery;
    private LinearLayout grid;
    private String loadedJson;
    private long loadedAt;
    private ValueAnimator pulse;
    /** Last server check (0 = not yet since start). */
    private static long syncedAt;
    private static volatile boolean syncing;

    private List<Config.Contact> contacts = new ArrayList<>();
    private boolean withYoutube = true;
    private int page = 1;

    private final Runnable clearHint = this::deselect;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            paintBattery(i);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 1);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("diag", false)) {
            Log.i(Config.TAG, "dump:\n" + Config.dump(this));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fullScreen();
        deselect();
        String j = Config.json(this);
        boolean stale = SystemClock.elapsedRealtime() - loadedAt > 15 * 60 * 1000;
        load(!j.equals(loadedJson) || stale);
        if (page != 1) showPage(1, false);
        sync();
        ringerToMaximum();
        MediaVolume.watch(this);
        ScreenControl.check(this, 0);
        Intent state = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (state != null) paintBattery(state);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (page != 1) showPage(1, false);
        deselect();
        if (i.getBooleanExtra("diag", false)) Log.i(Config.TAG, "dump:\n" + Config.dump(this));
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) fullScreen();
    }

    @Override
    public void onBackPressed() {
        if (page != 1) showPage(1, true);   // back does not leave the launcher
        else deselect();
    }

    /** On returning home (every 10 min at most, plus once at start) checks whether the buttons changed on the server. */
    private void sync() {
        long now = SystemClock.elapsedRealtime();
        if (syncing || (syncedAt != 0 && now - syncedAt < SYNC_EVERY)) return;
        if (!RemoteConfig.configured(this)) return;
        syncing = true;
        syncedAt = now;
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean changed = false;
            try {
                changed = RemoteConfig.sync(app);
                WirelessDebugging.check(app, 0);
            } finally {
                syncing = false;
            }
            if (changed) h.post(() -> {
                if (!isFinishing() && !isDestroyed()) load(true);
            });
        }, "remote").start();
    }

    private void fullScreen() {
        getWindow().setDecorFitsSystemWindows(false);     // the background reaches the top: no black status bar strip
        android.view.WindowManager.LayoutParams wl = getWindow().getAttributes();
        wl.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wl);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ view

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM}));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int m = (int) dp(8);
        column.setPadding(m, (int) dp(30), m, m);       // below the notch
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // Header: time and date on the left, battery on the right. It opens no settings on purpose, so nothing can be changed
        // by accident from the phone. With a button selected, the "Tap again..." hint shows here instead.
        FrameLayout top = new FrameLayout(this);
        column.addView(top, new LinearLayout.LayoutParams(-1, (int) dp(104)));

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(m * 2, 0, m * 2, 0);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50);
        time.setTypeface(BOLD);
        time.setIncludeFontPadding(false);
        timeRow.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        TextClock date = new TextClock(this);
        String datePattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd");
        date.setFormat24Hour(datePattern);
        date.setFormat12Hour(datePattern);
        date.setTextColor(0xFFC9D4E8);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setSingleLine(true);
        date.setAllCaps(false);

        battery = new TextView(this);
        battery.setTextColor(Color.WHITE);
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        battery.setTypeface(BOLD);
        battery.setGravity(Gravity.CENTER);
        battery.setPadding((int) dp(14), (int) dp(8), (int) dp(14), (int) dp(8));
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(dp(30));
        fb.setColor(DARK_GREEN);
        battery.setBackground(fb);
        battery.setText("…");
        timeRow.addView(battery, new LinearLayout.LayoutParams(-2, -2));
        header.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));
        header.addView(date, new LinearLayout.LayoutParams(-1, -2));
        top.addView(header, new FrameLayout.LayoutParams(-1, -1));

        hint = new TextView(this);
        hint.setTextColor(Color.BLACK);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        hint.setTypeface(BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(2);
        hint.setPadding(m * 2, m, m * 2, m);
        hint.setCompoundDrawablePadding(m);
        GradientDrawable fa = new GradientDrawable();
        fa.setColor(GREEN);
        fa.setCornerRadius(dp(22));
        hint.setBackground(fa);
        hint.setVisibility(View.GONE);
        hint.setClickable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(m, m, m, m);
        top.addView(hint, lp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        column.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    /** Every return home: ringer to maximum, not silenced. With "do not disturb" on, Android may refuse: tried anyway. */
    private void ringerToMaximum() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            for (int st : new int[]{AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_VOICE_CALL}) {
                int max = am.getStreamMaxVolume(st);
                if (am.getStreamVolume(st) < max) am.setStreamVolume(st, max, 0);
            }
        } catch (Exception e) {
            Log.w(Config.TAG, "could not raise the ringer: " + e.getMessage());
        }
    }

    private void paintBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) return;
        int pct = level * 100 / scale;
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        battery.setText((charging ? "⚡ " : "🔋 ") + pct + " %");
        GradientDrawable fb = (GradientDrawable) battery.getBackground();
        fb.setColor(charging ? DARK_GREEN : pct <= 15 ? RED : pct <= 35 ? AMBER : DARK_GREEN);
        battery.setTextColor(!charging && pct > 15 && pct <= 35 ? Color.BLACK : Color.WHITE);
    }

    private void load(boolean reread) {
        if (!reread && !tiles.isEmpty()) return;
        loadedJson = Config.json(this);
        loadedAt = SystemClock.elapsedRealtime();
        final List<Config.Contact> list = Config.contacts(this);
        contacts = list;
        withYoutube = Config.youtube(this);
        showPage(1, false);

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        new Thread(() -> {
            for (Config.Contact p : list) {
                Config.ensureContact(this, p);
                Config.resolve(this, p);
            }
            h.post(() -> {
                for (Tile t : tiles) t.refresh();
            });
        }, "contacts").start();
    }

    private void showPage(int n, boolean animate) {
        deselect();
        page = n;
        grid.removeAllViews();
        tiles.clear();
        List<Tile> list = new ArrayList<>();
        Tile bottomLeft = null, bottomRight = null;
        if (n == 1) {
            for (Config.Contact p : contacts) list.add(new Tile(p));
            if (withYoutube) {
                Intent yt = launcher(Config.YOUTUBE);
                if (yt != null) {
                    yt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    bottomLeft = new Tile(getString(R.string.youtube), getDrawable(R.drawable.ic_youtube), YOUTUBE_RED,
                            getString(R.string.tap_again_youtube), null, true);
                    bottomLeft.intent = yt;
                }
            }
            bottomRight = navigation(getString(R.string.more), R.drawable.ic_more, getString(R.string.tap_again_more), () -> showPage(2, true));
        } else {
            List<String> more = Config.more(this);
            if (more == null) {
                add(list, app(getString(R.string.camera), camera(), CARD_APP));
                add(list, app(getString(R.string.contacts), contactList(), CARD_APP));
                add(list, app(getString(R.string.phone), new Intent(Intent.ACTION_DIAL), CARD_APP));
            } else {
                // the configured ones, in order; missing or disabled apps do not show up
                for (String pkg : more) {
                    if (Config.WIRELESS_DEBUGGING.equals(pkg)) add(list, app(getString(R.string.debugging), Config.wirelessDebuggingIntent(), CARD_APP));
                    else add(list, app(null, launcher(pkg), CARD_APP));
                }
            }
            bottomLeft = navigation(getString(R.string.back), R.drawable.ic_back, getString(R.string.tap_again_back), () -> showPage(1, true));
            bottomLeft.singleTap = true;      // going back is harmless: one tap
        }
        int m = (int) dp(5);
        int rows = Math.max((list.size() + 1) / 2, n == 1 ? 5 : 3);
        LinearLayout row = null;
        for (int i = 0; i < rows * 2; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            p.setMargins(m, m, m, m);
            if (i < list.size()) {
                row.addView(list.get(i).view, p);
                tiles.add(list.get(i));
            } else {
                row.addView(new View(this), p);
            }
        }
        LinearLayout last = new LinearLayout(this);
        last.setOrientation(LinearLayout.HORIZONTAL);
        grid.addView(last, new LinearLayout.LayoutParams(-1, 0, 0.62f));
        // the right-hand slot is narrower; compared by position so that an empty left slot keeps its width
        Tile[] bottom = {bottomLeft, bottomRight};
        for (int i = 0; i < bottom.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, i == 1 ? 0.75f : 1.25f);
            p.setMargins(m, m, m, m);
            if (bottom[i] == null) {
                last.addView(new View(this), p);
            } else {
                last.addView(bottom[i].view, p);
                tiles.add(bottom[i]);
            }
        }
        if (animate) {
            grid.setAlpha(0f);
            grid.setTranslationY(dp(24));
            grid.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private static void add(List<Tile> l, Tile t) {
        if (t != null) l.add(t);
    }

    private Intent launcher(String pkg) {
        return getPackageManager().getLaunchIntentForPackage(pkg);
    }

    private Intent camera() {
        Intent i = launcher("com.android.camera");
        return i != null ? i : new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    }

    private Intent contactList() {
        Intent i = launcher("com.android.contacts");
        if (i != null && getPackageManager().resolveActivity(i, 0) != null) {
            // On MIUI the launcher entry of com.android.contacts opens the dialer: go to the contact list instead.
            Intent v = new Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI);
            v.setPackage("com.android.contacts");
            if (getPackageManager().resolveActivity(v, 0) != null) return v;
            return i;
        }
        return new Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI);
    }

    /** An app's tile; null if the app is missing or disabled. */
    private Tile app(String name, Intent i, int color) {
        if (i == null) return null;
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
        if (ri == null) return null;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (name == null) name = String.valueOf(ri.loadLabel(getPackageManager()));
        Tile t = new Tile(name, ri.loadIcon(getPackageManager()), color, getString(R.string.tap_again_open, name), null, false);
        t.intent = i;
        return t;
    }

    private Tile navigation(String name, int icon, String phrase, Runnable r) {
        return new Tile(name, getDrawable(icon), CARD_NAV, phrase, r, true);
    }

    /** A big button. Contact: the photo fills the card with the name on a dark band below. App: icon above, name below. */
    private final class Tile {
        final Config.Contact contact;
        final String phrase;
        final Runnable action;
        final int color;
        Intent intent;
        final ViewGroup view;
        final ImageView photo;
        final TextView name;
        final GradientDrawable background = new GradientDrawable();
        final GradientDrawable border = new GradientDrawable();
        final GradientDrawable band = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0x00000000});
        ImageView badge;
        boolean singleTap;

        /** A contact's button. */
        Tile(Config.Contact p) {
            this(p, p.name, null, CARD, getString(R.string.tap_again_call, p.name), null, false);
        }

        /** An app or navigation button. */
        Tile(String txt, Drawable icon, int color, String phrase, Runnable action, boolean horizontal) {
            this(null, txt, icon, color, phrase, action, horizontal);
        }

        private Tile(Config.Contact p, String txt, Drawable icon, int color, String phrase, Runnable action, boolean horizontal) {
            this.contact = p;
            this.phrase = phrase;
            this.action = action;
            this.color = color;
            background.setCornerRadius(dp(24));
            border.setCornerRadius(dp(24));
            border.setColor(Color.TRANSPARENT);

            name = new TextView(HomeActivity.this);
            name.setTypeface(BOLD);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setText(txt);
            name.setTextColor(Color.WHITE);

            if (p != null) {
                FrameLayout f = new FrameLayout(HomeActivity.this);
                view = f;
                f.setBackground(background);              // rounded corners clip the photo
                f.setClipToOutline(true);
                f.setForeground(border);                  // the frame goes above the photo
                photo = new ImageView(HomeActivity.this);
                photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
                f.addView(photo, new FrameLayout.LayoutParams(-1, -1));
                View shade = new View(HomeActivity.this);
                shade.setBackground(band);
                f.addView(shade, new FrameLayout.LayoutParams(-1, (int) dp(64), Gravity.BOTTOM));
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                name.setPadding((int) dp(6), 0, (int) dp(6), (int) dp(5));
                f.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
                badge = new ImageView(HomeActivity.this);
                badge.setImageResource(R.drawable.ic_call);
                int pc = (int) dp(6);
                badge.setPadding(pc, pc, pc, pc);
                GradientDrawable fc = new GradientDrawable();
                fc.setShape(GradientDrawable.OVAL);
                fc.setColor(GREEN);
                fc.setStroke((int) dp(2), Color.WHITE);
                badge.setBackground(fc);
                int tc = (int) dp(32);
                FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(tc, tc, Gravity.TOP | Gravity.END);
                lc.setMargins(0, (int) dp(8), (int) dp(8), 0);
                f.addView(badge, lc);
                refresh();
            } else {
                LinearLayout l = new LinearLayout(HomeActivity.this);
                view = l;
                l.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                l.setGravity(Gravity.CENTER);
                int pd = (int) dp(4);
                l.setPadding(pd, pd, pd, pd);
                l.setBackground(background);
                l.setClipToOutline(true);
                photo = new ImageView(HomeActivity.this);
                photo.setImageDrawable(icon);
                photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (!horizontal) {
                    name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
                    int mm = (int) dp(8);
                    lp.setMargins(mm, mm, mm, mm);
                    l.addView(photo, lp);
                    l.addView(name, new LinearLayout.LayoutParams(-1, -2));
                } else {
                    name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                    int ic = (int) dp(34);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ic, ic);
                    lp.setMargins(0, 0, (int) dp(10), 0);
                    l.addView(photo, lp);
                    l.addView(name, new LinearLayout.LayoutParams(-2, -2));
                }
            }
            view.setElevation(dp(4));
            paint(false);
            touch(view, this);
        }

        void refresh() {
            if (contact == null) return;
            Bitmap b = contact.photo;
            if (b != null) {
                photo.setImageBitmap(b);
                photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                photo.setImageDrawable(new Initial(contact.name, dp(72)));
                photo.setScaleType(ImageView.ScaleType.FIT_XY);
            }
        }

        void paint(boolean s) {
            if (contact != null) {
                background.setColor(CARD);
                border.setStroke((int) dp(s ? 7 : 2), s ? GREEN : CARD_BORDER);
                band.setColors(s ? new int[]{0xF2128C5A, 0x00128C5A} : new int[]{0xD9000000, 0x00000000});
                ((GradientDrawable) badge.getBackground()).setColor(s ? Color.WHITE : GREEN);
                badge.setColorFilter(s ? DARK_GREEN : Color.WHITE);
            } else {
                background.setColor(s ? GREEN : color);
                background.setStroke((int) dp(s ? 5 : 2), s ? Color.WHITE : 0x33FFFFFF);
                name.setTextColor(s ? Color.BLACK : Color.WHITE);
                if (action != null || intent != null) photo.setColorFilter(s ? Color.BLACK : Color.WHITE);
            }
            view.setElevation(dp(s ? 12 : 4));
        }
    }

    // ------------------------------------------------------------------ touches

    /** Only a short touch without dragging counts; long presses and drags do nothing. */
    private void touch(View v, Tile t) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop() * 2;
        final float[] start = new float[2];
        final long[] t0 = new long[1];
        final boolean[] cancelled = new boolean[1];
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    t0[0] = e.getEventTime();
                    cancelled[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - start[0]) > slop || Math.abs(e.getRawY() - start[1]) > slop)
                        cancelled[0] = true;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancelled[0] = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!cancelled[0] && e.getEventTime() - t0[0] <= MAX_TAP) tapped(t);
                    return true;
            }
            return true;
        });
    }

    private void tapped(Tile t) {
        long now = SystemClock.elapsedRealtime();
        if (t.singleTap) {
            t.view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            deselect();
            launch(t);
            return;
        }
        if (selected == t && now - selectedAt <= DOUBLE_TAP_WINDOW) {
            t.view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            deselect();
            launch(t);
            return;
        }
        t.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (selected != null) {
            selected.paint(false);
            selected.view.animate().cancel();
            selected.view.setScaleX(1f);
            selected.view.setScaleY(1f);
        }
        if (pulse != null) pulse.cancel();
        selected = t;
        selectedAt = now;
        t.paint(true);
        // the card pulses while waiting for the second tap
        final View v = t.view;
        pulse = ValueAnimator.ofFloat(1f, 1.05f);
        pulse.setDuration(420);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            v.setScaleX(s);
            v.setScaleY(s);
        });
        pulse.start();
        Drawable ic = t.contact != null ? getDrawable(R.drawable.ic_call) : null;
        if (ic != null) {
            int d = (int) dp(34);
            ic.setBounds(0, 0, d, d);
            ic.setTint(Color.BLACK);
        }
        hint.setCompoundDrawables(ic, null, null, null);
        hint.setText(t.phrase);
        hint.setAlpha(0f);
        hint.setVisibility(View.VISIBLE);
        hint.animate().alpha(1f).setDuration(140).start();
        header.setVisibility(View.INVISIBLE);
        h.removeCallbacks(clearHint);
        h.postDelayed(clearHint, DOUBLE_TAP_WINDOW + 400);
    }

    private void deselect() {
        h.removeCallbacks(clearHint);
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (selected != null) {
            selected.paint(false);
            selected.view.setScaleX(1f);
            selected.view.setScaleY(1f);
        }
        selected = null;
        if (hint != null) hint.setVisibility(View.GONE);
        if (header != null) header.setVisibility(View.VISIBLE);
    }

    private void launch(Tile t) {
        if (t.action != null) {
            t.action.run();
            return;
        }
        Intent i = t.contact != null ? Config.callIntent(t.contact) : t.intent;
        if (i == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            return;
        }
        String pkg = i.getPackage() != null ? i.getPackage() : (i.getComponent() != null ? i.getComponent().getPackageName() : "");
        try {
            Log.i(Config.TAG, "launching " + i);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(Config.TAG, "could not launch", e);
            Intent c = t.contact != null ? Config.chatIntent(t.contact) : null;
            if (c != null && c != i) {
                try {
                    startActivity(c);
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_LONG).show();
        }
    }

    /** A colored square with the initial, when the contact has no photo. */
    private static final class Initial extends Drawable {
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String txt;

        Initial(String name, float size) {
            txt = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            background.setColor(0xFF3D6FB6);
            letter.setColor(Color.WHITE);
            letter.setTextSize(size);
            letter.setTypeface(BOLD);
            letter.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect r = getBounds();
            c.drawRect(r, background);
            float y = r.exactCenterY() - (letter.descent() + letter.ascent()) / 2;
            c.drawText(txt, r.exactCenterX(), y, letter);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
