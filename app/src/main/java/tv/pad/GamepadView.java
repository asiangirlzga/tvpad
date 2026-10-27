package tv.pad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Touch gamepad. Every control produces Android key codes, which games on the TV already understand. */
public class GamepadView extends View {
    public interface Host {
        void onKey(int code, boolean down);
        void onAction(int id);
    }

    public static final int ACT_TILT = 1;

    private static class Btn {
        String label;
        boolean round, down;
        int key, act, color;
        final RectF r = new RectF();
        float cx, cy, rad;
    }

    private static final Object STICK = new Object();
    private final List<Btn> btns = new ArrayList<Btn>();
    private final SparseArray<Object> owner = new SparseArray<Object>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Host host;
    private float scx, scy, sr, u;
    private boolean stickHeld;
    private float sx, sy, tiltX;
    // up, down, left, right
    private final int[] dirKey = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
    private final boolean[] dirOn = new boolean[4];

    public boolean tilt;
    public String status = "Starting...";

    public GamepadView(Context c, Host host) {
        super(c);
        this.host = host;
        p.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        btns.clear();
        u = h / 100f;
        scx = w * 0.20f; scy = 66 * u; sr = 24 * u;
        int grey = 0xFF37474F;
        float fx = w * 0.80f, fy = 62 * u, d = 16 * u;
        round("A", fx, fy + d, 9 * u, KeyEvent.KEYCODE_BUTTON_A, 0xFF43A047);
        round("B", fx + d, fy, 9 * u, KeyEvent.KEYCODE_BUTTON_B, 0xFFE53935);
        round("X", fx - d, fy, 9 * u, KeyEvent.KEYCODE_BUTTON_X, 0xFF1E88E5);
        round("Y", fx, fy - d, 9 * u, KeyEvent.KEYCODE_BUTTON_Y, 0xFFFDD835);
        rect("L1", w * 0.10f, 9 * u, 11 * u, 5 * u, KeyEvent.KEYCODE_1, 0, grey);
        rect("L2 BRAKE", w * 0.10f, 21 * u, 11 * u, 5 * u, KeyEvent.KEYCODE_BUTTON_L2, 0, grey);
        rect("R1", w * 0.90f, 9 * u, 11 * u, 5 * u, KeyEvent.KEYCODE_3, 0, grey);
        rect("R2 GAS", w * 0.90f, 21 * u, 11 * u, 5 * u, KeyEvent.KEYCODE_BUTTON_R2, 0, grey);
        rect("BACK", w * 0.41f, 20 * u, 10 * u, 5 * u, KeyEvent.KEYCODE_BACK, 0, grey);
        rect("START", w * 0.59f, 20 * u, 10 * u, 5 * u, KeyEvent.KEYCODE_BUTTON_START, 0, grey);
        rect("OK", w * 0.50f, 34 * u, 10 * u, 5 * u, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0xFF00796B);
        rect("", w * 0.50f, 91 * u, 10 * u, 5 * u, 0, ACT_TILT, grey);
    }

    private void round(String l, float cx, float cy, float rad, int key, int color) {
        Btn b = new Btn();
        b.label = l; b.round = true; b.key = key; b.color = color; b.cx = cx; b.cy = cy; b.rad = rad;
        b.r.set(cx - rad * 1.15f, cy - rad * 1.15f, cx + rad * 1.15f, cy + rad * 1.15f);
        btns.add(b);
    }

