package com.sva.vehicle;

/**
 * Aircraft round 2, stage D3 (★12, mock-up docs/mockups/veh_air3_3_gear_nozzle.png, the top three rows): pure gear math -
 * the door / leg sequence and the oleo strut's spring-damper (server-safe: the self-test SelfTestAirD3SVA runs it; the
 * client keeps the state in AnimMath.State and RenderVehicleSVA poses the AnimParts rows by it).
 * <ul>
 * <li>Sequence (the jet, the tiltrotor; the shuttle's legs have no doors): the doors stay open while the gear is down (the
 * parked look of the mock-up's frame 1); retracting, the doors open first if they are not (DOOR_TICKS), the legs swing aft
 * (GEAR_TICKS = 2 s, frame 2), then the doors shut flush (frame 3); extending, the doors open, then the legs come down.
 * A reversal half way just runs the same rules back. vehicles.gearDoors off: the doors follow the legs at once.</li>
 * <li>Strut: compression 0 (extended, in the air) .. 1 (bottomed); on the wheels it settles at REST (40 %, the mock-up's
 * «стоянка»). A spring-damper per tick (K, DAMP); the touchdown gives the strut a push proportional to the sink rate
 * just before the contact (IMPACT) - 3 m/s (0.15 b/t) peaks at ~85 % and comes back through ~25 % to 40 % in a second
 * (the mock-up's graph: one or two damped rebounds); while rolling, a sudden rise of the vertical speed (a bump) kicks it
 * (BUMP). The legs' stroke (model units, AnimParts) turns compression into the piston's travel and the airframe's sink.</li>
 * <li>Torque links: two links of length l between hinges d apart fold out to acos(d / 2 l) off the leg line;
 * d = d0 - (c - REST) x stroke (d0 measured at the parking stroke, veh_real11.py).</li>
 * <li>The light plane's fixed gear (no retraction - the mock-up keeps the spats): its spring legs swing out-and-up about
 * their root by FLEX_DEG per unit of compression over REST (flexRise: the wheel's rise for the airframe's sink).</li>
 * </ul>
 */
public final class GearMath {

    /** The parking stroke (compression 0..1). */
    public static final float REST = 0.4F;
    /** The doors' travel and the legs' travel (ticks). */
    public static final int DOOR_TICKS = 10, GEAR_TICKS = 40;
    /** Spring-damper per tick: stiffness, damping, the touchdown push per b/t of sink, the bump push per b/t of change. */
    public static final float K = 0.35F, DAMP = 0.3F, IMPACT = 3.0F, BUMP = 2.0F;
    /** A change of the vertical speed below this (b/t) is no bump. */
    public static final float BUMP_MIN = 0.03F;
    /** The light plane's spring legs: degrees about the root per unit of compression. */
    public static final float FLEX_DEG = 14F;
    /** The wheels touch the ground under this height (blocks). */
    public static final double CONTACT = 0.1;

    private GearMath() {
    }

    /** The types whose legs retract (doors on the jet and the tiltrotor; the shuttle's legs draw up). */
    public static boolean retracts(VehicleType t) {
        return t == VehicleType.JET || t == VehicleType.TILT || t == VehicleType.SHUTTLE;
    }

    /** The types with oleo struts / spring legs (the light plane: flexing legs). */
    public static boolean struts(VehicleType t) {
        return t == VehicleType.JET || t == VehicleType.TILT || t == VehicleType.PLANE;
    }

    /**
     * One tick of the gear and its doors (s.gear 0 down .. 1 up, s.door 0 closed .. 1 open): up = the legs wanted in;
     * doors false (config off, or a type without doors): the legs travel at once and the doors follow them.
     */
    public static void sequence(AnimMath.State s, boolean up, boolean doors) {
        s.prevDoor = s.door;
        float g = s.gear, d = s.door;
        if (!doors) {
            g = step(g, up ? 1F : 0F, 1F / GEAR_TICKS);
            d = g >= 1F ? 0F : 1F;
        } else if (up) {
            if (g < 1F) {
                if (d < 1F) {
                    d = step(d, 1F, 1F / DOOR_TICKS);              // open first
                } else {
                    g = step(g, 1F, 1F / GEAR_TICKS);
                }
            } else {
                d = step(d, 0F, 1F / DOOR_TICKS);                  // in: shut flush
            }
        } else {
            if (d < 1F) {
                d = step(d, 1F, 1F / DOOR_TICKS);
            } else {
                g = step(g, 0F, 1F / GEAR_TICKS);                  // down; the doors stay open
            }
        }
        s.gear = g;
        s.door = d;
    }

    /** The phase a gear / door pair is in (self-test, the world test): 0 down, 1 doors opening up, 2 legs up, 3 doors shutting, 4 up. */
    public static int phase(float gear, float door, boolean up) {
        if (gear <= 0F && !up) {
            return 0;
        }
        if (gear >= 1F && door <= 0F) {
            return 4;
        }
        if (gear >= 1F) {
            return 3;
        }
        return door < 1F ? 1 : 2;
    }

