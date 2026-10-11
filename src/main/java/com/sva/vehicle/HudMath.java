package com.sva.vehicle;

import java.util.Locale;

/**
 * Aircraft round 2, stage D3 (★18, mock-up docs/mockups/veh_air3_4_cockpit.png, the HUD half): the jet's head-up display,
 * pure (SelfTestAirD3SVA). The symbols lie on a plane square to the airframe's axis DIST model units in front of the
 * pilot's eye (client/HudJetSVA draws them in the model frame - the HUD turns with the airframe, not the look: looking
 * right the symbols slide to the canopy's edge and are cut by its frame) and only inside the canopy's glass (the
 * VehicleType.cabWindows box the plane crosses: GL clip planes, as the cockpit view's window cut).
 * Angles map to the plane conformally: a symbol `a` degrees off the boresight sits DIST x tan(a) from its centre - the
 * pitch ladder's rungs (every 5 deg, the horizon at -pitch, turned by -bank), the heading tape (true degrees across), the
 * flight-path vector (where the aircraft goes: the velocity's climb and drift angles against the airframe's pitch).
 * The speed / altitude tapes are scales of SPEED_DEG / ALT_DEG degrees per unit. Model units: x forward, y up, z right.
 */
public final class HudMath {

    /** The HUD plane's distance ahead of the eye (model units). */
    public static final float DIST = 0.45F;
    /** Tapes: degrees per km/h and per block; the tapes' half height (deg); the pitch ladder's step and extent (deg). */
    public static final float SPEED_DEG = 0.2F, ALT_DEG = 0.2F, TAPE_HALF = 9F, LADDER_STEP = 5F, LADDER_MAX = 30F;
    /** Where things sit (deg off the boresight): the tapes left / right, the heading tape, the text rows below. */
    public static final float SPEED_X = -16F, ALT_X = 16F, HDG_Y = 11.5F, ROW1_Y = -10.5F, ROW2_Y = -12.5F;
    /** The FPV needs this much forward speed (b/t) to mean anything. */
    public static final double FPV_MIN = 0.05;
    /** The speed digits blink over this (km/h; the mod's speeds stop near 180). */
    public static final int SPEED_BLINK = 170;
    /** The stall warning under this share over the stall speed. */
    public static final double STALL_MARGIN = 1.1;
    /** Night brightness (day 1). */
    public static final float NIGHT_BRIGHT = 0.55F;
    /** Distance kinds: none, the runway (ЗАХВ's capture), a flight-plan waypoint (stage D4). */
    public static final int T_NONE = 0, T_RWY = 1, T_WPT = 2;

    private HudMath() {
    }

    /** A position on the HUD plane (model units from its centre) for an angle (deg) off the boresight. */
    public static float at(double deg) {
        return (float) (DIST * Math.tan(Math.toRadians(Math.max(-80, Math.min(80, deg)))));
    }

    /** A pitch ladder rung for `rungDeg` (0 = the horizon) at airframe pitch `pitchDeg` (nose up +): up the ladder's axis. */
    public static float ladderY(float rungDeg, float pitchDeg) {
        return at(rungDeg - pitchDeg);
    }

    /** The rungs on view (deg, multiples of LADDER_STEP within LADDER_MAX of the horizon and TAPE_HALF of the boresight - clear of the heading tape and the text rows). */
    public static int[] rungs(float pitchDeg) {
        int lo = (int) Math.ceil((pitchDeg - TAPE_HALF) / LADDER_STEP), hi = (int) Math.floor((pitchDeg + TAPE_HALF) / LADDER_STEP);
        lo = Math.max(lo, (int) (-LADDER_MAX / LADDER_STEP));
        hi = Math.min(hi, (int) (LADDER_MAX / LADDER_STEP));
        if (hi < lo) {
            return new int[0];
        }
        int[] r = new int[hi - lo + 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = (int) ((lo + i) * LADDER_STEP);
        }
        return r;
    }

    /**
     * The flight-path vector on the HUD plane {x (+ right), y (+ up)} from this tick's motion along the facing (fwd), to
     * the right (side) and up (b/t) at airframe pitch `pitchDeg`; null when it hardly moves (FPV_MIN).
     */
    public static float[] fpv(double fwd, double side, double up, float pitchDeg) {
        if (fwd < FPV_MIN) {
            return null;
        }
        double drift = Math.toDegrees(Math.atan2(side, fwd)), climb = Math.toDegrees(Math.atan2(up, fwd));
        return new float[] {at(drift), at(climb - pitchDeg)};
    }

    /** The motion along the facing / to the right of it for a world move (dx, dz) at yaw (Minecraft degrees). */
    public static double[] body(double dx, double dz, float yaw) {
        double r = Math.toRadians(yaw);
        double fwd = dx * -Math.sin(r) + dz * Math.cos(r);
        double right = dx * -Math.cos(r) + dz * -Math.sin(r);
        return new double[] {fwd, right};
    }

