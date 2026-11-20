package com.southportsmoothies.menuboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Shows the menu web page full screen, keeps the TV awake,
 * reconnects on its own, and refreshes every hour.
 * All settings live in res/values/settings.xml.
 */
public class MainActivity extends Activity {

    /** True while the board is on screen. The watchdog checks this. */
    static volatile boolean isInForeground = false;

    /** Give up on a load that hangs this long and show "Reconnecting…". */
    private static final long LOAD_TIMEOUT_MS = 90_000L;
    /** Two Back presses within this window close the app. */
    private static final long DOUBLE_BACK_MS = 2_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout webHolder;
    private View overlay;
    private TextView overlayText;
    private WebView webView;

    private String menuUrl;
    private String menuHost;
    private long refreshMs;
    private long retryMs;
    private int pageWidth;
    private boolean forcePageWidth;

    /** Set when the current page load failed, so we ignore its "finished" signal. */
    private boolean loadFailed = false;
    private long lastBackPress = 0;

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() { loadBoard(); }
    };

    /** Hourly refresh. If the internet is down, keep the current menu up and try again soon. */
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (isOnline()) {
                loadBoard();
            } else {
                handler.postDelayed(this, retryMs);
            }
        }
    };

    private final Runnable timeoutRunnable = new Runnable() {
        @Override public void run() { onLoadFailed(); }
    };

    // ------------------------------------------------------------------
    // Start-up
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on (no screensaver, no sleep) while the board is open.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        menuUrl = getString(R.string.menu_url).trim();
        menuHost = Uri.parse(menuUrl).getHost();
        refreshMs = Math.max(1, getResources().getInteger(R.integer.refresh_minutes)) * 60_000L;
        retryMs = Math.max(5, getResources().getInteger(R.integer.retry_seconds)) * 1_000L;
        pageWidth = Math.max(320, getResources().getInteger(R.integer.page_width));
        forcePageWidth = getResources().getBoolean(R.bool.force_page_width);

        webHolder = findViewById(R.id.web_holder);
        overlay = findViewById(R.id.overlay);
        overlayText = findViewById(R.id.overlay_text);

        createWebView();
        hideSystemUi();
        Watchdog.enable(this);

        showOverlay(getString(R.string.loading_text));
        loadBoard();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setMediaPlaybackRequiresUserGesture(false);   // menu videos can autoplay
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                              // ignore TV font-size settings
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);       // always fetch the newest menu
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        if (getResources().getBoolean(R.bool.desktop_mode)) {
            String ua = s.getUserAgentString();
            if (ua != null) s.setUserAgentString(ua.replace(" Mobile", ""));
        }

        webView.setWebViewClient(new BoardClient());
        webView.setWebChromeClient(new QuietChromeClient());

        webHolder.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------
    // Loading, reconnecting, refreshing
    // ------------------------------------------------------------------

    private void loadBoard() {
        if (webView == null || isFinishing()) return;
        handler.removeCallbacks(retryRunnable);
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(timeoutRunnable);
        loadFailed = false;
        handler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);
        webView.loadUrl(menuUrl);
    }

    /** Page is up: hide the waiting screen and book the next hourly refresh. */
    private void onLoadSucceeded() {
        if (loadFailed || isFinishing()) return;
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(retryRunnable);
        applyTvLayout();
        hideOverlay();
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, refreshMs);
    }

    /** Page failed: cover it with "Reconnecting…" and try again shortly. */
    private void onLoadFailed() {
        if (isFinishing()) return;
        loadFailed = true;
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(retryRunnable);
        showOverlay(getString(R.string.reconnecting_text));
        if (webView != null) webView.stopLoading();
        handler.postDelayed(retryRunnable, retryMs);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /**
     * Makes the page lay out as a 1920-pixel-wide screen that fills the TV exactly,
     * with no zoom and no scrollbars.
     */
    private void applyTvLayout() {
        if (webView == null) return;
        String hideScrollbars =
                "var st=document.getElementById('swc-tv-style');"
              + "if(!st){st=document.createElement('style');st.id='swc-tv-style';"
              + "st.textContent='::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}"
              + "html,body{overflow:hidden!important}';"
              + "(document.head||document.documentElement).appendChild(st);}";

        String setViewport = "";
        if (forcePageWidth) {
            float density = getResources().getDisplayMetrics().density;
            int viewPx = webView.getWidth() > 0
                    ? webView.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            double scale = (viewPx / density) / pageWidth;
            String sc = String.format(Locale.US, "%.4f", scale);
            String content = "width=" + pageWidth + ", initial-scale=" + sc
                    + ", minimum-scale=" + sc + ", maximum-scale=" + sc + ", user-scalable=no";
            setViewport =
                    "var m=document.querySelector('meta[name=viewport]');"
                  + "if(!m){m=document.createElement('meta');m.name='viewport';"
                  + "(document.head||document.documentElement).appendChild(m);}"
                  + "if(m.getAttribute('content')!=='" + content + "'){m.setAttribute('content','" + content + "');}";
        }

        webView.evaluateJavascript("(function(){try{" + setViewport + hideScrollbars + "}catch(e){}})();", null);
    }

    // ------------------------------------------------------------------
    // Waiting screen
    // ------------------------------------------------------------------

    private void showOverlay(String text) {
        overlayText.setText(text);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    // Remote control: ignore everything except Back (twice = exit) and volume
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) handleBack();
            return true;
        }
        if (key == KeyEvent.KEYCODE_VOLUME_UP
                || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        return true; // swallow every other button
    }

    private void handleBack() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress < DOUBLE_BACK_MS) {
            // Deliberate exit: stop the auto-reopen until the app is opened again
            // or the Fire TV restarts.
            Watchdog.disable(this);
            finishAndRemoveTask();
        } else {
            lastBackPress = now;
            Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Full screen + lifecycle
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideSystemUi(); // brought back to front by the watchdog: nothing to reload
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        Watchdog.schedule(this);
        if (webView != null) webView.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        isInForeground = false;
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyWebView();
        super.onDestroy();
    }

    private void destroyWebView() {
        if (webView != null) {
            webHolder.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    // ------------------------------------------------------------------
    // Web page callbacks
    // ------------------------------------------------------------------

    private class BoardClient extends WebViewClient {

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            // The new page has started drawing (Android 6+).
            onLoadSucceeded();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            onLoadSucceeded();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) onLoadFailed();
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            // Older Fire TVs (Android 5) report page errors here instead.
            if (Build.VERSION.SDK_INT < 23) onLoadFailed();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 400) onLoadFailed();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
            // Common right after a power cut, before the Fire TV clock is set. Never bypass; just retry.
            sslHandler.cancel();
            String host = error.getUrl() == null ? null : Uri.parse(error.getUrl()).getHost();
            if (host == null || host.equalsIgnoreCase(menuHost)) onLoadFailed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            return !("http".equals(scheme) || "https".equals(scheme));
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !(url.startsWith("http://") || url.startsWith("https://"));
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The web engine crashed (usually low memory). Rebuild it and reload.
            if (view == webView) {
                handler.removeCallbacksAndMessages(null);
                destroyWebView();
                createWebView();
                showOverlay(getString(R.string.loading_text));
                handler.postDelayed(retryRunnable, 2_000L);
            }
            return true;
        }
    }

    /** Hides pop-up alerts and the grey "play" icon on videos. */
    private static class QuietChromeClient extends WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
