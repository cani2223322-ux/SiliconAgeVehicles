package com.sva.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★6 / ★7: where each model's lights are (model units: x forward, y up, z RIGHT - so the red nav light sits at z < 0,
 * the left side, the green one at z > 0; export_obj.py). Pure data, server-safe (the self-test checks the table is
 * complete and every light sits on its model). Positions were measured from the OBJ: the existing emissive lamp /
 * tail / red / green / amber groups where the model has them, else the extremes of the hull (wing tips, fin tops,
 * belly) like the mock's nav_lights(). The client (client/VehicleLightsSVA) draws them only while the vehicle has
 * charge and is not a wreck.
 */
public final class VehicleLights {

    /**
     * HEAD: a headlight / landing light - the light cone (★6) points forward, `pitch` deg down; RED / GREEN: steady
     * nav lights (left / right); STROBE: white, one short flash a second; BEACON: red, rotating (underneath); AMBER:
     * amber rotating beacon (work vehicles' roofs); MARKER: steady amber side marker; BRAKE: a brake light's glow (the
     * model's red 'tail' lamps - brighter while braking); WHITE: steady white tail light.
     */
    public static final int HEAD = 0, RED = 1, GREEN = 2, STROBE = 3, BEACON = 4, AMBER = 5, MARKER = 6, BRAKE = 7, WHITE = 8;
    /** Flags: only with a trailer hitched; only while the gear is down; the model has no lamp there - draw a small lamp disc. */
    public static final int F_TRAILER = 1, F_GEAR = 2, F_DISC = 4;

    public static final class Light {
        public final int kind, flags;
        public final float x, y, z;
        /** HEAD: the beam's downward tilt (deg). */
        public final float pitch;

        Light(int kind, float x, float y, float z, int flags, float pitch) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.flags = flags;
            this.pitch = pitch;
        }

