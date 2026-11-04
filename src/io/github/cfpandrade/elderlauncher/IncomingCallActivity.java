package io.github.cfpandrade.elderlauncher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.List;

/** Incoming call screen: big photo, a huge "Answer" (one tap) and "Hang up" (two taps). */
public class IncomingCallActivity extends Activity {
    private static final int GREEN = 0xFF25D366;
    private static final int RED = 0xFFE53935;
    private static final long AUTO_ANSWER_MS = 20000;
    private static WeakReference<IncomingCallActivity> alive = new WeakReference<>(null);
    private final Handler h = new Handler(Looper.getMainLooper());
    private long hangUpTapped;
    private TextView hangUpText;
    private TextView label;

    static void close() {
        IncomingCallActivity l = alive.get();
        if (l != null) l.runOnUiThread(l::finish);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = new WeakReference<>(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        Typeface font = HomeActivity.font();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // the whole photo, full screen
        Bitmap bm = photoOf(CallWatcherService.caller);
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (bm != null) background.setImageBitmap(bm);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        View veil = new View(this);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000, 0x00000000, 0x00000000, 0xCC000000}));
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int m = (int) dp(16);
        column.setPadding(m, (int) dp(40), m, (int) dp(64));   // room for the system bar at the bottom
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        label = new TextView(this);
        label.setText(R.string.incoming_call);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        label.setTypeface(font);
        label.setGravity(Gravity.CENTER);
        column.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView name = new TextView(this);
        name.setText(displayName(CallWatcherService.caller));
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        name.setTypeface(font);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        column.addView(name, new LinearLayout.LayoutParams(-1, -2));

        column.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));   // gap: the face shows here

        TextView yes = button(getString(R.string.answer), GREEN, font, 46);
        yes.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            send(CallWatcherService.answer);
            voiceToMaximum();
            finish();
        });
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-1, (int) dp(200));
        ls.setMargins(0, 0, 0, (int) dp(18));
        column.addView(yes, ls);

        hangUpText = button(getString(R.string.hang_up), RED, font, 30);
        hangUpText.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - hangUpTapped <= 2500) {
                send(CallWatcherService.decline);
                finish();
                return;
            }
            hangUpTapped = now;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            hangUpText.setText(R.string.tap_again_hang_up);
            h.removeCallbacksAndMessages(null);
            h.postDelayed(() -> hangUpText.setText(R.string.hang_up), 2700);
        });
        column.addView(hangUpText, new LinearLayout.LayoutParams(-1, (int) dp(96)));

        setContentView(root);
        // system bars after setContentView: before it there is no window yet
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        if (CallWatcherService.answer == null) {
            Log.i(Config.TAG, "call screen opened with no call ringing: closing");
            finish();
            return;
        }
        Log.i(Config.TAG, "call screen opened for " + CallWatcherService.caller);
        // optional auto-answer: only calls from configured contacts, after 20 s, on speaker
        if (SettingsReceiver.autoAnswer(this) && contact(CallWatcherService.caller) != null) {
            h.postDelayed(() -> {
                if (isFinishing() || CallWatcherService.answer == null) return;
                label.setText(R.string.answering);
                send(CallWatcherService.answer);
                voiceToMaximum();
                speaker();
                finish();
            }, AUTO_ANSWER_MS);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (CallWatcherService.answer == null) finish();      // brought to the front when no longer ringing
        else Log.i(Config.TAG, "call screen, in front again");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (alive.get() == this) alive = new WeakReference<>(null);
    }

    @Override
    public void onBackPressed() {
        // back does not close the call screen: answer or hang up
    }

    private TextView button(String txt, int color, Typeface font, int sp) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font);
        t.setGravity(Gravity.CENTER);
        GradientDrawable f = new GradientDrawable();
        f.setColor(color);
        f.setCornerRadius(dp(36));
        f.setStroke((int) dp(3), Color.WHITE);
        t.setBackground(f);
        t.setElevation(dp(10));
        return t;
    }

    /** Once answered, call voice to maximum: a WhatsApp call changes the audio mode when it starts and each mode has its own
     *  volume, so this is repeated for a few seconds. */
    private void voiceToMaximum() {
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        Handler later = new Handler(Looper.getMainLooper());
        for (long d : new long[]{800, 2000, 4000, 7000}) {
            later.postDelayed(() -> {
                try {
                    int st = AudioManager.STREAM_VOICE_CALL;
                    am.setStreamVolume(st, am.getStreamMaxVolume(st), 0);
                } catch (Exception ignored) {
                }
            }, d);
        }
    }

    /** Speaker: a few seconds after answering, when the call already has the audio. */
    private void speaker() {
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        Handler later = new Handler(Looper.getMainLooper());
        for (long d : new long[]{1500, 3500, 6000}) {
            later.postDelayed(() -> {
                try {
                    am.setSpeakerphoneOn(true);
                } catch (Exception ignored) {
                }
            }, d);
        }
    }

    private void send(PendingIntent p) {
        if (p == null) return;
        try {
            p.send();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- who is calling

    private static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ").toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private Config.Contact contact(String who) {
        String q = plain(who);
        if (q.isEmpty()) return null;
        List<Config.Contact> all = Config.contacts(this);
        for (Config.Contact p : all) {
            String l = plain(p.lookup), n = plain(p.name);
            if (!l.isEmpty() && (q.contains(l) || l.contains(q))) return p;
            if (!n.isEmpty() && q.startsWith(n)) return p;
        }
        return null;
    }

    private String displayName(String who) {
        Config.Contact p = contact(who);
        return p != null ? p.name : (who == null || who.isEmpty() ? getString(R.string.someone) : who);
    }

    private Bitmap photoOf(String who) {
        Config.Contact p = contact(who);
        return p == null ? null : Config.callPhoto(this, p);
    }
}
