package com.sva.airdefense;

/**
 * Air-defence multiblocks (mock-ups veh_ad_2 / 3 / 4, the user's decision 2026-10-12: the blocks are as big as drawn):
 * the pure part - each kind's footprint, the facing, the cell <-> world offset math, the legacy decision for the single
 * blocks of older worlds and the animation angles the renderer uses. No Minecraft classes (the self-test runs it).
 *
 * Local cells: f = rows from the front row back (0, -1, -2), u = layers up, r = columns to the right of the front (seen
 * from behind the front, i.e. by somebody facing the same way as the front). The master block is always f 0 u 0 r 0, so a
 * block placed by the player stays where it was clicked and the structure grows away from him. The model space of
 * client/AdModelSVA (ad_models.py) is x forward (the front), y up, z right, the origin the footprint's centre.
 *
 * Facing = the way the front looks: 0 south (+z), 1 west (-x), 2 north (-z), 3 east (+x) - the player's own horizontal
 * direction numbering, so the front of a block placed by a player looking north (2) looks south (0), at him.
 */
public final class AdShapeMath {

    /** Shapes (the models): the three radars, the AA gun, the missile system, the command post. */
    public static final int SURVEY = 0, TRACK = 1, HEIGHT = 2, GUN = 3, MISSILE = 4, KP = 5, SHAPES = 6;
    public static final String[] MODEL = {"ad_survey", "ad_track", "ad_height", "ad_gun", "ad_missile", "ad_kp"};
    /** Cell collision: FULL a whole block, PAD the concrete pad only (2 / 16 high, walked over). */
    public static final int FULL = 0, PAD = 1;
    public static final float PAD_HEIGHT = 2F / 16F;
    /** Footprint across the front (W, columns r) and back from it (D, rows f), in blocks. */
    public static final int[] W = {3, 2, 2, 2, 3, 2}, D = {3, 2, 2, 2, 3, 1};

    /**
     * Each shape's cells {f, u, r, type}, the master first. Measured from ad_models.py: the survey radar's mast stands in the
     * middle cell 3 blocks high (2.9 to the platform), the tracking radar's pedestal + dish (to y 2.3) and the height
     * finder's antenna (to 3.2) in the back right cell, the gun's ring mount spreads over all four, the missile system's
     * turntable in the middle (the tube box to y 1.6), the command post's desk and screens over both cells (to y 1.9).
     */
    private static final int[][][] CELLS = {
        {{0, 0, 0, FULL}, {0, 0, -1, PAD}, {0, 0, 1, PAD}, {-1, 0, -1, PAD}, {-1, 0, 0, FULL}, {-1, 1, 0, FULL}, {-1, 2, 0, FULL},
            {-1, 0, 1, PAD}, {-2, 0, -1, PAD}, {-2, 0, 0, PAD}, {-2, 0, 1, PAD}},
        {{0, 0, 0, FULL}, {0, 0, 1, PAD}, {-1, 0, 0, PAD}, {-1, 0, 1, FULL}, {-1, 1, 1, FULL}},
        {{0, 0, 0, FULL}, {0, 0, 1, PAD}, {-1, 0, 0, PAD}, {-1, 0, 1, FULL}, {-1, 1, 1, FULL}, {-1, 2, 1, FULL}},
        {{0, 0, 0, FULL}, {0, 0, 1, FULL}, {-1, 0, 0, FULL}, {-1, 0, 1, FULL}},
        {{0, 0, 0, FULL}, {0, 0, -1, PAD}, {0, 0, 1, PAD}, {-1, 0, -1, PAD}, {-1, 0, 0, FULL}, {-1, 1, 0, FULL}, {-1, 0, 1, PAD},
            {-2, 0, -1, PAD}, {-2, 0, 0, PAD}, {-2, 0, 1, PAD}},
        {{0, 0, 0, FULL}, {0, 1, 0, FULL}, {0, 0, 1, FULL}, {0, 1, 1, FULL}},
    };

    /** The surveillance radar's turn (RadarMath.INTERVAL[SURVEY] = 120 ticks a sweep) and the height finder's nod. */
    public static final int SWEEP_TICKS = 120, NOD_TICKS = 60;
    public static final float NOD_DEG = 20F;
    /** Slew rates, degrees a tick: the gun's mount 120 deg/s (mock-up *3 «погон: поворот 120°/с»), the others slower. */
    public static final float GUN_YAW_RATE = 6F, GUN_PITCH_RATE = 4F, SAM_YAW_RATE = 3F, SAM_PITCH_RATE = 2F, DISH_YAW_RATE = 9F,
            DISH_PITCH_RATE = 6F;
    /** Rest poses (no target): the elevation of the dish / the barrels / the tube box. */
    public static final float REST_PITCH_TRACK = 12F, REST_PITCH_GUN = 8F, REST_PITCH_SAM = 25F;
    /** Elevation limits: the gun, the missile box (it never lays its tubes flat), the dish. */
    public static final float MIN_PITCH_GUN = -2F, MAX_PITCH_GUN = 80F, MIN_PITCH_SAM = 10F, MAX_PITCH_SAM = 65F, MIN_PITCH_DISH = -5F,
            MAX_PITCH_DISH = 85F;
    /** The gun's recoil (blocks the barrels slide back) and its length in ticks; the muzzle flash's ticks. */
    public static final float RECOIL = 0.18F;
    public static final int RECOIL_TICKS = 4, FLASH_TICKS = 2;
    /** Legacy single blocks (see legacy()). */
    public static final int WAIT = 0, CONVERT = 1, COMPACT = 2;
    /** The tubes of the missile system. */
    public static final int TUBES = 4;

