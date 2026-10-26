package tv.pad;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * One app, two modes (picked automatically):
 *  - Phone: runs the relay and shows the joystick.
 *  - TV (Android / Google TV): finds the phone's hotspot address by itself and shows the game inside the app.
 */
public class MainActivity extends Activity {
    private Relay relay;
    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tvMode;
    private int attempt;
    private boolean destroyed;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        tvMode = um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        setContentView(web);

        if (tvMode) startTv(); else startPhone();
    }

    // ------------------------------------------------------------ phone

    private void startPhone() {
        relay = new Relay(new Relay.Assets() {
            public byte[] get(String name) { return readAsset(name); }
        });
        int port;
        try {
            port = relay.start();
        } catch (Exception e) {
            finish();
            return;
        }
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("http://127.0.0.1:" + port + "/c");
    }

    // ------------------------------------------------------------ TV

    private void startTv() {
        web.setBackgroundColor(0xFF0B0F1A);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.startsWith("http://") && !url.startsWith("http://localhost")) loaded = true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    loaded = false;
                    waiting("Looking for your phone...");
                    handler.postDelayed(new Runnable() { public void run() { connectTv(); } }, 2500);
                }
            }
        });
        waiting("Looking for your phone...");
        connectTv();
    }

    private void connectTv() {
        if (destroyed) return;
        String ip = phoneIp();
        int port = 8080 + (attempt++ % 10);
        web.loadUrl("http://" + ip + ":" + port + "/");
    }

    /** On a phone hotspot, the phone is the Wi-Fi gateway of the TV. */
    private String phoneIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            int g = d.gateway;
            if (g != 0) {
                return (g & 255) + "." + ((g >> 8) & 255) + "." + ((g >> 16) & 255) + "." + ((g >> 24) & 255);
            }
        } catch (Exception ignored) { }
        return "192.168.43.1";
    }

    private void waiting(String msg) {
        String html = "<html><body style='margin:0;background:#0b0f1a;color:#fff;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
                + "<div><div style='font-size:5vh;font-weight:bold'>TV Pad</div>"
                + "<div style='font-size:3.2vh;margin-top:3vh;color:#ffd54f'>" + msg + "</div>"
                + "<div style='font-size:2.6vh;margin-top:3vh;opacity:.8'>1. Turn on the Hotspot on your phone<br>"
                + "2. Connect this TV to that hotspot's Wi-Fi<br>"
                + "3. Open the TV Pad app on your phone</div></div></body></html>";
        web.loadDataWithBaseURL("http://localhost/", html, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------ common

    private byte[] readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        if (relay != null) relay.stop();
        super.onDestroy();
    }
}