        public boolean has(int f) {
            return (flags & f) != 0;
        }
    }

    private static final Map<String, List<Light>> TABLE = new HashMap<String, List<Light>>();

    static {
        // buggy.obj: headlights = the lamp group at x 1.67..1.74, y 0.56..0.79, z +-0.36..0.58 (centre z +-0.469); tail lamps
        // x -1.70, y 0.71, z +-0.5; amber side markers on the front / rear wheel arches' outer edge (paint |z| to 1.0)
        head("buggy", 1.745F, 0.675F, 0.469F, 6F, 0);
        pair("buggy", BRAKE, -1.71F, 0.706F, 0.5F, 0);
        pair("buggy", MARKER, 1.55F, 0.75F, 1.0F, 0);
        pair("buggy", MARKER, -1.6F, 0.75F, 1.0F, 0);
        // hover.obj: one headlight at the nose (lamp x 1.71..1.78, y 0.21..0.28, z +-0.125); the rear lamps beside the
        // thruster (x -1.775, y 0.275, z +-0.2) brake
        add("hover", HEAD, 1.78F, 0.244F, 0F, 0, 4F);
        pair("hover", BRAKE, -1.78F, 0.275F, 0.2F, 0);
        // truck.obj: headlights x 3.13..3.18, y 0.74..0.92, z +-0.5; crawler tail lamps x -1.10, y 0.765, z +-0.625 and
        // the trailer's x -3.29, y 0.70, z +-0.562 (trailer only); two amber rotating beacons on the roof light bar (amber
        // x 1.99..2.37, y 1.84..1.91, |z| <= 0.375) ends; amber side markers on the track guards' outer edge (paint2
        // x -1.0..2.74, y 0.70..0.78, |z| 1.131) front and back
        head("truck", 3.18F, 0.828F, 0.5F, 6F, 0);
        pair("truck", BRAKE, -1.12F, 0.765F, 0.625F, 0);
        pair("truck", BRAKE, -3.31F, 0.703F, 0.562F, F_TRAILER);
        pair("truck", AMBER, 2.30F, 1.97F, 0.30F, 0);
        pair("truck", MARKER, 2.70F, 0.74F, 1.14F, 0);
        pair("truck", MARKER, -0.95F, 0.74F, 1.14F, 0);
        // drill.obj: no lamps in the model - two work lights over the cab windscreen (glass front x 0.855, top y 2.03;
        // the cab is on the left, z -0.7..0.03) as small lamp discs; the amber beacon = the existing amber dome on the cab
        // roof (x 0.235..0.39, y 2.30..2.46, z -0.39..-0.24); markers on the hull sides (paint2 |z| 0.875, tread 1.15)
        add("drill", HEAD, 0.88F, 2.12F, -0.55F, F_DISC, 12F);
        add("drill", HEAD, 0.88F, 2.12F, -0.12F, F_DISC, 12F);
        add("drill", AMBER, 0.312F, 2.50F, -0.312F, 0, 0F);
        pair("drill", MARKER, 1.10F, 0.66F, 1.16F, 0);
        pair("drill", MARKER, -2.75F, 0.66F, 1.16F, 0);
        // heli.obj: nav lights = the existing red / green lamps under the cockpit (x 1.65..1.70, y 0.70..0.76, z -+0.375);
        // the landing light = the lamp under the nose (x 1.67..1.73, y 0.25..0.39, z 0); the strobe on the tail fin top
        // (x -2.45, y 1.99 - the red lamp up there is the tail's own); the red beacon under the belly (paint y 0.294 at x 0.74)
        add("heli", RED, 1.69F, 0.731F, -0.39F, 0, 0F);
        add("heli", GREEN, 1.69F, 0.731F, 0.39F, 0, 0F);
        add("heli", HEAD, 1.73F, 0.322F, 0F, 0, 25F);
        add("heli", STROBE, -2.40F, 2.02F, 0F, 0, 0F);
        add("heli", BEACON, 0.74F, 0.27F, 0F, 0, 0F);
        // plane.obj: red / green = the wing-tip lamps (x 0.57..0.65, y 0.99..1.06, z -+2.46..2.54); the white tail light =
        // the lamp at the tail cone (x -2.26..-2.21, y 0.82); the strobe on the fin top (x -2.08, y 1.569); the beacon under
        // the belly (y 0.375 at x -0.2); landing lights in the wing leading edges (x 1.0 at |z| 1.13, y 0.98)
        add("plane", RED, 0.61F, 1.025F, -2.55F, 0, 0F);
        add("plane", GREEN, 0.61F, 1.025F, 2.55F, 0, 0F);
        add("plane", WHITE, -2.27F, 0.82F, 0F, 0, 0F);
        add("plane", STROBE, -2.08F, 1.60F, 0F, 0, 0F);
        add("plane", BEACON, -0.2F, 0.35F, 0F, 0, 0F);
        pair("plane", HEAD, 1.0F, 0.97F, 1.15F, F_DISC, 14F);
        // jet.obj: red / green = the wing-tip lamps (x -1.92..-1.86, y 0.56..0.63, z -+2.11..2.17); strobes on the twin fins'
        // tops (x -2.825..-2.512, y 1.561, |z| 0.34..0.44); the beacon under the belly (y 0.292 at x 0); a landing light on
        // the nose gear strut (x 1.61, y 0.3) - shown only while the gear is down
        add("jet", RED, -1.888F, 0.592F, -2.18F, 0, 0F);
        add("jet", GREEN, -1.888F, 0.592F, 2.18F, 0, 0F);
        pair("jet", STROBE, -2.70F, 1.58F, 0.40F, 0);
        add("jet", BEACON, 0F, 0.27F, 0F, 0, 0F);
        add("jet", HEAD, 1.66F, 0.32F, 0F, F_DISC | F_GEAR, 10F);
        // tilt.obj: red / green on the nacelles' outer sides (paint2 to |z| 2.45 at x 0.84, y 1.09); the strobe on the H-tail
        // top (paint2 / white y 1.95 at x -2.28); the beacon under the belly (paint y 0.344 at x 0.84); a landing light in
        // the nose (paint to x 2.281, y 0.61)
        add("tilt", RED, 0.47F, 1.10F, -2.47F, 0, 0F);
        add("tilt", GREEN, 0.47F, 1.10F, 2.47F, 0, 0F);
        add("tilt", STROBE, -2.20F, 1.98F, 0F, 0, 0F);
        add("tilt", BEACON, 0.84F, 0.32F, 0F, 0, 0F);
        add("tilt", HEAD, 2.27F, 0.58F, 0F, F_DISC, 15F);
        // shuttle.obj: red / green on the solar wings' tips (solar x -1.52..-0.27, y 1.56..1.60, |z| 2.125) - the nose's own
        // red / green lamps (x 1.6, |z| 0.79) glow as they are; the strobe on the aft top (white y 1.75 at x -1.77); the
        // beacon under the cabin floor (carpet / fabric y 0.69); landing lights in the nose (white x 2.916, y 0.875)
        add("shuttle", RED, -0.9F, 1.60F, -2.15F, 0, 0F);
        add("shuttle", GREEN, -0.9F, 1.60F, 2.15F, 0, 0F);
        add("shuttle", STROBE, -1.77F, 1.78F, 0F, 0, 0F);
        add("shuttle", BEACON, -0.3F, 0.66F, 0F, 0, 0F);
        pair("shuttle", HEAD, 2.92F, 0.88F, 0.18F, F_DISC, 12F);
        // ship.obj: red / green on the hull's widest points (paint |z| 3.5 at x -1.92, y 0.63); the strobe on the top of
        // the singularity's gold ring (y 3.125 at x -0.55); the beacon under the keel (paint y 0 at x 0.58); the headlight
        // = the bow lamp (x 3.58..3.63, y 0.41)
        add("ship", RED, -1.92F, 0.65F, -3.53F, 0, 0F);
        add("ship", GREEN, -1.92F, 0.65F, 3.53F, 0, 0F);
        add("ship", STROBE, -0.55F, 3.16F, 0F, 0, 0F);
        add("ship", BEACON, 0.58F, -0.03F, 0F, 0, 0F);
        add("ship", HEAD, 3.64F, 0.406F, 0F, 0, 6F);
    }

    private VehicleLights() {
    }

    /** The lights of a model (empty if none). */
    public static List<Light> of(String model) {
        List<Light> l = TABLE.get(model);
        return l == null ? Collections.<Light>emptyList() : l;
    }

    /** Models with a light table (self-test). */
    public static java.util.Set<String> models() {
        return TABLE.keySet();
    }

    /** Has the model a headlight / landing light (a beam and the dynamic light). */
    public static boolean hasHead(String model) {
        for (Light l : of(model)) {
            if (l.kind == HEAD) {
                return true;
            }
        }
        return false;
    }

    /** Kind names (logs, the view self-test). */
    public static final String[] NAMES = {"head", "red", "green", "strobe", "beacon", "amber", "marker", "brake", "white"};

    /**
     * The glow sprite's largest radius by kind (blocks; VehicleLightsSVA - a beacon swells to its size at the peak of its
     * turn). In the own first-person view CockpitView.ownGlow clamps it.
     */
    public static float glowSize(int kind) {
        switch (kind) {
            case HEAD:
                return 0.45F;
            case BRAKE:
                return 0.5F;
            case RED:
            case GREEN:
            case WHITE:
                return 0.35F;
            case MARKER:
                return 0.25F;
            case STROBE:
                return 1.0F;
            case BEACON:
            case AMBER:
                return 0.8F;
            default:
                return 0.3F;
        }
    }

    /** Light colour 0xRRGGBB by kind. */
    public static int colour(int kind) {
        switch (kind) {
            case HEAD:
                return 0xFFF4D8;
            case RED:
            case BEACON:
                return 0xFF2A20;
            case GREEN:
                return 0x30FF6A;
            case STROBE:
            case WHITE:
                return 0xF4F8FF;
            case AMBER:
            case MARKER:
                return 0xFF9A18;
            case BRAKE:
                return 0xFF2018;
            default:
                return 0xFFFFFF;
        }
    }

    private static void head(String model, float x, float y, float z, float pitch, int flags) {
        pair(model, HEAD, x, y, z, flags, pitch);
    }

    private static void pair(String model, int kind, float x, float y, float z, int flags) {
        pair(model, kind, x, y, z, flags, 0F);
    }

    /** A light on each side: z and -z. */
    private static void pair(String model, int kind, float x, float y, float z, int flags, float pitch) {
        add(model, kind, x, y, -z, flags, pitch);
        add(model, kind, x, y, z, flags, pitch);
    }

    private static void add(String model, int kind, float x, float y, float z, int flags, float pitch) {
        List<Light> l = TABLE.get(model);
        if (l == null) {
            l = new ArrayList<Light>();
            TABLE.put(model, l);
        }
        l.add(new Light(kind, x, y, z, flags, pitch));
    }
}
