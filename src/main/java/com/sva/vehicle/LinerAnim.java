package com.sva.vehicle;

/**
 * Liner wave 1: the SA-320's client-side animation state - pure (the self-test runs it): what the shared AnimMath.State
 * doesn't have. Ticked by EntityVehicleSVA's client tick from the synced systems (LinerSystems mirror) and the motion;
 * client/LinerModelSVA poses the rig groups from it.
 * <ul>
 * <li>Gear: one progress p 0 (down) .. 1 (up) over GEAR_TICKS (10 s - the concept's «~10 s»): the doors open in the first
 * DOOR_PART, the legs travel between, the doors shut in the last DOOR_PART; extending runs the same path back. Wave 2: the
 * server owns the position (LinerGear: Green moves it, the gravity extension drops it with the doors open) - the client
 * follows the synced one smoothly; the flaps / slats likewise (tickHighLift).</li>
 * <li>Fans: each spins by its N1 (FAN_DEG at 100 % a tick); the L1 door swings 0..165 deg in DOOR_TICKS, the airstairs
 * unfold after it; the APU inlet flap follows the synced flap; the oleos extend in the air.</li>
 * </ul>
 */
public final class LinerAnim {

    public static final int GEAR_TICKS = 200, DOOR_TICKS = 50, STAIRS_TICKS = 30;
    public static final float DOOR_PART = 0.15F, FAN_DEG = 55F;
    /** Main / nose leg travel (deg, liner3d.py's limits), the doors' opening, the door / stairs angles. */
    public static final float MLG_DEG = 90, NLG_DEG = 92, GEAR_DOOR_DEG = 88, DOOR_DEG = 165, APU_FLAP_DEG = 35;
    /** The oleos' extension in the air (the rig's track limits: main 0.12, nose 0.1). */
    public static final float MLG_OLEO = 0.12F, NLG_OLEO = 0.1F;

    public float gear, prevGear, door, prevDoor, stairs, prevStairs, oleo, prevOleo;
    /** Wave 2: the flaps (setting units 0..4) and slats (0..1) following the synced positions. */
    public float flap, prevFlap, slat, prevSlat;
    /** Wave 2: the gear fell by gravity (the doors stay open, the legs without the sequence). */
    public boolean gravity;
    public float fanL, prevFanL, fanR, prevFanR;
    /** Wave 4: the reversers' translating cowls (0 stowed .. 1 deployed) following the synced positions. */
    public float revL, prevRevL, revR, prevRevR;
    /** The cowl's travel (the rig's reverser_* track limit, model units). */
    public static final float REV_TRAVEL = 0.34F;

    /** Wave 4, one client tick of the reversers toward the synced cowl positions (engine 1 left, 2 right). */
    public void tickReversers(float l, float r) {
        prevRevL = revL;
        prevRevR = revR;
        revL = step(revL, Math.max(0F, Math.min(1F, l)), 1.5F / com.sva.vehicle.LinerAfs.REV_TICKS);
        revR = step(revR, Math.max(0F, Math.min(1F, r)), 1.5F / com.sva.vehicle.LinerAfs.REV_TICKS);
    }
    public boolean inited;
    /** The level of detail drawn last (LinerTexMath.lod's hysteresis; -1 none yet). */
    public int lod = -1;

    /**
     * One client tick: the synced gear position (0 down .. 1 up) and its gravity extension, on the ground, the door wanted
     * open, the N1s (%), the height over the ground.
     */
    public void tick(float gearPos, boolean grav, boolean ground, boolean doorOpen, double n1L, double n1R, double height) {
        prevGear = gear;
        prevDoor = door;
        prevStairs = stairs;
        prevOleo = oleo;
        prevFanL = fanL;
        prevFanR = fanR;
        if (!inited) {
            gear = prevGear = gearPos;
            door = prevDoor = doorOpen ? 1 : 0;
            stairs = prevStairs = doorOpen && ground ? 1 : 0;
            inited = true;
        }
        gravity = grav;
        // the synced position moves at most 1/GEAR_TICKS a tick (gravity: 1/GRAV_TICKS); follow it a little faster so a
        // late packet glides
        gear = step(gear, Math.max(0F, Math.min(1F, gearPos)), (grav ? 1.5F / LinerGear.GRAV_TICKS : 1.5F / GEAR_TICKS));
        // the door first, the stairs after it (and in before it closes)
        boolean wantStairs = doorOpen && ground;
        if (doorOpen) {
            door = step(door, 1, 1F / DOOR_TICKS);
            if (door >= 1) {
                stairs = step(stairs, wantStairs ? 1 : 0, 1F / STAIRS_TICKS);
            }
        } else {
            stairs = step(stairs, 0, 1F / STAIRS_TICKS);
            if (stairs <= 0) {
                door = step(door, 0, 1F / DOOR_TICKS);
            }
        }
        oleo = step(oleo, height > 0.3 ? 1 : 0, 0.05F);
        fanL = (fanL + (float) (n1L / 100.0 * FAN_DEG)) % 360F;
        fanR = (fanR + (float) (n1R / 100.0 * FAN_DEG)) % 360F;
    }

    /** Wave 2, one client tick of the high lift: toward the synced flap (setting units) and slat positions. */
    public void tickHighLift(float flapPos, float slatPos) {
        prevFlap = flap;
        prevSlat = slat;
        if (!hlInited) {
            flap = prevFlap = flapPos;
            slat = prevSlat = slatPos;
            hlInited = true;
        }
        flap = step(flap, flapPos, (float) LinerFctl.FLAP_RATE * 1.5F);
        slat = step(slat, slatPos, (float) LinerFctl.SLAT_RATE * 1.5F);
    }

    private boolean hlInited;

    /** The legs' share of the gear's travel at progress p - by gravity straight (no door phases). */
    public static float legs(float p, boolean gravity) {
        return gravity ? Math.max(0F, Math.min(1F, p)) : legs(p);
    }

    /** The doors' opening at progress p - after a gravity extension they hang open. */
    public static float doors(float p, boolean gravity) {
        return gravity ? 1F : doors(p);
    }

    static float step(float v, float t, float by) {
        return v < t ? Math.min(t, v + by) : Math.max(t, v - by);
    }

    /** The legs' share of the gear's travel at progress p (0 down .. 1 up). */
    public static float legs(float p) {
        return Math.max(0F, Math.min(1F, (p - DOOR_PART) / (1 - 2 * DOOR_PART)));
    }

    /** The gear doors' opening at progress p: open while the legs travel, shut down and up. */
    public static float doors(float p) {
        if (p <= 0F || p >= 1F) {
            return 0F;
        }
        if (p < DOOR_PART) {
            return p / DOOR_PART;
        }
        if (p > 1 - DOOR_PART) {
            return (1 - p) / DOOR_PART;
        }
        return 1F;
    }

    public static float lerp(float a, float b, float pt) {
        return a + (b - a) * pt;
    }

    /** A fan angle between ticks (wrapping). */
    public static float fan(float prev, float now, float pt) {
        float d = now - prev;
        if (d < -180) {
            d += 360;
        }
        return prev + d * pt;
    }
}
