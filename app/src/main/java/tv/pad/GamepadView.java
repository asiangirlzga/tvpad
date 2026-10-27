package tv.pad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Touch gamepad: left stick, A/B/X/Y, shoulders, start/select and a few toggles. Multi-touch. */
public class GamepadView extends View {
    public interface Host {
        void onInput();
        void onAction(int id);
    }

    // HID button bits (match Android's gamepad key mapping order)
    public static final int A = 1, B = 1 << 1, X = 1 << 3, Y = 1 << 4, L1 = 1 << 6, R1 = 1 << 7,
            L2 = 1 << 8, R2 = 1 << 9, SEL = 1 << 10, START = 1 << 11;
    public static final int ACT_KEYS = 1, ACT_TILT = 2, ACT_DPAD = 3, ACT_CONNECT = 4, ACT_PAIR = 5;

    private static class Btn {
        String label;
        boolean round;
        int bit, act, color;
        boolean down;
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

    // state read by the activity
    public int buttons;
    public float sx, sy;
    public boolean keys, tilt, dpad;
    public String status = "Not connected";

    public GamepadView(Context c, Host host) {
        super(c);
        this.host = host;
        p.setTextAlign(Paint.Align.CENTER);
    }

    public boolean stickHeld() { return stickHeld; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        btns.clear();
        u = h / 100f;
        scx = w * 0.20f; scy = 66 * u; sr = 24 * u;
        int grey = 0xFF37474F;
        float fx = w * 0.80f, fy = 62 * u, d = 16 * u;
        round("A", fx, fy + d, 9 * u, A, 0xFF43A047);
        round("B", fx + d, fy, 9 * u, B, 0xFFE53935);
        round("X", fx - d, fy, 9 * u, X, 0xFF1E88E5);
        round("Y", fx, fy - d, 9 * u, Y, 0xFFFDD835);
        rect("L1", w * 0.10f, 9 * u, 11 * u, 5 * u, L1, 0, grey);
        rect("L2", w * 0.10f, 21 * u, 11 * u, 5 * u, L2, 0, grey);
        rect("R1", w * 0.90f, 9 * u, 11 * u, 5 * u, R1, 0, grey);
        rect("R2", w * 0.90f, 21 * u, 11 * u, 5 * u, R2, 0, grey);
        rect("SELECT", w * 0.41f, 20 * u, 10 * u, 5 * u, SEL, 0, grey);
        rect("START", w * 0.59f, 20 * u, 10 * u, 5 * u, START, 0, grey);
        rect("CONNECT", w * 0.41f, 34 * u, 10 * u, 5 * u, 0, ACT_CONNECT, 0xFF00796B);
        rect("PAIR NEW", w * 0.59f, 34 * u, 10 * u, 5 * u, 0, ACT_PAIR, 0xFF00796B);
        rect("", w * 0.38f, 91 * u, 10 * u, 5 * u, 0, ACT_KEYS, grey);
        rect("", w * 0.50f, 91 * u, 10 * u, 5 * u, 0, ACT_TILT, grey);
        rect("", w * 0.62f, 91 * u, 10 * u, 5 * u, 0, ACT_DPAD, grey);
    }

    private void round(String l, float cx, float cy, float rad, int bit, int color) {
        Btn b = new Btn();
        b.label = l; b.round = true; b.bit = bit; b.color = color; b.cx = cx; b.cy = cy; b.rad = rad;
        b.r.set(cx - rad * 1.15f, cy - rad * 1.15f, cx + rad * 1.15f, cy + rad * 1.15f);
        btns.add(b);
    }

    private void rect(String l, float cx, float cy, float hw, float hh, int bit, int act, int color) {
        Btn b = new Btn();
        b.label = l; b.bit = bit; b.act = act; b.color = color; b.cx = cx; b.cy = cy;
        b.r.set(cx - hw, cy - hh, cx + hw, cy + hh);
        b.r.inset(-u, -u);
        btns.add(b);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF0B0F1A);
        // stick
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF1C2333);
        c.drawCircle(scx, scy, sr, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * 0.5f); p.setColor(0xFF455A64);
        c.drawCircle(scx, scy, sr, p);
        p.setStyle(Paint.Style.FILL); p.setColor(dpad ? 0xFFFFB74D : 0xFF4DD0E1);
        c.drawCircle(scx + sx * sr * 0.6f, scy + sy * sr * 0.6f, 10 * u, p);
        // buttons
        p.setTextSize(4.2f * u);
        for (Btn b : btns) {
            String label = b.label;
            boolean on = false;
            if (b.act == ACT_KEYS) { label = keys ? "KEYBOARD" : "GAMEPAD"; on = keys; }
            else if (b.act == ACT_TILT) { label = tilt ? "TILT: ON" : "TILT: OFF"; on = tilt; }
            else if (b.act == ACT_DPAD) { label = dpad ? "D-PAD" : "STICK"; on = dpad; }
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
        recalc();
        invalidate();
        host.onInput();
        return true;
    }

    private void down(int id, float x, float y) {
        for (Btn b : btns) {
            if (b.r.contains(x, y)) {
                owner.put(id, b);
                if (b.act != 0) host.onAction(b.act); else b.down = true;
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
        else if (o instanceof Btn) ((Btn) o).down = false;
    }

    public void releaseAll() {
        owner.clear();
        for (Btn b : btns) b.down = false;
        stickHeld = false; sx = 0; sy = 0;
        recalc();
        invalidate();
    }

    private void moveStick(float x, float y) {
        float dx = x - scx, dy = y - scy;
        float d = (float) Math.hypot(dx, dy);
        if (d > sr) { dx *= sr / d; dy *= sr / d; }
        sx = dx / sr;
        sy = dy / sr;
    }

    private void recalc() {
        int m = 0;
        for (Btn b : btns) if (b.down) m |= b.bit;
        buttons = m;
    }
}