    private AdShapeMath() {
    }

    /** The shape of a block: BlockAirDefenseSVA kind (0 radar, 1 launcher, 2 command post) + meta. */
    public static int shape(int blockKind, int meta) {
        if (blockKind == 0) {
            return meta == RadarMath.TRACK ? TRACK : meta == RadarMath.HEIGHT ? HEIGHT : SURVEY;
        }
        if (blockKind == 1) {
            return meta == 1 ? MISSILE : GUN;
        }
        return KP;
    }

    public static int[][] cells(int shape) {
        return CELLS[shape < 0 || shape >= SHAPES ? 0 : shape];
    }

    /** The front's unit vector {x, z}. */
    public static int fx(int facing) {
        int f = facing & 3;
        return f == 1 ? -1 : f == 3 ? 1 : 0;
    }

    public static int fz(int facing) {
        int f = facing & 3;
        return f == 0 ? 1 : f == 2 ? -1 : 0;
    }

    /** A local cell offset (f rows forward, r columns right) -> {dx, dz} in the world. Right = (-fz, fx). */
    public static int[] toWorld(int facing, int f, int r) {
        int ax = fx(facing), az = fz(facing);
        return new int[] {f * ax - r * az, f * az + r * ax};
    }

    /** The inverse: a world offset -> {f, r}. */
    public static int[] toLocal(int facing, int dx, int dz) {
        int ax = fx(facing), az = fz(facing);
        return new int[] {dx * ax + dz * az, -dx * az + dz * ax};
    }

    /** Every cell's world offset from the master {dx, dy, dz, type}. */
    public static int[][] worldCells(int shape, int facing) {
        int[][] c = cells(shape);
        int[][] out = new int[c.length][];
        for (int i = 0; i < c.length; i++) {
            int[] w = toWorld(facing, c[i][0], c[i][2]);
            out[i] = new int[] {w[0], c[i][1], w[1], c[i][3]};
        }
        return out;
    }

    /** The highest cell layer (render box). */
    public static int layers(int shape) {
        int n = 0;
        for (int[] c : cells(shape)) {
            n = Math.max(n, c[1] + 1);
        }
        return n;
    }

    /** Facing of a block placed by a player turned `yaw`: the front looks back at him. */
    public static int facingFromYaw(float yaw) {
        int look = (int) Math.floor(yaw * 4.0F / 360.0F + 0.5D) & 3;
        return (look + 2) & 3;
    }

    /** The command post's old meta (its screen's side, 2..5 = N S W E) -> facing. */
    public static int facingFromSide(int side) {
        return side == 2 ? 2 : side == 4 ? 1 : side == 5 ? 3 : 0;
    }

    /** ... and back (the post keeps its meta = the screen's side for the old code paths). */
    public static int sideFromFacing(int facing) {
        int f = facing & 3;
        return f == 2 ? 2 : f == 1 ? 4 : f == 3 ? 5 : 3;
    }

    /** The GL turn about y of the model (x forward) to face `facing`: x -> (fx, fz). */
    public static float modelYaw(int facing) {
        return (float) Math.toDegrees(Math.atan2(-fz(facing), fx(facing)));
    }

    /**
     * The model origin (the footprint's centre on the ground) from the master block's centre, {dx, dz} in blocks: the
     * centre cell is f -(D-1)/2, r between the outer columns.
     */
    public static double[] centre(int shape, int facing) {
        int[][] c = cells(shape);
        int rmin = 0, rmax = 0, fmin = 0;
        for (int[] k : c) {
            rmin = Math.min(rmin, k[2]);
            rmax = Math.max(rmax, k[2]);
            fmin = Math.min(fmin, k[0]);
        }
        double f = fmin / 2.0, r = (rmin + rmax) / 2.0;
        int ax = fx(facing), az = fz(facing);
        return new double[] {f * ax - r * az, f * az + r * ax};
    }

    /**
     * A legacy single block (placed before the multiblocks): CONVERT when every other cell is loaded and free, WAIT while a
     * chunk of it isn't loaded (decide later), else COMPACT - it stays a 1x1 master, drawn as its model scaled into the
     * block, working as before.
     */
    public static int legacy(boolean allLoaded, boolean allFree) {
        if (!allLoaded) {
            return WAIT;
        }
        return allFree ? CONVERT : COMPACT;
    }

