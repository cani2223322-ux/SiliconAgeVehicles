package com.sva.vehicle;

/**
 * The server's time budget for the driver's Move packets (EntityVehicleSVA.serverMove): a token bucket of client steps.
 * <p>
 * The driver's client and the server tick unsynchronised and ServerQueueSVA keeps only the newest Move of a tick, so one
 * server tick may get two (three) client steps and the next none; a lagging server's catch-up ticks run back to back and
 * get none at all. The old rule let one step be borrowed (credit -1..+1): two bunched steps in a row with no empty tick
 * between (the user's log: «why=1 bh=3.98 dt=1 spd=1.99» - the liner at 144 km/h, where two steps no longer fit one
 * tick's reach) were refused, the server's position stood still, the measured speed collapsed and the liner snapped back.
 * <p>
 * Here the credit (steps) grows by the time that passed at each tick's first Move - the server ticks, or the real time
 * when that is more (a server under 20 TPS) - and each accepted Move takes the steps it needed. The carried credit stays
 * in [-CAP, +CAP]: a burst may borrow up to CAP steps (repaid by the empty ticks after it), and up to CAP steps the
 * client was late with are kept for its catch-up - but never more, so a long silence hoards nothing and a steady 2x pace
 * runs out within CAP + 1 ticks. Over any stretch the steps accepted are at most the time elapsed + 2 CAP.
 */
public final class MoveBudget {

    /** The carried credit's bound (steps either way). */
    public static final double CAP = 3;
    /** Rounding slack (steps): the real time's fractions never cost a whole step. */
    public static final double SLACK = 0.25;
    /** One step, ms. */
    public static final double STEP_MS = 50;
    /**
     * The most real time one tick's first Move may bring (steps): MinecraftServer catches up at most 2 s of lag (then it
     * skips ticks) - a client that flew through a 1.2 s stall covered 24 steps; refusing it was the snap-back.
     */
    public static final int MAX_ELAPSED = 40;

    private double credit, avail;
    private long lastTick = Long.MIN_VALUE, used;
    private double clockMs;

    /**
     * A tick's first Move: the time since the last Move tick goes in (at most MAX_ELAPSED). After a silence over
     * `timeout` server ticks (the server drove meanwhile, or ran its catch-up ticks) it starts fresh with one step.
     * `ms` the real time (ms) - a lagging server's ticks count by it, and the catch-up ticks that follow are not counted
     * twice (the credited clock may run ahead of the real time).
     */
    public void tick(long now, long ms, int timeout) {
        if (now == lastTick) {
            return;
        }
        long ticks = now - lastTick;
        double elapsed;
        if (lastTick == Long.MIN_VALUE || ticks > timeout || ticks < 0) {
            credit = 0;
            elapsed = 1;
            clockMs = ms;
        } else {
            double real = (ms - clockMs) / STEP_MS;
            elapsed = Math.min(MAX_ELAPSED, Math.max(ticks, real));
            clockMs = Math.max(clockMs + elapsed * STEP_MS, ms - MAX_ELAPSED * STEP_MS);
        }
        lastTick = now;
        avail = credit + elapsed;
        used = 0;
    }

    /** The most steps this tick's displacement may take (at least 0). */
    public long maxSteps() {
        return Math.max(0, (long) Math.floor(avail + CAP + SLACK));
    }

    /** An accepted Move needed `d` steps for the tick's whole displacement (Moves of one tick share it). */
    public void accept(long d) {
        used = Math.max(used, d);
        credit = Math.max(-CAP, Math.min(CAP, avail - used));
    }

    /** The carried credit (steps; diagnostics, tests). */
    public double credit() {
        return credit;
    }
}