    /** A heading mark `markDeg` (compass) across the heading tape at heading `hdgDeg`: x on the plane, NaN off the tape's +-25 deg. */
    public static float headingX(double markDeg, double hdgDeg) {
        double d = CruiseMath.wrap(markDeg - hdgDeg);
        return Math.abs(d) > 25 ? Float.NaN : at(d);
    }

    /** A tape mark `value` with the tape at `current`, degPerUnit: y on the plane, NaN outside the tape. */
    public static float tapeY(double value, double current, float degPerUnit) {
        double a = (value - current) * degPerUnit;
        return Math.abs(a) > TAPE_HALF ? Float.NaN : at(a);
    }

    /** The HUD plane's x (model units) for the eye. */
    public static float planeX(double[] eye) {
        return (float) eye[0] + DIST;
    }

    /**
     * Does the window box {x0, x1, y0, y1, z0, z1} hold the HUD: the plane's x inside its x range and the boresight in
     * its y / z (the HUD is then drawn clipped to the box's y / z faces).
     */
    public static boolean holds(float[] box, double[] eye) {
        float x = planeX(eye);
        return x > box[0] && x < box[1] && eye[1] > box[2] && eye[1] < box[3] && eye[2] > box[4] && eye[2] < box[5];
    }

    /** The first window box that holds the HUD, or null. */
    public static float[] glass(float[][] windows, double[] eye) {
        if (windows == null) {
            return null;
        }
        for (float[] b : windows) {
            if (holds(b, eye)) {
                return b;
            }
        }
        return null;
    }

    /**
     * The four clip planes (model space a x + b y + c z + d >= 0 keeps a point) of the box's y / z faces - the HUD is
     * kept inside the glass.
     */
    public static double[][] clipPlanes(float[] box) {
        return new double[][] {{0, 1, 0, -box[2]}, {0, -1, 0, box[3]}, {0, 0, 1, -box[4]}, {0, 0, -1, box[5]}};
    }

    /** Is a model point kept by clipPlanes(box)? (self-test) */
    public static boolean kept(double[][] planes, double x, double y, double z) {
        for (double[] p : planes) {
            if (p[0] * x + p[1] * y + p[2] * z + p[3] < 0) {
                return false;
            }
        }
        return true;
    }

    /** The angle off the boresight (deg) of the box's y / z faces as seen on the HUD plane: {down, up, left, right}. */
    public static float[] reach(float[] box, double[] eye) {
        return new float[] {deg(box[2] - eye[1]), deg(box[3] - eye[1]), deg(box[4] - eye[2]), deg(box[5] - eye[2])};
    }

    private static float deg(double off) {
        return (float) Math.toDegrees(Math.atan2(off, DIST));
    }

    /** A distance target: kind (T_NONE / T_RWY / T_WPT), the runway's designator or the waypoint's number, blocks. */
    public static final class Target {
        public final int kind;
        public final String label;
        public final double dist;

        public Target(int kind, String label, double dist) {
            this.kind = kind;
            this.label = label;
            this.dist = dist;
        }
    }

    /**
     * What the distance line shows: a flight-plan waypoint (stage D4: {x, z, number}, null now) before the runway ЗАХВ
     * flies to (its designator, the distance to its threshold - along < 0 before it); NONE without either.
     */
    public static Target target(double[] waypoint, double px, double pz, String rwy, double rwyAlong) {
        if (waypoint != null) {
            double dx = waypoint[0] - px, dz = waypoint[1] - pz;
            return new Target(T_WPT, String.valueOf((int) waypoint[2]), Math.sqrt(dx * dx + dz * dz));
        }
        if (rwy != null && !Double.isNaN(rwyAlong)) {
            return new Target(T_RWY, rwy, Math.max(0, -rwyAlong));
        }
        return new Target(T_NONE, "", 0);
    }

    /** A distance in blocks (= metres) as {number, unit key}: under 1000 whole metres, else km with one decimal (comma). */
    public static String[] distance(double blocks) {
        if (blocks < 1000) {
            return new String[] {String.valueOf((int) Math.round(blocks)), "sva.jethud.m"};
        }
        return new String[] {String.format(Locale.ROOT, "%.1f", blocks / 1000).replace('.', ','), "sva.jethud.km"};
    }

    /** The stall warning: airborne and slower than STALL_MARGIN x the stall speed (or stalling). */
    public static boolean stallWarn(boolean airborne, boolean stalling, double airspeed, double stallSpeed) {
        return airborne && (stalling || stallSpeed > 0 && airspeed < stallSpeed * STALL_MARGIN);
    }

    /** The symbols' brightness for the sky's darkness 0 (day) .. 1 (night). */
    public static float brightness(float darkness) {
        float d = Math.max(0F, Math.min(1F, darkness));
        return 1F - (1F - NIGHT_BRIGHT) * d;
    }

    /** The load factor shown («G»): 1 / cos(bank) in a level turn (the mod has no other load), 1.0 .. 3.0. */
    public static float g(float bankDeg) {
        double c = Math.cos(Math.toRadians(Math.min(70, Math.abs(bankDeg))));
        return (float) Math.min(3.0, 1.0 / c);
    }
}
