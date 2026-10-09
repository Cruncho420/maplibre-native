package org.maplibre.android.maps.renderer;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;

/**
 * Puts capped frames ({@link MapRenderer#setMaximumFps(int)}) on the display's vsync grid.
 * <p>
 * The stock cap sleeps "frame budget minus render time" after each render. That period is a little longer than a
 * whole number of vsyncs (the swap and the render loop are not counted), so the swap drifts across the vsync phase
 * and every few dozen frames one frame misses its vsync: at a 30 fps cap on a 60 Hz panel about one interval in ten
 * is 50 ms followed by a 16 ms catch-up. This pacer instead releases each swap just after an app vsync, on a fixed
 * grid of whole vsyncs when the cap is a whole fraction of the refresh rate. A frame that is already late is
 * swapped at once, as before.
 * <p>
 * The vsync phase comes from a {@link Choreographer} frame time and the period from two consecutive frame times (checked
 * against the display's nominal refresh rate, refined over the time since the previous sample), read on the main
 * thread about once a second while the map is drawing; sampling stops two
 * seconds after the last frame and forgets the grid, so a resumed or re-moded display is sampled afresh. Until a
 * sample exists, and for a map on any display but the default one (whose vsync the main Choreographer follows, e.g.
 * a virtual display), the stock sleep is used.
 */
final class FramePacer implements Choreographer.FrameCallback {

  /** Release the swap this long after the app vsync: early in the window, so the compositor latches it. */
  private static final long MARGIN_NS = 2_000_000L;
  private static final long SAMPLE_EVERY_MS = 1000L;
  private static final long IDLE_STOP_NS = 2_000_000_000L;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final MapRenderer renderer;
  private final Runnable start = new Runnable() {
    @Override
    public void run() {
      Choreographer.getInstance().postFrameCallback(FramePacer.this);
    }
  };

  /** One vsync sample, published as a whole so the render thread never pairs a new period with an old phase. */
  private static final class Grid {
    final long anchorNs;
    final long periodNs;

    Grid(long anchorNs, long periodNs) {
      this.anchorNs = anchorNs;
      this.periodNs = periodNs;
    }
  }

  // Written on the main thread, read on the render thread.
  private volatile Grid grid;
  private volatile boolean sampling;
  private volatile long lastUseNs;

  // Main thread only.
  private long prevVsyncNs;
  private long lastAnchorNs;
  private long lastShortPeriodNs;

  // Render thread only.
  private long idealNs;

  FramePacer(MapRenderer renderer) {
    this.renderer = renderer;
  }

  @Override
  public void doFrame(long frameTimeNanos) {
    if (System.nanoTime() - lastUseNs > IDLE_STOP_NS) {
      grid = null;
      sampling = false;
      prevVsyncNs = 0;
      lastAnchorNs = 0;
      return;
    }
    if (prevVsyncNs == 0) {
      // First of two consecutive callbacks: the second one gives a one-vsync interval.
      prevVsyncNs = frameTimeNanos;
      Choreographer.getInstance().postFrameCallback(this);
      return;
    }
    long shortPeriod = frameTimeNanos - prevVsyncNs;
    prevVsyncNs = 0;
    View view = renderer.getView();
    Display display = view == null ? null : view.getDisplay();
    float hz = display == null ? 0f : display.getRefreshRate();
    long nominal = hz >= 24f && hz <= 500f ? (long) (1e9 / hz) : 0;
    if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY || nominal == 0
        || Math.abs(shortPeriod - nominal) > nominal / 4) {
      // Not the default display, no refresh rate, or a skipped callback: no grid this round.
      grid = null;
      lastAnchorNs = 0;
    } else {
      // The panel's real vsync period can differ from its nominal rate by ~1 % (measured 16.8 ms in a "60 Hz" mode on a
      // Pixel 8a), which drifts a 1-s-old anchor by ~10 ms. Refine the one-interval estimate over the time since the
      // previous sample: a whole number of vsyncs.
      long period = shortPeriod;
      long span = frameTimeNanos - lastAnchorNs;
      // Only within one display mode: a span that crosses a 60 <-> 120 Hz switch is not a whole number of either.
      if (lastAnchorNs != 0 && span < 3_000_000_000L && Math.abs(shortPeriod - lastShortPeriodNs) < shortPeriod / 50) {
        long vsyncs = Math.round((double) span / shortPeriod);
        if (vsyncs > 0) {
          period = span / vsyncs;
        }
      }
      grid = new Grid(frameTimeNanos, period);
      lastAnchorNs = frameTimeNanos;
      lastShortPeriodNs = shortPeriod;
    }
    Choreographer.getInstance().postFrameCallbackDelayed(this, SAMPLE_EVERY_MS);
  }

  /**
   * Render thread, after the frame is drawn and before the swap: sleeps until this frame's slot.
   *
   * @return false when no vsync sample exists yet (the caller falls back to the stock sleep)
   */
  boolean pace(double frameBudgetNs) throws InterruptedException {
    long now = System.nanoTime();
    lastUseNs = now;
    if (!sampling) {
      sampling = true;
      mainHandler.post(start);
    }
    Grid sample = grid;
    if (sample == null) {
      return false;
    }
    long period = sample.periodNs;
    long anchor = sample.anchorNs;
    double vsyncs = frameBudgetNs / period;
    long whole = Math.round(vsyncs);
    boolean onGrid = whole >= 1 && Math.abs(vsyncs - whole) < 0.1;
    long step = onGrid ? whole * period : (long) frameBudgetNs;

    idealNs += step;
    long target = slotAtOrAfter(idealNs, anchor, period);
    if (target < now || idealNs > now + 2 * step) {
      // Late (or the first frame, or after an idle gap): swap now, as without pacing (the compositor shows it at the
      // next vsync); the next frame keeps the spacing from that vsync. A swap anywhere between two slots is latched
      // like one in the earlier slot, so that slot is this frame's place on the grid.
      idealNs = slotAtOrAfter(now, anchor, period) - period;
      return true;
    }
    if (onGrid) {
      idealNs = target; // stay on the grid, so a cap/refresh mismatch of a few ppm never drifts into a slip
    }
    long sleepNs = target - now;
    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
    return true;
  }

  /** First "app vsync + margin" at or after {@code t}. */
  private static long slotAtOrAfter(long t, long anchor, long period) {
    long k = -Math.floorDiv(-(t - anchor - MARGIN_NS), period); // ceil, also for t before the anchor
    return anchor + k * period + MARGIN_NS;
  }
}
