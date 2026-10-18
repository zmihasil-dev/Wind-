package pet.margyt;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import cat.narezany.margyt.plugin.MargyPlugin;

/**
 * Wind plugin. A button blows wind left to right and sweeps every view off the screen
 * except the video; pressing it again brings everything back, right to left.
 *
 * How "the video" is found: the player draws into a SurfaceView or a TextureView, so those
 * views and everything that contains them stay; every other view next to them is blown away.
 * (The class name is kept from the earlier plugin so the GitHub workflow works unchanged.)
 */
public final class PetPlugin extends MargyPlugin {

    private static final long SWEEP_MS = 640L;
    private static final float STAGGER_MS = 340f;
    private static final long WIND_MS = 1500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashMap<View, float[]> store = new HashMap<View, float[]>();
    private final Random rnd = new Random();

    private Activity cur;
    private ViewGroup decor;
    private PetView wind;
    private WindButton button;
    private boolean swept;
    private boolean busy;

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!swept) {
                return;
            }
            if (!busy) {
                try {
                    rescan();
                } catch (Throwable t) {
                    margyt().log("wind: rescan failed: " + t);
                }
            }
            main.postDelayed(this, 280L);
        }
    };

    // ------------------------------------------------------------------ hooks

    @Override
    public void onStart(Context context) {
        margyt().log("wind: started");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            attach(activity);
        } catch (Throwable t) {
            margyt().log("wind: attach failed: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            if (activity == cur) {
                teardown();
            }
        } catch (Throwable t) {
            margyt().log("wind: detach failed: " + t);
        }
    }

    @Override
    public void onStop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    teardown();
                } catch (Throwable t) {
                    margyt().log("wind: stop failed: " + t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ views

    private void attach(Activity a) {
        View dv = a.getWindow().getDecorView();
        if (!(dv instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) dv;
        Context app = margyt().context();
        if (cur != a || decor != root || wind == null || button == null) {
            teardown();
            cur = a;
            decor = root;
            wind = new PetView(app);
            button = new WindButton(app,
                    margyt().prefs().getFloat("bx", 0.09f),
                    margyt().prefs().getFloat("by", 0.42f),
                    new WindButton.Listener() {
                        @Override
                        public void onTap() {
                            toggle();
                        }

                        @Override
                        public void onMoved(float fx, float fy) {
                            margyt().prefs().edit().putFloat("bx", fx).putFloat("by", fy).apply();
                        }
                    });
        }
        if (wind.getParent() != root) {
            removeFromParent(wind);
            root.addView(wind, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (button.getParent() != root) {
            removeFromParent(button);
            int s = button.sizePx();
            root.addView(button, new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.LEFT));
        }
        wind.bringToFront();
        button.bringToFront();
    }

    private static void removeFromParent(View v) {
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private void teardown() {
        main.removeCallbacks(watch);
        if (wind != null) {
            wind.stop();
        }
        instantRestore();
        swept = false;
        busy = false;
        if (button != null) {
            button.setSwept(false);
            removeFromParent(button);
        }
        if (wind != null) {
            removeFromParent(wind);
        }
        cur = null;
        decor = null;
        wind = null;
        button = null;
    }

    private void toast(String ru, String en) {
        boolean isRu = "ru".equals(Locale.getDefault().getLanguage());
        Toast.makeText(margyt().context(), isRu ? ru : en, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ finding the video

    private static boolean isVideoSurface(View v) {
        if (v instanceof SurfaceView || v instanceof TextureView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            return false;
        }
        String n = v.getClass().getName();
        return n.endsWith("VideoView") || n.contains("TextureView") || n.contains("SurfaceView");
    }

    private void findVideo(View v, ArrayList<View> out, long rootArea, Rect tmp) {
        if (v == wind || v == button || v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (isVideoSurface(v)) {
            if (v.getWidth() > 0 && v.getHeight() > 0 && v.getGlobalVisibleRect(tmp)) {
                long area = (long) tmp.width() * (long) tmp.height();
                if (area * 100L >= rootArea * 18L) {
                    out.add(v);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                findVideo(g.getChildAt(i), out, rootArea, tmp);
            }
        }
    }

    private ArrayList<View> videos() {
        ArrayList<View> out = new ArrayList<View>();
        if (decor != null) {
            long area = (long) decor.getWidth() * (long) decor.getHeight();
            if (area > 0L) {
                findVideo(decor, out, area, new Rect());
            }
        }
        return out;
    }

    private HashSet<View> keepSet(ArrayList<View> video) {
        HashSet<View> keep = new HashSet<View>();
        for (int i = 0; i < video.size(); i++) {
            View v = video.get(i);
            while (v != null) {
                keep.add(v);
                if (v == decor) {
                    break;
                }
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
        }
        return keep;
    }

    private void collect(ViewGroup g, HashSet<View> keep, ArrayList<View> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == wind || c == button) {
                continue;
            }
            if (keep.contains(c)) {
                if (c instanceof ViewGroup) {
                    collect((ViewGroup) c, keep, out);
                }
            } else if (c.getVisibility() == View.VISIBLE && c.getWidth() > 0 && c.getHeight() > 0) {
                out.add(c);
            }
        }
    }

    // ------------------------------------------------------------------ sweeping

    /** tx, ty, alpha, rotation, centre as a fraction of the width, distance to push it off the right edge. */
    private float[] record(View v, int w) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        float density = margyt().context().getResources().getDisplayMetrics().density;
        float frac = w > 0 ? (loc[0] + v.getWidth() / 2f) / w : 0.5f;
        frac = Math.max(0f, Math.min(1f, frac));
        float push = (w - loc[0]) + 40f * density;
        return new float[]{v.getTranslationX(), v.getTranslationY(), v.getAlpha(), v.getRotation(), frac, push};
    }

    private static void apply(View v, float[] o) {
        v.animate().cancel();
        v.setTranslationX(o[0]);
        v.setTranslationY(o[1]);
        v.setAlpha(o[2]);
        v.setRotation(o[3]);
    }

    private void instantRestore() {
        for (Map.Entry<View, float[]> e : store.entrySet()) {
            apply(e.getKey(), e.getValue());
        }
        store.clear();
    }

    private void toggle() {
        if (busy || decor == null || wind == null) {
            return;
        }
        try {
            if (swept) {
                startRestore();
            } else {
                startSweep();
            }
        } catch (Throwable t) {
            margyt().log("wind: toggle failed: " + t);
            instantRestore();
            swept = false;
            busy = false;
            if (button != null) {
                button.setSwept(false);
            }
        }
    }

    private void startSweep() {
        ArrayList<View> video = videos();
        if (video.isEmpty()) {
            margyt().log("wind: no video surface found on " + cur);
            toast("Видео на этом экране не найдено", "No video found on this screen");
            return;
        }
        HashSet<View> keep = keepSet(video);
        ArrayList<View> targets = new ArrayList<View>();
        collect(decor, keep, targets);
        margyt().log("wind: video views=" + video.size() + ", blowing away " + targets.size());

        busy = true;
        swept = true;
        button.setSwept(true);
        WindSound.play(false);
        wind.blow(1, WIND_MS, new Runnable() {
            @Override
            public void run() {
                busy = false;
                main.removeCallbacks(watch);
                main.postDelayed(watch, 280L);
            }
        });

        int w = decor.getWidth();
        float density = margyt().context().getResources().getDisplayMetrics().density;
        for (int i = 0; i < targets.size(); i++) {
            View v = targets.get(i);
            float[] o = record(v, w);
            store.put(v, o);
            v.animate().cancel();
            v.animate()
                    .translationX(o[0] + o[5])
                    .translationY(o[1] + (rnd.nextFloat() - 0.5f) * 60f * density)
                    .rotation(o[3] + (rnd.nextFloat() - 0.5f) * 24f)
                    .alpha(0f)
                    .setStartDelay((long) (o[4] * STAGGER_MS))
                    .setDuration(SWEEP_MS)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .start();
        }
    }

    private void startRestore() {
        busy = true;
        main.removeCallbacks(watch);
        WindSound.play(true);
        wind.blow(-1, WIND_MS, new Runnable() {
            @Override
            public void run() {
                instantRestore();
                swept = false;
                busy = false;
                if (button != null) {
                    button.setSwept(false);
                }
            }
        });
        for (Map.Entry<View, float[]> e : store.entrySet()) {
            View v = e.getKey();
            float[] o = e.getValue();
            v.animate().cancel();
            v.animate()
                    .translationX(o[0])
                    .translationY(o[1])
                    .rotation(o[3])
                    .alpha(o[2])
                    .setStartDelay((long) ((1f - o[4]) * STAGGER_MS))
                    .setDuration(SWEEP_MS)
                    .setInterpolator(new DecelerateInterpolator(1.3f))
                    .start();
        }
    }

    /**
     * While the screen is swept the feed keeps living: a new video page appears, views get reused.
     * This keeps new things out of sight and gives the video page back its surface.
     */
    private void rescan() {
        if (decor == null) {
            return;
        }
        ArrayList<View> video = videos();
        if (video.isEmpty()) {
            return;
        }
        HashSet<View> keep = keepSet(video);

        ArrayList<View> back = new ArrayList<View>();
        for (View v : store.keySet()) {
            if (keep.contains(v)) {
                back.add(v);
            }
        }
        for (int i = 0; i < back.size(); i++) {
            View v = back.get(i);
            apply(v, store.remove(v));
        }

        ArrayList<View> targets = new ArrayList<View>();
        collect(decor, keep, targets);
        int w = decor.getWidth();
        for (int i = 0; i < targets.size(); i++) {
            View v = targets.get(i);
            if (store.containsKey(v)) {
                continue;
            }
            float[] o = record(v, w);
            store.put(v, o);
            v.animate().cancel();
            v.setTranslationX(o[0] + o[5]);
            v.setAlpha(0f);
        }
    }
}
