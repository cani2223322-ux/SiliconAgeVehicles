package com.sva.vehicle;

/**
 * The SA-320's view from the cockpit (plan-next §8, the user's items 1, 3, 4, 6; mock-ups
 * docs/mockups/veh_liner_impl_view_*.png) as pure maths - the client (LinerViewSVA) and the dedicated server's self-test
 * share it. No Minecraft classes.
 * <ul>
 * <li><b>The hand</b> (item 1): a pilot of the liner sees no arm / held item in the first-person view (it covered the
 * lower ECAM) - hideHand(); passengers keep theirs.</li>
 * <li><b>The eye</b> (item 3): the pilots' eyes EYE_* in the cockpit frame (LinerControls: x forward, y up, z right;
 * VehicleType.LINER's seats put them there): in the model 0.07 forward, 0.05 higher and 0.02 inboard of wave 1's (6.88,
 * 2.70, +-0.40) - as far as the head's outer top corner stays inside the nose's skin - and the cockpit itself 0.07 lower
 * (CK_Y). With the lower glare shield and windscreen sill (LinerInterior) the line over the FCU's edge is 12 deg under
 * the horizon: the ground shows 12.6 blocks ahead - some 25 m at the model's half scale, the A320's 25..30 m (wave 1:
 * the glare shield stood over the eye - no ground ahead at all). The A320's own 20 deg would need the panels another
 * 0.15 lower - under the seat cushion; the head can't go higher in the nose's skin.</li>
 * <li><b>Quick views</b> (item 4): the presets below - the local camera turns (and zooms in a little) to a panel and
 * back, eased over EASE_S. Each preset is a point of the cockpit (its panel's own placement), so the F/O's are his
 * mirror image. It is a look only: the client adds it to the rider's rotation for the frame and takes it off after
 * (the liner is steered by keys, never by the look; the server sees the real look), the 3D click ray is built from the
 * frame's own matrices, so it follows the view.</li>
 * </ul>
 */
public final class LinerView {

    /** The pilots' eye in the cockpit frame (the captain at -EYE_Z, the F/O at +EYE_Z): model (6.95, 2.75, +-0.38). */
    public static final float EYE_X = 0.07F, EYE_Y = 0.04F, EYE_Z = 0.38F;
    /** Wave 1's rider eye in the MODEL (x, y, |z|) - the «before» of the reports (then 0.08 under the concept's origin). */
    public static final float OLD_EYE_X = 6.88F, OLD_EYE_Y = 2.70F, OLD_EYE_Z = 0.40F;

    /** Presets: forward (the own look, no offset), own PFD / ND, ECAM, overhead, pedestal / MCDU, left / right side window. */
    public static final int FWD = 0, PFD_ND = 1, ECAM = 2, OVHD = 3, PED = 4, WIN_L = 5, WIN_R = 6, PRESETS = 7;
    /** Lang keys: sva.liner.view.KEY. */
    public static final String[] KEYS = {"fwd", "pfd", "ecam", "ovhd", "ped", "winL", "winR"};
    /**
     * The FOV factor of each preset (1 = the player's own FOV): the PFD / ND closest - its digits readable - the ECAM a
     * little less (two DUs), the overhead / pedestal hardly (large panels), the windows none.
     */
    public static final float[] ZOOM = {1F, 0.62F, 0.72F, 0.95F, 0.85F, 1F, 1F};
    /**
     * Cockpit view (plan-next 8, item 6): the cockpit windows on the skin, (x, theta from the top) corners - the windscreen
     * half, the sliding window, the aft side window (LinerPaintSVA; liner3d.py's panes; wave 1: {8.32, 3} {7.96, 3} {7.86, 37} {8.22, 43} /
     * {7.8, 44} {7.45, 44} {7.42, 68} {7.82, 64} / {7.35, 47} {7.08, 47} {7.08, 66} {7.33, 66}). The posts between them
     * 0.04..0.05 (wave 1: 0.06..0.10).
     */
    public static final double[][][] PAINT_WINDOWS = {{{8.32, 3}, {7.96, 3}, {7.83, 53}, {8.20, 58}},
            {{7.79, 42}, {7.40, 43}, {7.37, 77}, {7.80, 74}}, {{7.33, 45}, {7.02, 46}, {7.02, 76}, {7.31, 76}}};
    /** The parked wiper painted on the windscreen (x0, theta0, x1, theta1) - over the OBJ's wiper (details group). */
    public static final double[] PAINT_WIPER = {8.16, 54, 8.27, 26};

