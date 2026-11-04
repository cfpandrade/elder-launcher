package io.github.cfpandrade.elderlauncher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** A full-screen message from the family: "Message from Alice", the text huge and read aloud, "OK" to close it and a button to
 *  call the sender. Opened by {@link CommandReceiver} with the MESSAGE action (text, who). It shows on a locked phone and closes
 *  itself after 10 minutes. */
public class MessageActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int GREEN = 0xFF25D366;
    private static final long CLOSE_MS = 10 * 60 * 1000;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView from, body, callButton;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String spoken = "";
    private Config.Contact contact;

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

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(0xFF1E2A3A);
        int m = (int) dp(18);
        column.setPadding(m, (int) dp(40), m, (int) dp(56));

        from = box("", 26, 0xFFB8C7D9, font);
        column.addView(from, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        body = box("", 38, Color.WHITE, font);
        body.setPadding(0, (int) dp(24), 0, (int) dp(24));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        column.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView ok = button(getString(R.string.ok), 0xFF2E7DD7, font, 40);
        ok.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            finish();
        });
        LinearLayout.LayoutParams lv = new LinearLayout.LayoutParams(-1, (int) dp(150));
        lv.setMargins(0, 0, 0, (int) dp(16));
        column.addView(ok, lv);

        callButton = button(getString(R.string.call), GREEN, font, 28);
        callButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            callNow();
        });
        column.addView(callButton, new LinearLayout.LayoutParams(-1, (int) dp(96)));

        setContentView(column);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        tts = new TextToSpeech(this, this);
        prepare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        prepare(i);
    }

    private void prepare(Intent i) {
        String t = i.getStringExtra("text") == null ? "" : i.getStringExtra("text");
        String q = i.getStringExtra("who") == null ? "" : i.getStringExtra("who");
        contact = LookingForYouActivity.contact(this, q);
        String name = contact != null ? contact.name : (q.isEmpty() ? getString(R.string.your_family) : q);
        from.setText(getString(R.string.message_from, name));
        body.setText(t);
        callButton.setVisibility(contact != null ? View.VISIBLE : View.GONE);
        if (contact != null) callButton.setText(getString(R.string.call_name, contact.name));
        spoken = getString(R.string.message_from, name) + ". " + t;
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::finish, CLOSE_MS);
        if (ttsReady) speak();
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            Log.w(Config.TAG, "message: no text-to-speech (" + status + ")");
            return;
        }
        tts.setLanguage(Locale.getDefault());
        // on the notification stream, which is always at maximum (CallWatcherService.ringerUp)
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setSpeechRate(0.9f);
        ttsReady = true;
        speak();
    }

    private void speak() {
        if (tts == null || spoken.isEmpty()) return;
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "message");
        // and again a while later, in case it was missed
        h.postDelayed(() -> {
            if (!isFinishing() && tts != null) tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "message2");
        }, 45000);
    }

    private void callNow() {
        final Config.Contact p = contact;
        if (p == null) return;
        if (tts != null) tts.stop();
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Runnable r = () -> new Thread(() -> {
            if (p.callRow < 0 && p.chatRow < 0) Config.resolve(this, p);
            runOnUiThread(() -> {
                Intent i = Config.callIntent(p);
                if (i == null) i = Config.chatIntent(p);
                try {
                    if (i != null) startActivity(i);
                } catch (Exception e) {
                    Log.e(Config.TAG, "message: could not call", e);
                }
                finish();
            });
        }).start();
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { r.run(); }
                @Override public void onDismissError() { r.run(); }
            });
        } else r.run();
    }

    private TextView box(String t, int sp, int color, Typeface font) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(font);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private TextView button(String t, int color, Typeface font, int sp) {
        TextView v = box(t, sp, Color.WHITE, font);
        GradientDrawable f = new GradientDrawable();
        f.setColor(color);
        f.setCornerRadius(dp(36));
        f.setStroke((int) dp(3), Color.WHITE);
        v.setBackground(f);
        v.setElevation(dp(10));
        return v;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
