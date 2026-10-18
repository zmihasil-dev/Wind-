package pet.margyt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * Wind overlay: streaks of air and flying leaves drawn over the whole screen.
 * (The file name is kept from the earlier plugin so the GitHub workflow works unchanged.)
 * It never takes touches: everything goes through to the app underneath.
 */
final class PetView extends View {

    private static final class Streak {
        float y, len, delay, span, thick, amp, alpha;
    }

    private static final class Leaf {
        float y, delay, span, size, spin, wob, wobF;
        int color;
    }

    private static final int[] LEAF_COLORS = {
            0xFF7FB85A, 0xFFB8D86B, 0xFFE0C36A, 0xFFFFFFFF, 0xFFA0D8EF
    };

    private final Random rnd = new Random();
    private final Streak[] streaks = new Streak[44];
    private final Leaf[] leaves = new Leaf[18];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leafPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float d;

    private float progress;
    private int dir = 1;
    private boolean active;
    private ValueAnimator anim;
    private LinearGradient gradient;
    private int gradientW;

    PetView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(0xFFFFFFFF);
        for (int i = 0; i < streaks.length; i++) {
            streaks[i] = new Streak();
        }
        for (int i = 0; i < leaves.length; i++) {
            leaves[i] = new Leaf();
        }
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    private void reseed() {
        for (int i = 0; i < streaks.length; i++) {
            Streak s = streaks[i];
            s.y = rnd.nextFloat();
            s.len = 0.25f + rnd.nextFloat() * 0.5f;
            s.delay = rnd.nextFloat() * 0.45f;
            s.span = 0.35f + rnd.nextFloat() * 0.2f;
            s.thick = (1.2f + rnd.nextFloat() * 3.2f) * d;
            s.amp = (8f + rnd.nextFloat() * 40f) * d;
            s.alpha = 0.35f + rnd.nextFloat() * 0.45f;
        }
        for (int i = 0; i < leaves.length; i++) {
            Leaf l = leaves[i];
            l.y = rnd.nextFloat();
            l.delay = rnd.nextFloat() * 0.4f;
            l.span = 0.4f + rnd.nextFloat() * 0.2f;
            l.size = (4f + rnd.nextFloat() * 7f) * d;
            l.spin = (rnd.nextFloat() - 0.5f) * 1500f;
            l.wob = 0.3f + rnd.nextFloat();
            l.wobF = 1.5f + rnd.nextFloat() * 3f;
            l.color = LEAF_COLORS[rnd.nextInt(LEAF_COLORS.length)];
        }
    }

    /** direction +1: left to right, -1: right to left. */
    void blow(int direction, long duration, final Runnable done) {
        if (anim != null) {
            anim.cancel();
        }
        dir = direction;
        reseed();
        progress = 0f;
        active = true;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(duration);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                active = false;
                invalidate();
                if (!cancelled && done != null) {
                    done.run();
                }
            }
        });
        anim.start();
    }

    void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        active = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false;
    }

    private static int a255(float v) {
        int i = (int) (v * 255f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!active) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float env = (float) Math.sin(Math.PI * progress);

        // the gust front: a soft light band that runs with the wind
        int bw = (int) (w * 0.55f);
        if (gradient == null || gradientW != bw) {
            gradientW = bw;
            gradient = new LinearGradient(0f, 0f, bw, 0f,
                    new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            bandPaint.setShader(gradient);
        }
        float front = progress * 1.4f - 0.2f;
        float cx = dir > 0 ? front * w : w - front * w;
        bandPaint.setAlpha(a255(env));
        c.save();
        c.translate(cx - bw / 2f, 0f);
        c.drawRect(0f, 0f, bw, h, bandPaint);
        c.restore();

        // streaks of air
        for (int i = 0; i < streaks.length; i++) {
            Streak s = streaks[i];
            float q = (progress - s.delay) / s.span;
            if (q <= 0f || q >= 1f) {
                continue;
            }
            float len = s.len * w;
            float tail = -len + q * (w + 2f * len);
            float head = tail + len;
            if (dir < 0) {
                tail = w - tail;
                head = w - head;
            }
            float y = s.y * h;
            float bend = s.amp * (float) Math.sin(q * 6f + s.delay * 10f);
            path.reset();
            path.moveTo(tail, y);
            path.quadTo((tail + head) / 2f, y + bend, head, y - bend * 0.4f);
            line.setStrokeWidth(s.thick);
            line.setAlpha(a255(s.alpha * (float) Math.sin(Math.PI * q)));
            c.drawPath(path, line);
        }

        // leaves and dust carried by the wind
        for (int i = 0; i < leaves.length; i++) {
            Leaf l = leaves[i];
            float q = (progress - l.delay) / l.span;
            if (q <= 0f || q >= 1f) {
                continue;
            }
            float x = -l.size * 2f + q * (w + l.size * 4f);
            if (dir < 0) {
                x = w - x;
            }
            float y = l.y * h + (float) Math.sin(q * l.wobF * 6.2832f) * l.wob * h * 0.06f;
            leafPaint.setColor(l.color);
            leafPaint.setAlpha(a255((float) Math.sin(Math.PI * q)));
            c.save();
            c.translate(x, y);
            c.rotate(q * l.spin * dir);
            rect.set(-l.size, -l.size * 0.45f, l.size, l.size * 0.45f);
            c.drawOval(rect, leafPaint);
            c.restore();
        }
    }
}