    /** The DUs on the main panel (panel units x0 y0 x1 y1): PFD 1, ND 1, E/WD, SD, ND 2, PFD 2, ISIS (LinerPaintSVA.SCREENS). */
    public static final float[][] DU_RECTS = {{170, 20, 450, 300}, {465, 20, 745, 300}, {860, 20, 1140, 300}, {860, 320, 1140, 600},
            {1255, 20, 1535, 300}, {1550, 20, 1830, 300}, {754, 30, 852, 128}};
    /** The turn's duration (s), eased in and out. */
    public static final double EASE_S = 0.3;

    private LinerView() {
    }

    // ---------------------------------------------------------------- the hand

    /** Hide the first-person hand / held item: the option on, the liner, a pilot seat, the first-person view. */
    public static boolean hideHand(boolean option, boolean liner, boolean pilotSeat, boolean firstPerson) {
        return option && liner && pilotSeat && firstPerson;
    }

    // ---------------------------------------------------------------- the eye

    /** The pilot's eye (cockpit frame), the F/O's mirrored. */
    public static float[] eye(boolean fo) {
        return new float[] {EYE_X, EYE_Y, fo ? EYE_Z : -EYE_Z};
    }

    /** Wave 1's eye in the model (the reports' «before»). */
    public static float[] oldEyeModel(boolean fo) {
        return new float[] {OLD_EYE_X, OLD_EYE_Y, fo ? OLD_EYE_Z : -OLD_EYE_Z};
    }

    /** The eye's height over the ground (blocks; the liner on its gear: model y 0 = the ground). */
    public static double eyeHeight() {
        return LinerControls.CK_Y + EYE_Y;
    }

    // ---------------------------------------------------------------- the presets

    /** A preset's point (cockpit frame) - what the view centres on; null for FWD. */
    public static float[] target(int preset, boolean fo) {
        switch (preset) {
            case PFD_ND: {
                // between the own PFD and ND (LinerPaintSVA.SCREENS: the captain's 170..745, the F/O's mirrored), their middle
                float tx = fo ? LinerControls.PANEL_W[LinerControls.MP] - 457.5F : 457.5F;
                return LinerControls.point(LinerControls.MP, tx, 160, new float[3]);
            }
            case ECAM:
                return LinerControls.point(LinerControls.MP, 1000, 310, new float[3]);   // the E/WD over the SD: their seam
            case OVHD:
                return LinerControls.point(LinerControls.OVH, 900, 640, new float[3]);   // the middle of the overhead
            case PED:
                return LinerControls.point(LinerControls.PED, 380, 240, new float[3]);   // the two MCDUs on the sloping front
            case WIN_L:
                return LinerInterior.sideWindowCentre(-1);
            case WIN_R:
                return LinerInterior.sideWindowCentre(1);
            default:
                return null;
        }
    }

    /**
     * The own side window's look pitch (deg down): out through the window's lower half at the ground beside the nose (the
     * window spans about 40 deg over to 11 deg under the eye's level from the own seat). The far window (across the
     * cockpit, 1.3 away: its sill only 4.5 deg under the eye) is looked at in its middle.
     */
    public static final float WIN_PITCH = 4F;

    /**
     * A preset's look relative to the liner: {yaw deg (+ right, Minecraft's sense), pitch deg (+ down, Minecraft's sense)}
     * from the pilot's eye; FWD {0, 0}. The windows: toward the window's centre - the own one WIN_PITCH down.
     */
    public static float[] angles(int preset, boolean fo) {
        float[] t = target(preset, fo);
        if (t == null) {
            return new float[] {0F, 0F};
        }
        float[] a = look(eye(fo), t);
        if (preset == (fo ? WIN_R : WIN_L)) {
            a[1] = WIN_PITCH;
        }
        return a;
    }