    /** legacyAll(): no facing fits yet but a chunk is missing / none ever fits. */
    public static final int KEEP_WAITING = -1, KEEP_COMPACT = -2;

    /**
     * The four facings' legacy() decisions (tried in that order) -> the index of the first that converts; else KEEP_WAITING
     * while any of them still waits for a chunk, else KEEP_COMPACT.
     */
    public static int legacyAll(int[] d) {
        boolean wait = false;
        for (int i = 0; i < d.length; i++) {
            if (d[i] == CONVERT) {
                return i;
            }
            wait |= d[i] == WAIT;
        }
        return wait ? KEEP_WAITING : KEEP_COMPACT;
    }

    private static final float[] DRIVE_GUN = {GUN_YAW_RATE, GUN_PITCH_RATE, REST_PITCH_GUN, MIN_PITCH_GUN, MAX_PITCH_GUN},
            DRIVE_SAM = {SAM_YAW_RATE, SAM_PITCH_RATE, REST_PITCH_SAM, MIN_PITCH_SAM, MAX_PITCH_SAM},
            DRIVE_DISH = {DISH_YAW_RATE, DISH_PITCH_RATE, REST_PITCH_TRACK, MIN_PITCH_DISH, MAX_PITCH_DISH};

    /** A turning mount's drive: {yaw rate, pitch rate, rest pitch, min pitch, max pitch} (degrees, a tick; shared - read only). */
    public static float[] drive(int shape) {
        return shape == GUN ? DRIVE_GUN : shape == MISSILE ? DRIVE_SAM : DRIVE_DISH;
    }

    /**
     * Where a radar looks from, blocks above its master (the beam's start, RadarMath.losClear): 1.5 as before, the survey
     * radar's reflector on its 3-block mast at 3.9 once it is a multiblock (mock-up *2: «ставь РЛС повыше - тень меньше»).
     */
    public static double antennaHeight(int shape, boolean multi) {
        return multi && shape == SURVEY ? 3.9 : 1.5;
    }

    /** The scale the whole model is drawn at in a compact (1x1) block: its larger footprint side into 1 block. */
    public static float compactScale(int shape) {
        int s = Math.max(W[shape < 0 || shape >= SHAPES ? 0 : shape], D[shape < 0 || shape >= SHAPES ? 0 : shape]);
        return 1F / s;
    }

    // ------------------------------------------------------------------ animation angles (degrees)

    public static float wrap180(float a) {
        a %= 360F;
        if (a >= 180F) {
            a -= 360F;
        }
        if (a < -180F) {
            a += 360F;
        }
        return a;
    }

    /** One step of `cur` toward `target` by at most `rate` (the short way round). */
    public static float approach(float cur, float target, float rate) {
        float d = wrap180(target - cur);
        if (Math.abs(d) <= rate) {
            return target;
        }
        return cur + (d > 0 ? rate : -rate);
    }

    /** The angle between two ticks' values (partialTicks), the short way round. */
    public static float lerpAngle(float prev, float cur, float pt) {
        return prev + wrap180(cur - prev) * pt;
    }

    /** The surveillance antenna's step a tick: a full turn per sweep. */
    public static float sweepStep() {
        return 360F / SWEEP_TICKS;
    }

    /** The height finder's nod at `ticks` (+-NOD_DEG, a full nod per NOD_TICKS - its scan interval). */
    public static float nod(double ticks) {
        return (float) (NOD_DEG * Math.sin(ticks * 2 * Math.PI / NOD_TICKS));
    }

    /**
     * A world direction {dx, dy, dz} -> the model's local {yaw, pitch}: yaw the GL turn about y (0 = the front, the model's
     * x), pitch up from the horizon.
     */
    public static float[] aim(int facing, double dx, double dy, double dz) {
        int ax = fx(facing), az = fz(facing);
        double lx = dx * ax + dz * az, lz = -dx * az + dz * ax;
        double h = Math.sqrt(lx * lx + lz * lz);
        float yaw = (float) Math.toDegrees(Math.atan2(-lz, lx));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, h));
        return new float[] {yaw, pitch};
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** The barrels' recoil (blocks back) `t` ticks after a burst: all the way back at once, home over RECOIL_TICKS. */
    public static float recoil(float t) {
        if (t < 0 || t >= RECOIL_TICKS) {
            return 0F;
        }
        return RECOIL * (1F - t / RECOIL_TICKS);
    }

    /**
     * Missiles to show on the rails: one per tube while ammunition lasts, a tube fired less than `reload` ticks ago stays
     * empty (the reload), so a launch takes one away and the reload puts it back while the stock lasts.
     */
    public static int visibleMissiles(int ammo, int reloading) {
        return Math.max(0, Math.min(TUBES, ammo) - Math.max(0, reloading));
    }

    /** The alarm lamp blinks (on half of each second). */
    public static boolean blink(long ticks) {
        return (ticks / 10) % 2 == 0;
    }
}