/** The round button that starts and ends the wind. Tap = blow, drag = move it. */
final class WindButton extends View {

    interface Listener {
        void onTap();

        void onMoved(float fx, float fy);
    }

    private final Listener listener;
    private final float d;
    private final int size;
    private final float slop;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean swept;
    private boolean pressed;
    private boolean dragging;
    private float downX, downY, startTx, startTy;
    private float fx, fy;
    private int tries;

    private final Runnable placer = new Runnable() {
        @Override
        public void run() {
            place();
        }
    };

    WindButton(Context c, float fx, float fy, Listener l) {
        super(c);
        this.fx = fx;
        this.fy = fy;
        this.listener = l;
        this.d = c.getResources().getDisplayMetrics().density;
        this.size = (int) (54f * d);
        this.slop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), 8f * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(26f * d);
        setClickable(true);
    }

    int sizePx() {
        return size;
    }

    void setSwept(boolean s) {
        swept = s;
        invalidate();
    }

    void place() {
        View p = getParent() instanceof View ? (View) getParent() : null;
        if (p == null) {
            return;
        }
        int w = p.getWidth();
        int h = p.getHeight();
        if (w <= 0 || h <= 0) {
            if (tries++ < 30) {
                postDelayed(placer, 100L);
            }
            return;
        }
        setTranslationX(clamp(fx * w - size / 2f, 0f, w - size));
        setTranslationY(clamp(fy * h - size / 2f, 0f, h - size));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tries = 0;
        post(placer);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(placer);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = size / 2f;
        fill.setColor(0x33000000);
        c.drawCircle(r, r + 2f * d, r - 3f * d, fill);
        fill.setColor(pressed ? 0xFFCFE9FF : (swept ? 0xF2BFE3FF : 0xEEFFFFFF));
        c.drawCircle(r, r, r - 3f * d, fill);
        text.setColor(0xFF000000);
        c.drawText(swept ? "↩" : "💨", r, r + 9f * d, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        View p = getParent() instanceof View ? (View) getParent() : null;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                dragging = false;
                downX = e.getRawX();
                downY = e.getRawY();
                startTx = getTranslationX();
                startTy = getTranslationY();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (p == null) {
                    return true;
                }
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                }
                if (dragging) {
                    setTranslationX(clamp(startTx + dx, 0f, p.getWidth() - size));
                    setTranslationY(clamp(startTy + dy, 0f, p.getHeight() - size));
                }
                return true;
            case MotionEvent.ACTION_UP:
                pressed = false;
                if (dragging && p != null && p.getWidth() > 0 && p.getHeight() > 0) {
                    fx = (getTranslationX() + size / 2f) / p.getWidth();
                    fy = (getTranslationY() + size / 2f) / p.getHeight();
                    listener.onMoved(fx, fy);
                } else if (!dragging) {
                    listener.onTap();
                }
                dragging = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                dragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}

/** Wind noise made on the fly: no sound files needed. */
final class WindSound {

    private static final int RATE = 22050;
    private static short[] sweepBuf;
    private static short[] backBuf;

    private WindSound() {
    }

    private static synchronized short[] buffer(boolean back) {
        if (back) {
            if (backBuf == null) {
                backBuf = make(true);
            }
            return backBuf;
        }
        if (sweepBuf == null) {
            sweepBuf = make(false);
        }
        return sweepBuf;
    }

    private static short[] make(boolean back) {
        int n = (int) (RATE * 1.5f);
        short[] out = new short[n];
        Random r = new Random(back ? 7L : 3L);
        float lp1 = 0f;
        float lp2 = 0f;
        double phase = 0.0;
        float peak = back ? 0.55f : 0.42f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float env = t < peak
                    ? (float) Math.sin(t / peak * Math.PI / 2.0)
                    : (float) Math.cos((t - peak) / (1f - peak) * Math.PI / 2.0);
            env = env * env;
            float a = 0.04f + 0.30f * (float) Math.sin(Math.PI * t);
            float noise = r.nextFloat() * 2f - 1f;
            lp1 += a * (noise - lp1);
            lp2 += 0.012f * (lp1 - lp2);
            float gust = 0.75f + 0.25f * (float) Math.sin(2.0 * Math.PI * 6.0 * t + (back ? 1.0 : 0.0));
            float f = (back ? 640f : 380f) + (back ? -260f : 260f) * (float) Math.sin(Math.PI * t);
            phase += 2.0 * Math.PI * f / RATE;
            float v = (lp1 - lp2) * 3.2f * gust + 0.05f * (float) Math.sin(phase);
            v *= env;
            if (i < 200) {
                v *= i / 200f;
            }
            if (i > n - 400) {
                v *= (n - i) / 400f;
            }
            v = Math.max(-1f, Math.min(1f, v));
            out[i] = (short) (v * 30000f);
        }
        return out;
    }

    static void play(final boolean back) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    short[] buf = buffer(back);
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            buf.length * 2, AudioTrack.MODE_STATIC);
                    track.write(buf, 0, buf.length);
                    track.play();
                    Thread.sleep(1700L);
                } catch (Throwable ignored) {
                    // no sound is better than a crash
                } finally {
                    if (track != null) {
                        try {
                            track.release();
                        } catch (Throwable ignored) {
                            // nothing to do
                        }
                    }
                }
            }
        }).start();
    }
}
