package tv.pad;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Phone as a Bluetooth gamepad / keyboard / TV remote. The TV (or any device) sees a normal controller,
 * so it works with any game that supports gamepads or keyboard keys.
 */
@TargetApi(28)
public class MainActivity extends Activity implements GamepadView.Host, SensorEventListener {

    private static final int[] DESC = {
            // ---- Keyboard, report id 1 (8 bytes)
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // ---- Gamepad, report id 2 (7 bytes: 16 buttons, X, Y, Z, Rz, hat)
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x02,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
            0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
            0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
            0xC0
    };

    private GamepadView view;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private SensorManager sm;
    private float tiltVal;
    private byte[] lastSent = new byte[0];
    private boolean lastWasKeys;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GamepadView(this, this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        if (Build.VERSION.SDK_INT < 28) {
            view.status = "Needs Android 9 or newer";
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !hasBtPerm()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
        } else {
            initBt();
        }
    }

    private boolean hasBtPerm() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (hasBtPerm()) initBt();
        else { view.status = "Allow Bluetooth permission, then reopen"; view.invalidate(); }
    }

    // ---------------------------------------------------------------- Bluetooth

    private void initBt() {
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { view.status = "No Bluetooth on this phone"; view.invalidate(); return; }
        if (!adapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                byte[] d = new byte[DESC.length];
                for (int i = 0; i < d.length; i++) d[i] = (byte) DESC[i];
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                        "TV Pad", "Phone gamepad", "TvPad", BluetoothHidDevice.SUBCLASS1_COMBO, d);
                hid.registerApp(sdp, null, null, getMainExecutor(), callback);
            }

            public void onServiceDisconnected(int profile) { hid = null; }
        }, BluetoothProfile.HID_DEVICE);
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            status(registered ? "Ready. Tap PAIR NEW (first time) or CONNECT" : "Bluetooth controller not available");
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                status("Connected: " + safeName(device));
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (host != null && host.equals(device)) host = null;
                status("Disconnected. Tap CONNECT");
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Connecting...");
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid != null) hid.replyReport(device, type, id, new byte[id == 1 ? 8 : 7]);
        }
    };

    private String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? d.getAddress() : n; } catch (Exception e) { return "TV"; }
    }

    private void status(String s) {
        view.status = s;
        view.invalidate();
    }

    @Override
    public void onAction(int id) {
        switch (id) {
            case GamepadView.ACT_KEYS:
                neutral();
                view.keys = !view.keys;
                break;
            case GamepadView.ACT_TILT:
                view.tilt = !view.tilt;
                tiltVal = 0;
                break;
            case GamepadView.ACT_DPAD:
                view.dpad = !view.dpad;
                break;
            case GamepadView.ACT_CONNECT:
                chooseDevice();
                break;
            case GamepadView.ACT_PAIR:
                pairNew();
                break;
        }
        view.invalidate();
        send();
    }

    private void pairNew() {
        if (hid == null) { Toast.makeText(this, "Bluetooth not ready", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(i);
        Toast.makeText(this, "On the TV: Settings > Remotes & Accessories > Add accessory, then pick this phone", Toast.LENGTH_LONG).show();
    }

    private void chooseDevice() {
        if (hid == null || adapter == null) { Toast.makeText(this, "Bluetooth not ready", Toast.LENGTH_SHORT).show(); return; }
        Set<BluetoothDevice> set = adapter.getBondedDevices();
        final List<BluetoothDevice> list = new ArrayList<BluetoothDevice>(set);
        if (list.isEmpty()) { Toast.makeText(this, "No paired devices yet. Tap PAIR NEW first", Toast.LENGTH_LONG).show(); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = safeName(list.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Connect to")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (hid != null) hid.connect(list.get(which));
                    }
                }).show();
    }

    // ---------------------------------------------------------------- input -> HID reports

    @Override
    public void onInput() {
        send();
    }

    private void neutral() {
        view.releaseAll();
        if (hid != null && host != null) {
            hid.sendReport(host, 1, new byte[8]);
            hid.sendReport(host, 2, new byte[7]);
        }
        lastSent = new byte[0];
    }

    private void send() {
        if (hid == null || host == null) return;
        float x = view.sx, y = view.sy;
        if (view.tilt && !view.stickHeld()) x = tiltVal;
        int id;
        byte[] r;
        if (view.keys) {
            id = 1;
            r = keyReport(x, y);
        } else {
            id = 2;
            r = padReport(x, y);
        }
        if (id == 1 == lastWasKeys && Arrays.equals(r, lastSent)) return;
        lastWasKeys = id == 1;
        lastSent = r;
        hid.sendReport(host, id, r);
    }

    private byte[] padReport(float x, float y) {
        int b = view.buttons;
        int hat = 0;
        float ax = x, ay = y;
        if (view.dpad) {
            boolean l = view.sx < -0.4f, rr = view.sx > 0.4f, u = view.sy < -0.4f, d = view.sy > 0.4f;
            if (u && rr) hat = 2; else if (d && rr) hat = 4; else if (d && l) hat = 6; else if (u && l) hat = 8;
            else if (u) hat = 1; else if (rr) hat = 3; else if (d) hat = 5; else if (l) hat = 7;
            ax = 0; ay = 0;
        }
        byte[] r = new byte[7];
        r[0] = (byte) b;
        r[1] = (byte) (b >> 8);
        r[2] = (byte) Math.round(ax * 127);
        r[3] = (byte) Math.round(ay * 127);
        r[6] = (byte) hat;
        return r;
    }

    private byte[] keyReport(float x, float y) {
        int b = view.buttons;
        byte[] r = new byte[8];
        int n = 2;
        int[] k = new int[10];
        int c = 0;
        if (x > 0.4f) k[c++] = 0x4F;
        if (x < -0.4f) k[c++] = 0x50;
        if (y > 0.4f) k[c++] = 0x51;
        if (y < -0.4f) k[c++] = 0x52;
        if ((b & GamepadView.A) != 0) k[c++] = 0x2C;      // Space
        if ((b & GamepadView.B) != 0) k[c++] = 0x1D;      // Z
        if ((b & GamepadView.X) != 0) k[c++] = 0x1B;      // X
        if ((b & GamepadView.Y) != 0) k[c++] = 0x06;      // C
        if ((b & GamepadView.L1) != 0) k[c++] = 0x14;     // Q
        if ((b & GamepadView.R1) != 0) k[c++] = 0x08;     // E
        if ((b & GamepadView.START) != 0) k[c++] = 0x28;  // Enter (OK on TV)
        if ((b & GamepadView.SEL) != 0) k[c++] = 0x29;    // Esc (Back on TV)
        int mod = 0;
        if ((b & GamepadView.L2) != 0) mod |= 2;          // Shift
        if ((b & GamepadView.R2) != 0) mod |= 1;          // Ctrl
        r[0] = (byte) mod;
        for (int i = 0; i < c && n < 8; i++) r[n++] = (byte) k[i];
        return r;
    }

    // ---------------------------------------------------------------- tilt steering

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!view.tilt) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        float t = (rot == Surface.ROTATION_270 ? -e.values[1] : e.values[1]) / 5f;
        t = Math.max(-1f, Math.min(1f, t));
        if (Math.abs(t) < 0.08f) t = 0;
        if (Math.abs(t - tiltVal) > 0.02f || (t == 0 && tiltVal != 0)) {
            tiltVal = t;
            view.invalidate();
            send();
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
        neutral();
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
        try {
            if (hid != null) {
                hid.unregisterApp();
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
