package app.rubanled;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/**
 * Colour wheel: the angle is the hue (same 0-359 as the strip), the distance from the centre the
 * saturation (white in the middle, pure colour on the rim). Reports changes while dragging.
 */
final class ColourWheel extends View {
    interface Listener {
        /** done: the finger was lifted. */
        void onColour(int hue, int sat, boolean done);
    }

    private final Paint wheel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private int hue, sat;
    private float cx, cy, radius;

    ColourWheel(Context ctx) {
        super(ctx);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * getResources().getDisplayMetrics().density);
        ring.setColor(Color.WHITE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /**
     * Locked (owner, 17 Nov: a finger scrolling the page over the alert wheel turned it blue):
     * touches go to the page, the wheel is drawn dimmed. Unlocked from the app's button.
     */
    private boolean locked = true;

    void setLocked(boolean l) {
        locked = l;
        setAlpha(l ? 0.45f : 1f);
        invalidate();
    }

    void setColour(int hue, int sat) {
        this.hue = hue;
        this.sat = sat;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        if (size == 0) size = Math.max(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        setMeasuredDimension(size, size);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - ring.getStrokeWidth() * 3;
        int[] colours = new int[13];
        for (int i = 0; i < colours.length; i++) {
            // SweepGradient runs clockwise from 3 o'clock; hue goes the same way
            colours[i] = Color.HSVToColor(new float[] {i * 30f % 360, 1f, 1f});
        }
        Shader sweep = new SweepGradient(cx, cy, colours, null);
        Shader white = new RadialGradient(cx, cy, radius, Color.WHITE, 0x00ffffff, Shader.TileMode.CLAMP);
        wheel.setShader(new ComposeShader(sweep, white, PorterDuff.Mode.SRC_OVER));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawCircle(cx, cy, radius, wheel);
        double a = Math.toRadians(hue);
        float r = radius * sat / 100f;
        float x = cx + (float) (Math.cos(a) * r);
        float y = cy + (float) (Math.sin(a) * r);
        dot.setColor(Color.HSVToColor(new float[] {hue, sat / 100f, 1f}));
        float d = ring.getStrokeWidth() * 3;
        c.drawCircle(x, y, d, dot);
        c.drawCircle(x, y, d, ring);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (locked) return false;                               // the page scrolls
        int action = e.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
            && action != MotionEvent.ACTION_UP) return super.onTouchEvent(e);
        float dx = e.getX() - cx, dy = e.getY() - cy;
        // a touch must start on the wheel itself, not in the corners of its square
        if (action == MotionEvent.ACTION_DOWN && Math.hypot(dx, dy) > radius * 1.1) return false;
        getParent().requestDisallowInterceptTouchEvent(true);   // don't scroll the page
        double deg = Math.toDegrees(Math.atan2(dy, dx));
        if (deg < 0) deg += 360;
        hue = (int) Math.round(deg) % 360;
        sat = (int) Math.round(Math.min(1.0, Math.hypot(dx, dy) / radius) * 100);
        invalidate();
        if (listener != null) listener.onColour(hue, sat, action == MotionEvent.ACTION_UP);
        return true;
    }
}
