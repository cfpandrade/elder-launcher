package io.github.cfpandrade.elderlauncher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Plays a video on demand (opened by {@link CommandReceiver}): turns the screen on, sets media volume and plays full screen.
 *  It plays here, with YouTube's web player, in landscape and above the lock screen (the YouTube app cannot show above a PIN
 *  lock). It closes itself at the end. If the video cannot be embedded, it opens in the YouTube app, which asks to unlock. */
public class VideoActivity extends Activity {
    private static WeakReference<VideoActivity> alive = new WeakReference<>(null);
    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private String id;
    private boolean launched;

    /** The built-in video screen open now, or null. */
    static VideoActivity alive() {
        VideoActivity v = alive.get();
        return v != null && !v.isFinishing() && v.web != null ? v : null;
    }

    static void control(String command) {
        VideoActivity v = alive();
        if (v == null) return;
        v.runOnUiThread(() -> {
            if ("stop".equals(command)) v.finish();
            else if (v.web != null) v.web.evaluateJavascript("pause && " + ("pause".equals(command) ? "pause()" : "resume()"), null);
        });
    }

    static void close() {
        VideoActivity v = alive.get();
        if (v != null) v.runOnUiThread(v::finish);
    }

    private float dp(float x) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, getResources().getDisplayMetrics());
    }

    /** YouTube identifies embedding apps by an HTTPS referrer built from the application id. */
    private String base() {
        return "https://" + getPackageName() + "/";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (!start(getIntent())) finish();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String before = id;
        if (start(i) && web != null && !id.equals(before)) web.loadDataWithBaseURL(base(), html(id), "text/html", "utf-8", null);
    }

    private boolean start(Intent i) {
        String fresh = i.getStringExtra("id");
        if (fresh == null || !CommandReceiver.VIDEO_ID.matcher(fresh).matches()) return false;
        id = fresh;
        if (web == null) CommandReceiver.key(this, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE);   // pause whatever plays elsewhere
        volume(i.getIntExtra("volume", MediaVolume.level(this)));
        if (web != null) return true;                                  // screen already open: only the video changes
        builtIn();
        return true;
    }

    private void volume(int pct) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.round(max * Math.max(0, Math.min(100, pct)) / 100f);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Log.i(Config.TAG, "media volume " + v + "/" + max);
        } catch (Exception e) {
            Log.w(Config.TAG, "could not set the volume", e);
        }
    }

    /** With the YouTube app (phone unlocked). */
    private void youtubeApp() {
        if (launched) return;
        launched = true;
        Intent y = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id));
        y.setPackage(Config.YOUTUBE);
        y.putExtra("force_fullscreen", true);
        y.putExtra("finish_on_ended", true);
        y.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(y);
            Log.i(Config.TAG, "YouTube opened with " + id);
        } catch (Exception e) {
            Log.e(Config.TAG, "could not open YouTube", e);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    // ---------------------------------------------------------------- built-in player, above the lock screen

    @SuppressLint("SetJavaScriptEnabled")
    private void builtIn() {
        alive = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Phone");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        TextView closeButton = new TextView(this);
        closeButton.setText("✕");
        closeButton.setTextColor(Color.WHITE);
        closeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        closeButton.setGravity(Gravity.CENTER);
        GradientDrawable f = new GradientDrawable();
        f.setShape(GradientDrawable.OVAL);
        f.setColor(0x88000000);
        closeButton.setBackground(f);
        closeButton.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams((int) dp(56), (int) dp(56), Gravity.TOP | Gravity.END);
        lc.setMargins(0, (int) dp(12), (int) dp(12), 0);
        root.addView(closeButton, lc);

        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        web.loadDataWithBaseURL(base(), html(id), "text/html", "utf-8", null);
        Log.i(Config.TAG, "video " + id + " on the built-in screen");
    }

    private String html(String id) {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='referrer' content='strict-origin-when-cross-origin'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style>"
                + "</head><body><div id='p'></div><script>"
                + "var p,pause=null,resume=null;"
                + "function onYouTubeIframeAPIReady(){p=new YT.Player('p',{videoId:'" + id + "',width:'100%',height:'100%',"
                + "playerVars:{autoplay:1,playsinline:1,rel:0,fs:0,controls:1,iv_load_policy:3,cc_load_policy:0,origin:'" + base().replaceAll("/$", "") + "'},"
                + "events:{onReady:function(e){try{e.target.unloadModule('captions');e.target.unloadModule('cc')}catch(x){}e.target.unMute();e.target.setVolume(100);e.target.playVideo();"
                + "pause=function(){p.pauseVideo()};resume=function(){p.playVideo()}},"
                + "onStateChange:function(e){if(e.data===0)Phone.ended()},"
                + "onError:function(e){Phone.error(String(e.data))}}})}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }

    private final class Bridge {
        @JavascriptInterface
        public void ended() {
            Log.i(Config.TAG, "video ended");
            runOnUiThread(VideoActivity.this::finish);
        }

        @JavascriptInterface
        public void error(String code) {
            Log.w(Config.TAG, "the player cannot play " + id + " (error " + code + "): opening the YouTube app");
            runOnUiThread(() -> {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km != null) km.requestDismissKeyguard(VideoActivity.this, null);
                h.postDelayed(VideoActivity.this::youtubeApp, 300);
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (alive.get() == this) alive = new WeakReference<>(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
    }
}
