package io.github.cfpandrade.elderlauncher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.List;

/** "Trying to reach you": the photo of whoever is looking for the user and a huge green button that calls back over WhatsApp
 *  with ONE tap. It shows on a locked phone and closes itself after 5 minutes. If the person has no button on the home screen,
 *  it offers to call the first configured contact instead. */
public class LookingForYouActivity extends Activity {
    private static final int GREEN = 0xFF25D366;
    private static final long CLOSE_MS = 5 * 60 * 1000;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Config.Contact contact;
    private TextView label, name, callButton;
    private ImageView background;

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Typeface font = HomeActivity.font();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1E2A3A);
        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        View veil = new View(this);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x22000000, 0x00000000, 0x22000000, 0xDD000000}));
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int m = (int) dp(16);
        column.setPadding(m, (int) dp(40), m, (int) dp(56));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        label = text(getString(R.string.looking_for_you), 28, 0xFFF0F0F0, font);
        column.addView(label, new LinearLayout.LayoutParams(-1, -2));
        name = text("", 46, Color.WHITE, font);
        name.setMaxLines(2);
        column.addView(name, new LinearLayout.LayoutParams(-1, -2));

        column.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));   // gap: the face shows here

        callButton = text(getString(R.string.call), 38, Color.WHITE, font);
        callButton.setMaxLines(2);
        GradientDrawable f = new GradientDrawable();
        f.setColor(GREEN);
        f.setCornerRadius(dp(36));
        f.setStroke((int) dp(3), Color.WHITE);
        callButton.setBackground(f);
        callButton.setElevation(dp(10));
        callButton.setPadding(m, 0, m, 0);
        callButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            callNow();
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, (int) dp(210));
        ll.setMargins(0, 0, 0, (int) dp(20));
        column.addView(callButton, ll);

        TextView closeButton = text(getString(R.string.close), 22, 0xFFE0E0E0, font);
        GradientDrawable fc = new GradientDrawable();
        fc.setColor(0x66000000);
        fc.setCornerRadius(dp(28));
        fc.setStroke((int) dp(2), 0x99FFFFFF);
        closeButton.setBackground(fc);
        closeButton.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams((int) dp(200), (int) dp(60));
        lc.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(closeButton, lc);

        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        prepare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        prepare(i);
    }

    private TextView text(String t, int sp, int color, Typeface font) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(font);
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        return v;
    }

    /** Who is looking: their button (by name); if none, the first configured contact. The address book is read off the UI thread. */
    private void prepare(Intent i) {
        final String who = i.getStringExtra("who") == null ? "" : i.getStringExtra("who");
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::finish, CLOSE_MS);
        Config.Contact p = contact(this, who);
        boolean exact = p != null;
        if (p == null) {
            List<Config.Contact> all = Config.contacts(this);
            p = all.isEmpty() ? null : all.get(0);
        }
        contact = p;
        if (exact) {
            label.setText(R.string.looking_for_you);
            name.setText(p.name);
        } else {
            label.setText(R.string.someone_looking_for_you);
            name.setText(p != null ? getString(R.string.call_name, p.name) : getString(R.string.call_family));
        }
        callButton.setText(p != null ? getString(R.string.call_name, p.name) : getString(R.string.call));
        Log.i(Config.TAG, "looking-for-you screen for \"" + who + "\" -> " + (p != null ? p.name : "nobody"));
        if (p == null) return;
        final Config.Contact chosen = p;
        new Thread(() -> {
            Config.resolve(this, chosen);
            final Bitmap bm = Config.callPhoto(this, chosen);
            runOnUiThread(() -> {
                if (bm != null && contact == chosen) background.setImageBitmap(bm);
            });
        }).start();
    }

    private void callNow() {
        final Config.Contact p = contact;
        if (p == null) {
            finish();
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { launch(p); }
                @Override public void onDismissError() { launch(p); }
            });
        } else {
            launch(p);
        }
    }

    private void launch(Config.Contact p) {
        if (p.callRow < 0 && p.chatRow < 0) Config.resolve(this, p);
        Intent i = Config.callIntent(p);
        try {
            if (i == null) throw new IllegalStateException("no WhatsApp");
            Log.i(Config.TAG, "calling " + p.name + " from the looking-for-you screen");
            startActivity(i);
        } catch (Exception e) {
            Log.e(Config.TAG, "could not call", e);
            Intent c = Config.chatIntent(p);
            try {
                if (c != null) startActivity(c);
                else Toast.makeText(this, R.string.could_not_call, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.could_not_call, Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- who it is, by name

    private static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ").toLowerCase().trim().replaceAll("\\s+", " ");
    }

    /** That person's button by name (also used by the message screen), or null. */
    static Config.Contact contact(Context c, String who) {
        String q = plain(who);
        if (q.isEmpty()) return null;
        List<Config.Contact> all = Config.contacts(c);
        for (Config.Contact p : all) if (plain(p.name).equals(q)) return p;
        for (Config.Contact p : all) {
            String n = plain(p.name), l = plain(p.lookup);
            String first = n.contains(" ") ? n.substring(0, n.indexOf(' ')) : n;
            if (!n.isEmpty() && (q.startsWith(n) || (q.length() >= 3 && n.startsWith(q)) || q.equals(first))) return p;
            if (!l.isEmpty() && l.equals(q)) return p;
        }
        return null;
    }
}