    /**
     * One tick of the strut: contact = the wheels on the ground; dy = this tick's vertical motion (b/t); animate off (or
     * vehicles.gearStruts off) = at REST on the ground, extended in the air.
     */
    public static void strut(AnimMath.State s, boolean contact, float dy, boolean animate) {
        s.prevStrut = s.strut;
        if (!animate) {
            s.strut = s.prevStrut = contact ? REST : 0F;
            s.strutV = 0F;
        } else {
            if (contact && !s.contact) {
                s.strutV += IMPACT * Math.max(0F, -Math.min(s.strutDy, dy));            // the sink just before
            } else if (contact) {
                s.strutV += bump(s.strutDy, dy);
            }
            s.strutV += K * ((contact ? REST : 0F) - s.strut) - DAMP * s.strutV;
            s.strut += s.strutV;
            if (s.strut > 1F) {
                s.strut = 1F;                                     // bottomed out
                s.strutV = Math.min(0F, s.strutV);
            } else if (s.strut < 0F) {
                s.strut = 0F;
                s.strutV = Math.max(0F, s.strutV);
            }
        }
        s.strutDy = dy;
        s.contact = contact;
    }

    /** A bump's push: a sudden rise of the vertical speed by more than BUMP_MIN (a step, a kerb) compresses the strut. */
    public static float bump(float lastDy, float dy) {
        float d = dy - lastDy;
        return d > BUMP_MIN ? Math.min(0.2F, d * BUMP) : 0F;
    }

    /** The piston's (and wheel's) travel up the leg at compression c (model units, stroke = the leg's full stroke). */
    public static float travel(float c, float stroke) {
        return (c - REST) * stroke;
    }

    /** The airframe's sink on the ground (model units): the struts take what is over the parking stroke. */
    public static float sink(float c, float stroke) {
        return Math.max(0F, c - REST) * stroke;
    }

    /** The main legs' stroke (model units, veh_real11.py): the jet 1.2 / 16, the tiltrotor 0.8 / 16. */
    public static final float STROKE_JET = 0.075F, STROKE_TILT = 0.05F;
    /** The light plane's spring leg: its root (y, |z|) and axle (y, |z|) - AnimParts' flex rows. */
    public static final float PLANE_ROOT_Y = 0.457F, PLANE_ROOT_Z = 0.17F, PLANE_AXLE_Y = 0.144F, PLANE_AXLE_Z = 0.375F;

    /** The airframe's sink for a type at compression c (model units): the main legs' stroke, the plane's flexing legs. */
    public static float sink(VehicleType t, float c) {
        if (t == VehicleType.JET) {
            return sink(c, STROKE_JET);
        }
        if (t == VehicleType.TILT) {
            return sink(c, STROKE_TILT);
        }
        if (t == VehicleType.PLANE && c > REST) {
            return flexRise(PLANE_ROOT_Y, PLANE_ROOT_Z, PLANE_AXLE_Y, PLANE_AXLE_Z, flex(c));
        }
        return 0F;
    }

    /** A torque link's angle off the leg line (deg) at compression c: hinges d0 apart at REST, links `len` long. */
    public static float linkAngle(float d0, float len, float stroke, float c) {
        float d = d0 - travel(c, stroke);
        return (float) Math.toDegrees(Math.acos(Math.max(-1F, Math.min(1F, d / (2F * len)))));
    }

    /** The upper link's turn from the modelled pose (deg about +z, + = its joint forward); the lower link turns the other way. */
    public static float linkTurn(float d0, float len, float stroke, float c) {
        return linkAngle(d0, len, stroke, c) - linkAngle(d0, len, stroke, REST);
    }

    /** The light plane: a spring leg's swing about its root (deg about +x for the right leg; the left one mirrored). */
    public static float flex(float c) {
        return -FLEX_DEG * (c - REST);
    }

    /**
     * The wheel's rise (model units) when a spring leg from root (y, |z|) to the axle swings by `deg` out-and-up: the
     * airframe sinks by it (RenderVehicleSVA).
     */
    public static float flexRise(float rootY, float rootZ, float axleY, float axleZ, float deg) {
        double a = Math.toRadians(Math.abs(deg)), dy = axleY - rootY, dz = Math.abs(axleZ) - Math.abs(rootZ);
        return (float) (dy * Math.cos(a) + dz * Math.sin(a) - dy);
    }

    /** v one step of `by` toward target (snapped onto it within 1e-4: forty steps of 1/40 end exactly there). */
    static float step(float v, float target, float by) {
        float n = v < target ? Math.min(target, v + by) : Math.max(target, v - by);
        return Math.abs(n - target) < 1e-4F ? target : n;
    }
}