    private void rect(String l, float cx, float cy, float hw, float hh, int key, int act, int color) {
        Btn b = new Btn();
        b.label = l; b.key = key; b.act = act; b.color = color; b.cx = cx; b.cy = cy;
        b.r.set(cx - hw, cy - hh, cx + hw, cy + hh);
        b.r.inset(-u, -u);
        btns.add(b);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF0B0F1A);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF1C2333);
        c.drawCircle(scx, scy, sr, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * 0.5f); p.setColor(0xFF455A64);
        c.drawCircle(scx, scy, sr, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF4DD0E1);
        float kx = stickHeld ? sx : tiltX;
        c.drawCircle(scx + kx * sr * 0.6f, scy + (stickHeld ? sy : 0) * sr * 0.6f, 10 * u, p);
        p.setTextSize(4.2f * u);
        for (Btn b : btns) {
            String label = b.label;
            boolean on = false;
            if (b.act == ACT_TILT) { label = tilt ? "TILT STEER: ON" : "TILT STEER: OFF"; on = tilt; }
            p.setStyle(Paint.Style.FILL);
            p.setColor(b.down ? 0xFFFFFFFF : b.color);
            if (b.round) c.drawCircle(b.cx, b.cy, b.rad, p);
            else c.drawRoundRect(b.r, 2 * u, 2 * u, p);
            if (on) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * 0.6f); p.setColor(0xFFFFD54F);
                c.drawRoundRect(b.r, 2 * u, 2 * u, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor((b.down || b.color == 0xFFFDD835) ? 0xFF000000 : 0xFFFFFFFF);
            float cy = b.round ? b.cy : (b.r.top + b.r.bottom) / 2;
            c.drawText(label, b.round ? b.cx : (b.r.left + b.r.right) / 2, cy - (p.descent() + p.ascent()) / 2, p);
        }
        p.setColor(0xFFFFD54F);
        p.setTextSize(4.5f * u);
        c.drawText(status, getWidth() / 2f, 8 * u, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            down(e.getPointerId(i), e.getX(i), e.getY(i));
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (owner.get(e.getPointerId(i)) == STICK) moveStick(e.getX(i), e.getY(i));
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            up(e.getPointerId(e.getActionIndex()));
        } else if (a == MotionEvent.ACTION_CANCEL) {
            releaseAll();
        }
        updateDirs();
        invalidate();
        return true;
    }

    private void down(int id, float x, float y) {
        for (Btn b : btns) {
            if (b.r.contains(x, y)) {
                owner.put(id, b);
                if (b.act != 0) host.onAction(b.act);
                else { b.down = true; host.onKey(b.key, true); }
                return;
            }
        }
        if (!stickHeld && x < getWidth() * 0.5f && Math.hypot(x - scx, y - scy) < sr * 1.8f) {
            owner.put(id, STICK);
            stickHeld = true;
            moveStick(x, y);
        }
    }

    private void up(int id) {
        Object o = owner.get(id);
        owner.remove(id);
        if (o == STICK) { stickHeld = false; sx = 0; sy = 0; }
        else if (o instanceof Btn) {
            Btn b = (Btn) o;
            if (b.down) { b.down = false; host.onKey(b.key, false); }
        }
    }

    /** Releases every key (used when the app pauses so nothing stays stuck on the TV). */
    public void releaseAll() {
        owner.clear();
        for (Btn b : btns) if (b.down) { b.down = false; host.onKey(b.key, false); }
        stickHeld = false; sx = 0; sy = 0; tiltX = 0;
        updateDirs();
        invalidate();
    }

    private void moveStick(float x, float y) {
        float dx = x - scx, dy = y - scy;
        float d = (float) Math.hypot(dx, dy);
        if (d > sr) { dx *= sr / d; dy *= sr / d; }
        sx = dx / sr;
        sy = dy / sr;
    }

    /** Tilt steering input, -1..1. */
    public void setTilt(float x) {
        tiltX = x;
        updateDirs();
        postInvalidate();
    }

    private void updateDirs() {
        float x = stickHeld ? sx : (tilt ? tiltX : 0);
        float y = stickHeld ? sy : 0;
        set(0, y, true);
        set(1, y, false);
        set(2, x, true);
        set(3, x, false);
    }

    // idx: 0 up (y<0), 1 down (y>0), 2 left (x<0), 3 right (x>0); hysteresis 0.50 on / 0.30 off
    private void set(int idx, float v, boolean negative) {
        float m = negative ? -v : v;
        boolean on = dirOn[idx] ? m > 0.30f : m > 0.50f;
        if (on != dirOn[idx]) { dirOn[idx] = on; host.onKey(dirKey[idx], on); }
    }
}