    /** {yaw (+ right), pitch (+ down)} deg of point p seen from e (cockpit frame: x forward, y up, z right). */
    public static float[] look(float[] e, float[] p) {
        double dx = p[0] - e[0], dy = p[1] - e[1], dz = p[2] - e[2];
        return new float[] {(float) Math.toDegrees(Math.atan2(dz, dx)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
    }

    /** The next preset `n` steps on (the wheel; FWD is in the ring). */
    public static int step(int preset, int n) {
        return ((preset + n) % PRESETS + PRESETS) % PRESETS;
    }

    /** Smoothstep easing of t in 0..1 (clamped): slow out, slow in. */
    public static double ease(double t) {
        double x = t < 0 ? 0 : t > 1 ? 1 : t;
        return x * x * (3 - 2 * x);
    }

    /** -180..180. */
    public static float wrap(float a) {
        float r = a % 360F;
        if (r >= 180F) {
            r -= 360F;
        }
        if (r < -180F) {
            r += 360F;
        }
        return r;
    }

    /**
     * The key «Вид кабины» (VehicleFn.VIEW): a TAP toggles between forward and the last picked preset (first: the own PFD /
     * ND); HOLDING it and turning the wheel picks a preset live (wheel down = the next: forward, PFD / ND, ECAM, overhead,
     * pedestal, left, right window, forward...) and letting go keeps it. Tapping again (or picking «вперёд») comes back.
     */
    public static final class Picker {
        /** The preset shown now (FWD = the own look). */
        public int current = FWD;
        /** The preset a tap from forward goes to (the last one picked). */
        public int favourite = PFD_ND;
        private boolean held, pendingReturn;

        /** The key went down. */
        public void press() {
            held = true;
            if (current == FWD) {
                current = favourite;
                pendingReturn = false;
            } else {
                pendingReturn = true;                               // a tap: back to forward when it is let go
            }
        }

        /** Wheel notches while held (+ = next). @return used (the hotbar must not scroll) */
        public boolean wheel(int n) {
            if (!held || n == 0) {
                return false;
            }
            current = step(current, n);
            if (current != FWD) {
                favourite = current;
            }
            pendingReturn = false;
            return true;
        }

        /** The key was let go. */
        public void release() {
            if (held && pendingReturn) {
                current = FWD;
            }
            held = false;
            pendingReturn = false;
        }

        /** Out of the seat / view: forward, nothing held (the favourite stays). */
        public void reset() {
            current = FWD;
            held = false;
            pendingReturn = false;
        }

        public boolean held() {
            return held;
        }
    }

    /**
     * The view's offset eased from where it is to a new target: yaw / pitch (deg, added to the rider's look) and the FOV
     * factor. retarget() starts a new turn from the current value (a turn changed half-way stays smooth).
     */
    public static final class Blend {
        private float y0, p0, z0 = 1, y1, p1, z1 = 1;
        private double t = 1;

        public void retarget(float yaw, float pitch, float zoom) {
            float y = yaw(), p = pitch(), z = zoom();
            y0 = y;
            p0 = p;
            z0 = z;
            y1 = y + wrap(yaw - y);                                 // the short way round
            p1 = pitch;
            z1 = zoom;
            t = 0;
        }

        /** At once (left the seat / the view). */
        public void snap(float yaw, float pitch, float zoom) {
            y0 = y1 = yaw;
            p0 = p1 = pitch;
            z0 = z1 = zoom;
            t = 1;
        }

        public void advance(double dtSeconds) {
            t = Math.min(1, t + Math.max(0, dtSeconds) / EASE_S);
        }

        public float yaw() {
            return (float) (y0 + (y1 - y0) * ease(t));
        }

        public float pitch() {
            return (float) (p0 + (p1 - p0) * ease(t));
        }

        public float zoom() {
            return (float) (z0 + (z1 - z0) * ease(t));
        }

        public boolean done() {
            return t >= 1;
        }

        /** No offset and no zoom (or on its way there and finished). */
        public boolean idle() {
            return done() && Math.abs(y1) < 1e-4F && Math.abs(p1) < 1e-4F && Math.abs(z1 - 1) < 1e-4F;
        }
    }

    /**
     * The offset that turns the rider's real look (relYaw - his yaw minus the liner's, pitch) to `preset`: {yaw, pitch}.
     * FWD: none. The pitch stays within Minecraft's -90..90.
     */
    public static float[] offset(int preset, boolean fo, float relYaw, float pitch) {
        if (preset == FWD) {
            return new float[] {0F, 0F};
        }
        float[] a = angles(preset, fo);
        float tp = Math.max(-89F, Math.min(89F, a[1]));
        return new float[] {wrap(a[0] - relYaw), tp - pitch};
    }
}
