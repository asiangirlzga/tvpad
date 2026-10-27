package tv.pad;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

/**
 * Wi-Fi game controller. The phone runs a tiny server; the games on the TV connect to it over the phone's hotspot
 * and receive key presses (D-pad, A/B/X/Y, shoulders, Start, Back, OK).
 */
public class MainActivity extends Activity implements GamepadView.Host, SensorEventListener {
    private GamepadView view;
    private PadServer server;
    private SensorManager sm;
    private float tiltVal;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GamepadView(this, this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        server = new PadServer(new PadServer.Listener() {
            public void onClients(final int n) {
                runOnUiThread(new Runnable() { public void run() { showStatus(n); } });
            }
        });
        server.start();
        showStatus(0);
    }

    private void showStatus(int n) {
        if (n < 0) view.status = server.error;
        else if (n == 0) view.status = "Waiting for TV. Hotspot ON, then open the game on the TV";
        else view.status = "TV connected" + (n > 1 ? " (" + n + ")" : "");
        view.invalidate();
    }

    @Override
    public void onKey(int code, boolean down) {
        server.key(code, down);
    }

    @Override
    public void onAction(int id) {
        if (id == GamepadView.ACT_TILT) {
            view.tilt = !view.tilt;
            tiltVal = 0;
            view.setTilt(0);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!view.tilt) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        float t = (rot == Surface.ROTATION_270 ? -e.values[1] : e.values[1]) / 5f;
        t = Math.max(-1f, Math.min(1f, t));
        if (Math.abs(t) < 0.08f) t = 0;
        if (Math.abs(t - tiltVal) > 0.02f) {
            tiltVal = t;
            view.setTilt(t);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        sm.unregisterListener(this);
        view.releaseAll();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onDestroy() {
        server.stop();
        super.onDestroy();
    }
}
