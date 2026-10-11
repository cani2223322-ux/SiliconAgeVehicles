package com.sva.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★10: the animated parts cut out of the OBJ models - wheels, landing gear legs and control surfaces - as pure data
 * (server-safe: the self-test loads the OBJ text and checks every box catches its triangles). ObjModelSVA turns each
 * Def into a Part when a model loads: the triangles of `groups` whose centre lies in [min, max] (model units: x forward,
 * y up, z right; export_obj.py) go to "#name/material" groups that RenderVehicleSVA draws moved. A `cut` part instead
 * CLIPS the triangles crossing its box (clipBox): the coarse wings / fins (the jet's wing is 12 triangles, the plane's fin
 * too) have no edge where an aileron or a rudder ends, so the box itself is the hinge line and the span limit.
 * Every number below was measured from the exported OBJ (connected components of the material, see the comments).
 * The table goes in FRONT of ObjModelSVA's older parts (rotors, trailer, bore): first match wins, so the truck's trailer
 * wheels are wheels (drawn with the trailer only) and not part of the plain trailer part.
 * Aircraft stage A: tagged parts. export_obj.py writes "# part TAG" before the faces of a moving piece (veh_real7: the
 * slotted flaps, slats, spoilers, the plane's ailerons and static wicks, the jet's slats / flaps / dorsal brake, the
 * tiltrotor's flaperons); a Def with a `tag` takes only triangles under that tag (its box then just picks the side), and
 * no untagged Def takes them - a slotted flap's nose sits under the wing's cove, no box could tell them apart. The tag
 * "wing" (SKIN_TAG) only marks the static wing skin (its panel tile) and is no part's.
 */
public final class AnimParts {

    /** Part kinds (the same numbers as ObjModelSVA.Part's - ROTOR 0 .. RINGS 5 are the older ones). */
    public static final int WHEEL = 6, GEAR = 7, SURFACE = 8,
            /** Cockpit stage 1: a moving control in the cabin (steering wheel, cyclic, collective, yoke, throttle, pedals). */
            CONTROL = 9,
            /** Aircraft stage A: a high-lift device (flap, slat, spoiler, the jet's speed brake) - tagged, see the DEVICE rows. */
            DEVICE = 10;
    /** DEVICE kinds (what moves it: the flap position, the slats, the spoilers / the brake - EntityVehicleSVA / AnimMath.State). */
    public static final int FLAP = 0, SLAT = 1, SPOILER = 2, BRAKE = 3,
            /** Stage D1, ★14: a retractable landing light (AnimMath.State.lamp 0 in .. 1 out; AirfieldMath.lampOut). */
            LAMP = 4,
            /** Stage D3, ★12: a gear door, modelled open - turned by ang1 x (1 - AnimMath.State.door) about its hinge. */
            DOOR = 5,
            /** Stage D3, ★13: a jet nozzle petal - turned by (REST_DEG - the petal angle) degrees about its root's tangent. */
            PETAL = 6;
    /** The static wing skin's tag (no part takes it; ObjModelSVA gives its paint the wing panel tile). */
    public static final String SKIN_TAG = "wing";
    /**
     * CONTROL inputs (AnimMath.State): STEER = the front wheels' angle / MAX_STEER, PITCH (+ = pull back, nose up), ROLL
     * (+ = right), YAW (+ = right), LIFT (+ = collective up), THROTTLE (the 0..1 throttle as -1..1: the lever's middle at
     * half throttle). All -1..1.
     */
    public static final int STEER = 0, PITCH = 1, ROLL = 2, YAW = 3, LIFT = 4, THROTTLE = 5,
            /**
             * Aircraft stage C: FLAPS = the flap position over its settings as -1..1 (-1 retracted, 1 the last setting - the
             * lever modelled at the middle), TRIM = the cosmetic pitch trim / TRIM_MAX (AnimMath.trim; + = nose up).
             */
            FLAPS = 6, TRIM = 7;
    /** Number of CONTROL inputs (debug/CockpitCheckSVA sweeps each). */
    public static final int INPUTS = 8;
    /**
     * Stage C, the light plane's flap lever (veh_real8.py): its pivot on the quadrant (model units), length to the knob and
     * forward angle (deg from the vertical) retracted / at the last setting - the CONTROL row turns it +-FLAP_SWING / 2
     * about +z from the modelled middle; the gate's marks (CockpitInstrumentsSVA) stand under the knob at each setting.
     */
    public static final float[] FLAP_LEVER_PIVOT = {1.215625F, 0.65625F, 0.190625F};
    public static final float FLAP_LEVER_LEN = 0.084375F, FLAP_LEVER_FWD = 12F, FLAP_LEVER_SWING = 44F;
    /** SURFACE controls. */
    public static final int AILERON = 0, ELEVATOR = 1, RUDDER = 2;
    /** Axes. */
    public static final int X = 0, Y = 1, Z = 2;

    /** One animated part. */
    public static final class Def {
        public final String model, name;
        public final int kind;
        public final String[] groups;
        public final float[] min, max;
        /** WHEEL: the axle centre (spin about `axis`); SURFACE: a point on the hinge line (turn about `axis`). */
        public final float[] pivot;
        public int axis = Z;
        /** WHEEL: rolling radius (model units; the tyre's outer radius) - 0: does not spin (a caliper that only steers). */
        public float radius;
        /** WHEEL: a front wheel - turned about y through the pivot by the steering angle. */
        public boolean steer;
        /** Drawn only while a trailer is hitched (the truck's trailer wheels). */
        public boolean trailer;
        /** Retracting gear (WHEEL or GEAR): at gear position 1 turned by gearAngle about gearAxis through gearPivot, then lifted by gearLift. */
        public float[] gearPivot;
        public int gearAxis = Z;
        public float gearAngle, gearLift;
        /**
         * Stage D3, ★12 (GearMath): the oleo's stroke (model units at full compression; the piston, the wheel and the lower
         * torque link slide up the leg by GearMath.travel - 0: the part does not slide), the torque link (+1 upper / -1
         * lower; turned about `pivot` - its hinge - by +-GearMath.linkTurn with the hinges linkD0 apart at REST, the links
         * linkLen long, the leg's stroke linkStroke), the light plane's spring leg (flex: swung about x through gearPivot by
         * GearMath.flex, mirrored on the left).
         */
        public float stroke, linkStroke, linkD0, linkLen;
        public int link;
        public boolean flex;
        /** SURFACE: which control and the sign (+1: positive command = positive angle about `axis`). */
        public int control;
        public float gain = 1F;
        /** Clip the triangles crossing the box (instead of the centre test). */
        public boolean cut;
        /** Self-test: at least this many triangles must fall in (cut: be clipped into) the box. */
        public int minTris = 8;
        /** Stage A: only the triangles exported under "# part TAG" (null: only untagged / skin-tagged triangles). */
        public String tag;
        /**
         * DEVICE: FLAP / SLAT / SPOILER / BRAKE. Turned by ang1 x level about axis1 through `pivot` and moved by move x level,
         * level = the deflection / refDeg (flaps: the angle of the setting over the reference one the motion was fitted at;
         * slats 0..1; spoilers / brake 0..1 of refDeg). ailMix (the tiltrotor's flaperons): + gain x ailMix x the aileron
         * command / AnimMath.MAX_SURFACE degrees on top.
         */
        public int device = -1;
        public float refDeg = 1F, ailMix;
        /**
         * CONTROL: turned by in1 x ang1 degrees about axis1 (a unit vector, through `pivot`), then by in2 x ang2 about
         * axis2, and moved by inMove x move (model units); -1 = no such input. Signs: + input turns the right-hand way.
         */
        public int in1 = -1, in2 = -1, inMove = -1;
        public float ang1, ang2;
        public float[] axis1, axis2, move;

        Def turn(int in, float[] axis, float deg) {
            in1 = in;
            axis1 = axis;
            ang1 = deg;
            return this;
        }

        Def turn2(int in, float[] axis, float deg) {
            in2 = in;
            axis2 = axis;
            ang2 = deg;
            return this;
        }

        Def wheel(float r) {
            radius = r;
            axis = Z;
            return this;
        }

        Def stroke(float s) {
            stroke = s;
            return this;
        }

        Def link(int which, float d0, float len, float legStroke) {
            link = which;
            linkD0 = d0;
            linkLen = len;
            linkStroke = legStroke;
            return this;
        }

        Def move(int in, float[] by) {
            inMove = in;
            move = by;
            return this;
        }

        Def(String model, String name, int kind, String[] groups, float[] min, float[] max, float[] pivot) {
            this.model = model;
            this.name = name;
            this.kind = kind;
            this.groups = groups;
            this.min = min;
            this.max = max;
            this.pivot = pivot;
        }

        /** Does this part take triangles of material `group`? */
        public boolean takes(String group) {
            for (String g : groups) {
                if (g.equals(group)) {
                    return true;
                }
            }
            return false;
        }

        /** The centre test (non-cut parts). */
        public boolean holds(String group, float x, float y, float z) {
            return takes(group) && x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
        }

        /** Stage A: may this Def take a triangle exported under `triTag` (null = untagged)? Its own tag only; untagged Defs the rest. */
        public boolean takesTag(String triTag) {
            return tag == null ? triTag == null || SKIN_TAG.equals(triTag) : tag.equals(triTag);
        }

        /** The triangle's bounding box overlaps the part's box (a cut part clips it). */
        public boolean overlaps(float[] a, float[] b, float[] c) {
            for (int k = 0; k < 3; k++) {
                float lo = Math.min(a[k], Math.min(b[k], c[k])), hi = Math.max(a[k], Math.max(b[k], c[k]));
                if (hi <= min[k] || lo >= max[k]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Map<String, List<Def>> TABLE = new HashMap<String, List<Def>>();
    private static final List<Def> ALL = new ArrayList<Def>();

    private static final String[] TYRE = {"rubber", "chrome", "disc", "accent", "dark"};

    static {
        // ---- buggy.obj: four wheels (tyre rubber r 0.488 + chrome rims + brake disc + dark spokes + accent hub cap) at
        // x -1.05 (rear) / 1.075 (front), y 0.488, z +-0.781 (rim faces z 0.649..0.914); the spare on the back (x -1.77,
        // y 1.08) stays. The front calipers (x 0.98..1.19, y 0.65..0.77) turn with the steering but do not spin.
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            wheel("buggy", "wheelF" + side, TYRE, 1.075F, 0.488F, s * 0.781F, 0.5F, 0.5F, 0.6F, 0.96F, s, 0.488F).steer = true;
            wheel("buggy", "wheelB" + side, TYRE, -1.05F, 0.488F, s * 0.781F, 0.5F, 0.5F, 0.6F, 0.96F, s, 0.488F);
            Def cal = box("buggy", "caliperF" + side, WHEEL, new String[] {"caliper"}, 0.95F, 0.62F, 0.72F, 1.22F, 0.8F, 0.82F, s,
                    new float[] {1.075F, 0.488F, s * 0.781F});
            cal.steer = true;
        }
        // ---- truck.obj: the crawler's six road wheels per side (steel + accent hub, r 0.175) at x -0.631, -0.006, 0.619,
        // 1.244, 1.869, 2.494, y 0.365, z +-0.981 (inside the tread loop, |z| 0.83..1.11). The trailer's two wheels (as the
        // buggy's, r 0.39) at x -2.256, y 0.39, z +-0.781 - drawn with the trailer only; its calipers stay with the trailer.
        float[] road = {-0.631F, -0.006F, 0.619F, 1.244F, 1.869F, 2.494F};
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < road.length; i++) {
                wheel("truck", "road" + (s > 0 ? "R" : "L") + i, new String[] {"steel", "accent"}, road[i], 0.365F, s * 0.981F,
                        0.19F, 0.19F, 0.8F, 1.16F, s, 0.175F).minTris = 40;
            }
            wheel("truck", "trailerWheel" + (s > 0 ? "R" : "L"), TYRE, -2.256F, 0.39F, s * 0.781F, 0.45F, 0.41F, 0.62F, 0.95F, s, 0.39F)
                    .trailer = true;
        }
        // ---- wave 3 (★15) the trailer models (vehicles/w3/veh_trailers.py, truck.obj's model space): tandem axles of the
        // braked tyre (rubber + chrome rims + disc + dark bolts + accent hub; the calipers stay). trailer_tank / _charger:
        // r 0.344 at x -2.819 / -2.069, y 0.359, z +-0.656 (tank) / +-0.787 (charger); trailer_platform: smaller wheels
        // outboard of the deck, r 0.287 at x -2.881 / -2.194, y 0.303, z +-0.988. Boxes: +-0.384 / +-0.326 round the axle,
        // |z| over the tyre's width + 0.04 (the tank's bumper and the deck stay out: other materials or |z| inside).
        float[][] tw = {{-2.819F, 0.359F, 0.656F, 0.344F, 0.384F, 0.513F, 0.800F}, {-2.069F, 0.359F, 0.656F, 0.344F, 0.384F, 0.513F, 0.800F}};
        float[][] pw = {{-2.881F, 0.303F, 0.988F, 0.287F, 0.326F, 0.856F, 1.119F}, {-2.194F, 0.303F, 0.988F, 0.287F, 0.326F, 0.856F, 1.119F}};
        float[][] cw = {{-2.819F, 0.359F, 0.787F, 0.344F, 0.384F, 0.644F, 0.931F}, {-2.069F, 0.359F, 0.787F, 0.344F, 0.384F, 0.644F, 0.931F}};
        trailerWheels("trailer_tank", tw);
        trailerWheels("trailer_platform", pw);
        trailerWheels("trailer_charger", cw);
        // ---- drill.obj: five road wheels per side (steel, r 0.194) at x -2.375, -1.656, -0.938, -0.219, 0.5, y 0.281,
        // z +-1.006 (the tread runs at |z| 0.85..1.15 around them)
        float[] droad = {-2.375F, -1.656F, -0.938F, -0.219F, 0.5F};
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < droad.length; i++) {
                wheel("drill", "road" + (s > 0 ? "R" : "L") + i, new String[] {"steel"}, droad[i], 0.281F, s * 1.006F, 0.21F, 0.21F,
                        0.82F, 1.19F, s, 0.194F).minTris = 40;
            }
        }
        // ---- plane.obj: fixed main gear in spats - wheels (r 0.144) at x 0.547, y 0.144, z +-0.375 inside the paint2
        // spats (x 0.359..0.734, y 0..0.325, |z| 0.275..0.475) on a steel strut from the fuselage side (strut top y 0.457,
        // |z| 0.17). Stage D3 (★12, the mock-up keeps the spats): no retraction any more (it folded back 85 deg) - the spring
        // legs flex out-and-up about the strut top on touchdown (GearMath.flex).
        // The tail wheel (r 0.084, x -1.953, y 0.6) only spins. Control surfaces (cut): ailerons = the outer wing trailing
        // edge (wing x 0.297 TE .. 1.047 LE, y 0.88..1.07, span to 2.5 with the paint2 tip cap from 2.375), elevator halves
        // = the tailplane's trailing edge (x -2.078..-1.641, y 0.656..0.731, |z| 0.062..0.812), rudder = the fin's aft
        // part (fin x -2.328..-1.641, y 0.756..1.569, |z| <= 0.038).
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            Def w = wheel("plane", "gearWheel" + side, new String[] {"rubber", "chrome", "dark", "accent"}, 0.547F, 0.144F, s * 0.375F,
                    0.16F, 0.16F, 0.32F, 0.43F, s, 0.144F);
            flex(w, new float[] {0.547F, 0.457F, s * 0.17F});
            Def leg = box("plane", "gearSpat" + side, GEAR, new String[] {"paint2"}, 0.35F, -0.01F, 0.26F, 0.75F, 0.34F, 0.48F, s,
                    new float[] {0.547F, 0.457F, s * 0.17F});
            flex(leg, leg.pivot);
            // the strut (x 0.522..0.572) - the wing brace's foot right next to it (x <= 0.446, y 0.42..0.47) stays
            Def strut = box("plane", "gearStrut" + side, GEAR, new String[] {"steel"}, 0.48F, 0.1F, 0.16F, 0.6F, 0.47F, 0.41F, s,
                    new float[] {0.547F, 0.457F, s * 0.17F});
            flex(strut, strut.pivot);
            // aircraft stage A (veh_real7, scratchpad vehicles/airA/airA.py): the aileron is its own airfoil piece now
            // (73.5 % .. TE, |z| 23.4..37.4 concept, with its three static wicks) hinged at 75 % chord - (0.530, 1.012) at
            // mid-span; tagged, not cut
            Def ail = surface("plane", "aileron" + side, new String[] {"dark", "paint"}, 0.2864F, 0.9750F, 1.4585F, 0.5692F, 1.0444F, 2.3415F, s,
                    new float[] {0.5304F, 1.0115F, 0F}, Z, AILERON, -s);
            ail.cut = false;
            ail.tag = "ail";
            ail.minTris = 380;
            surface("plane", "elevator" + side, new String[] {"paint"}, -2.09F, 0.64F, 0.08F, -1.93F, 0.75F, 0.83F, s,
                    new float[] {-1.93F, 0.694F, 0F}, Z, ELEVATOR, -1);
            // the elevator's two static wicks (dark, behind its trailing edge) turn with it
            Def wick = surface("plane", "elevatorWicks" + side, new String[] {"dark"}, -2.1244F, 0.6872F, 0.5255F, -2.0105F, 0.7003F, 0.7619F, s,
                    new float[] {-1.93F, 0.694F, 0F}, Z, ELEVATOR, -1);
            wick.cut = false;
            wick.tag = "elev";
            wick.minTris = 40;
            // ★1 the slotted flap (66 % .. TE, |z| 4.2..23 concept, with its three moving track fairings): back and down -
            // fitted to the concept's section kinematics (hinge 70 % chord, 6.5 % under it, 35 deg + 2 % / -0.8 % chord):
            // 33.5 deg about the hinge line + (-0.013, -0.008) at 35 deg, 0.005 off the concept
            device("plane", "flap" + side, FLAP, "flap", new String[] {"paint"}, 0.2191F, 0.9056F, 0.2585F, 0.6043F, 1.0214F, 1.4415F, s,
                    new float[] {0.5357F, 0.9129F, 0.85F}, new float[] {0.0219F, 0.0482F, 0.9986F}, 33.54F, 35F,
                    new float[] {-0.0130F, -0.0084F, -0.0033F}).minTris = 690;
            // ★2 the slat (nose 0..12 % with its metal strip, |z| 16.2..37.4): 17 deg nose down about 12 % chord, forward /
            // down 4.5 / 2.8 % chord
            device("plane", "slat" + side, SLAT, "slat", new String[] {"lestrip", "paint"}, 0.8683F, 0.9339F, 1.0085F, 1.0099F, 1.0538F, 2.3415F, s,
                    new float[] {0.8962F, 1.0001F, 1.675F}, new float[] {0.0416F, -0.0394F, -0.9984F}, 17.03F, 1F,
                    new float[] {0.0266F, -0.0165F, -0.0004F}).minTris = 408;
            // ★3 two spoiler panels a side (45..65.5 % chord, |z| 7.6..19.8): the trailing edge up 50 deg about the front edge
            device("plane", "spoiler" + side, SPOILER, "spoil", new String[] {"paint"}, 0.5570F, 0.9907F, 0.4710F, 0.7117F, 1.0435F, 1.2415F, s,
                    new float[] {0.7059F, 1.0249F, 0.8562F}, new float[] {0.0055F, -0.0247F, -0.9997F}, 48.68F, 50F,
                    new float[] {0.0012F, 0.0011F, 0.0012F}).minTris = 192;
        }
        for (int s = -1; s <= 1; s += 2) {
            // stage D1, ★14 the retractable landing light (veh_real9: |z| 13 concept, 24..40 % chord under the wing): the
            // flush door with the lamp housing, bezel and lens on its inner face swings 105 deg about its rear (hinge) edge,
            // front edge down - the lens then looks forward, 15 deg down (AirfieldMath.LAMP_DEG / LAMP_PITCH); 204 triangles
            device("plane", "landingLamp" + (s > 0 ? "R" : "L"), LAMP, "llamp", new String[] {"chrome", "dark", "lamp", "paint"}, 0.7364F, 0.9103F,
                    0.7679F, 0.8540F, 0.9571F, 0.8571F, s, new float[] {0.7404F, 0.9206F, 0.8125F}, new float[] {0F, 0F, -1F},
                    com.sva.vehicle.AirfieldMath.LAMP_DEG, 1F, new float[] {0F, 0F, 0F}).minTris = 200;
        }
        wheel("plane", "tailWheel", new String[] {"rubber", "dark", "accent"}, -1.953F, 0.6F, 0F, 0.1F, 0.1F, -0.04F, 0.04F, 0, 0.084F);
        surface("plane", "rudder", new String[] {"paint"}, -2.34F, 0.80F, -0.05F, -2.12F, 1.58F, 0.05F, 0,
                new float[] {-2.12F, 1.1F, 0F}, Y, RUDDER, 1);
        // ---- jet.obj: the gear - see the stage D3 rows below (the old main wheels rose 0.25 into the wing root, the nose
        // leg folded back into the nose). Surfaces (cut; the wing is 12 triangles): ailerons on the
        // swept trailing edge (root TE x -1.888 at |z| 0.312, tip TE -2.138 at 2.125) behind the hinge x -1.80 for |z|
        // 1.0..1.9; elevators = the tailplane aft of x -2.60 (|z| 0.31..0.88; the tail cone stays: |z| <= 0.31 there);
        // rudders = the twin canted fins aft of x -2.55 (fins y 0.749..1.561, |z| 0.188..0.438).
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            // stage A: the wing is three plates now (veh_real7) - the aileron cut starts outboard of the flap (|z| > 1.25)
            surface("jet", "aileron" + side, new String[] {"paint"}, -2.2F, 0.5F, 1.27F, -1.8F, 0.62F, 1.9F, s,
                    new float[] {-1.8F, 0.561F, 0F}, Z, AILERON, -s);
            // ★1 the plain flaps (paint3, |z| 5..20 concept, chord 3) hinged at their front edge: 0 / 20 / 40 deg
            device("jet", "flap" + side, FLAP, "flap", new String[] {"paint3"}, -1.9777F, 0.5289F, 0.3085F, -1.7391F, 0.5932F, 1.2540F, s,
                    new float[] {-1.7F, 0.5548F, 0.3125F}, new float[] {-0.1366F, 0F, 0.9906F}, 40F, 40F, new float[] {0F, 0F, 0F}).minTris = 12;
            // ★2 the slats along the span (|z| 12..34, with the accent leading edge): 16 deg nose down about the hinge line,
            // 1.3 forward along the chord and 0.35 down (concept units)
            device("jet", "slat" + side, SLAT, "slat", new String[] {"accent", "paint"}, -1.7509F, 0.5258F, 0.7322F, 0.0846F, 0.5963F, 2.1428F, s,
                    new float[] {-0.0806F, 0.5548F, 0.75F}, new float[] {0.7788F, 0F, -0.6273F}, 16F, 1F,
                    new float[] {0.0510F, -0.0219F, 0.0633F}).minTris = 44;
            surface("jet", "elevator" + side, new String[] {"paint"}, -2.92F, 0.52F, 0.31F, -2.6F, 0.61F, 0.89F, s,
                    new float[] {-2.6F, 0.564F, 0F}, Z, ELEVATOR, -1);
            surface("jet", "rudder" + side, new String[] {"paint"}, -2.85F, 0.76F, 0.17F, -2.55F, 1.57F, 0.46F, s,
                    new float[] {-2.55F, 1.15F, s * 0.3F}, Y, RUDDER, 1);
        }
        // stage D3 (★12, veh_real11.py, scratchpad vehicles/airD3/gen_rows.py): the retractable gear - each leg built down
        // at the parking stroke (GearMath.REST), aft 90 deg about z through its pivot when retracted; the main legs (pivot
        // x -0.6375, y 0.505, |z| 0.1625) carry the old tyres on a half axle at |z| 0.225, the nose leg (x 1.55) the old nose
        // tyre moved 0.0625 aft; piston / wheel / lower torque link slide with the stroke (0.075 main, 0.056 nose), the links
        // fold; the clamshell doors (paint3, modelled open) shut about their hinges under the belly
        leg("jet", "mainLLeg", GEAR, "mleg", new String[] {"dark", "steel"}, new float[] {-0.6720F, 0.2671F, -0.1968F}, new float[] {-0.6030F, 0.5088F, -0.1283F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, 112);
        leg("jet", "mainLPiston", GEAR, "mpis", new String[] {"chrome"}, new float[] {-0.6586F, 0.1051F, -0.2634F}, new float[] {-0.6164F, 0.3314F, -0.1415F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, 196).stroke(0.07500F);
        leg("jet", "mainLWheel", WHEEL, "mwhl", new String[] {"accent", "chrome", "dark", "rubber"}, new float[] {-0.7635F, -0.0024F, -0.2728F}, new float[] {-0.5115F, 0.2495F, -0.1772F}, new float[] {-0.6375F, 0.1236F, -0.2250F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, 864).wheel(0.124F).stroke(0.07500F);
        leg("jet", "mainLLinkUp", GEAR, "mlku", new String[] {"chrome", "steel"}, new float[] {-0.6204F, 0.2062F, -0.1750F}, new float[] {-0.5321F, 0.3061F, -0.1500F}, new float[] {-0.6094F, 0.2942F, -0.1625F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, 152).link(1, 0.14881F, 0.09865F, 0.07500F);
        leg("jet", "mainLLinkLow", GEAR, "mlkl", new String[] {"steel"}, new float[] {-0.6204F, 0.1335F, -0.1736F}, new float[] {-0.5376F, 0.2265F, -0.1514F}, new float[] {-0.6094F, 0.1454F, -0.1625F}, new float[] {-0.6375F, 0.5048F, -0.1625F}, 88).stroke(0.07500F).link(-1, 0.14881F, 0.09865F, 0.07500F);
        leg("jet", "mainRLeg", GEAR, "mleg", new String[] {"dark", "steel"}, new float[] {-0.6720F, 0.2671F, 0.1283F}, new float[] {-0.6030F, 0.5088F, 0.1968F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, 112);
        leg("jet", "mainRPiston", GEAR, "mpis", new String[] {"chrome"}, new float[] {-0.6586F, 0.1051F, 0.1415F}, new float[] {-0.6164F, 0.3314F, 0.2634F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, 196).stroke(0.07500F);
        leg("jet", "mainRWheel", WHEEL, "mwhl", new String[] {"accent", "chrome", "dark", "rubber"}, new float[] {-0.7635F, -0.0024F, 0.1772F}, new float[] {-0.5115F, 0.2495F, 0.2728F}, new float[] {-0.6375F, 0.1236F, 0.2250F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, 864).wheel(0.124F).stroke(0.07500F);
        leg("jet", "mainRLinkUp", GEAR, "mlku", new String[] {"chrome", "steel"}, new float[] {-0.6204F, 0.2062F, 0.1500F}, new float[] {-0.5321F, 0.3061F, 0.1750F}, new float[] {-0.6094F, 0.2942F, 0.1625F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, 152).link(1, 0.14881F, 0.09865F, 0.07500F);
        leg("jet", "mainRLinkLow", GEAR, "mlkl", new String[] {"steel"}, new float[] {-0.6204F, 0.1335F, 0.1514F}, new float[] {-0.5376F, 0.2265F, 0.1736F}, new float[] {-0.6094F, 0.1454F, 0.1625F}, new float[] {-0.6375F, 0.5048F, 0.1625F}, 88).stroke(0.07500F).link(-1, 0.14881F, 0.09865F, 0.07500F);
        leg("jet", "noseLeg", GEAR, "nleg", new String[] {"dark", "steel"}, new float[] {1.5155F, 0.2487F, -0.0342F}, new float[] {1.5845F, 0.4588F, 0.0342F}, new float[] {1.5500F, 0.4548F, 0.0000F}, new float[] {1.5500F, 0.4548F, 0.0000F}, 112);
        leg("jet", "nosePiston", GEAR, "npis", new String[] {"chrome"}, new float[] {1.5289F, 0.1258F, -0.0209F}, new float[] {1.5711F, 0.3055F, 0.0209F}, new float[] {1.5500F, 0.4548F, 0.0000F}, new float[] {1.5500F, 0.4548F, 0.0000F}, 56).stroke(0.05625F);
        leg("jet", "noseWheel", WHEEL, "nwhl", new String[] {"accent", "chrome", "dark", "rubber"}, new float[] {1.4433F, 0.0169F, -0.0415F}, new float[] {1.6567F, 0.2303F, 0.0415F}, new float[] {1.5500F, 0.1236F, 0.0000F}, new float[] {1.5500F, 0.4548F, 0.0000F}, 864).wheel(0.104F).stroke(0.05625F);
        leg("jet", "noseLinkUp", GEAR, "nlku", new String[] {"chrome", "steel"}, new float[] {1.5671F, 0.2424F, -0.0125F}, new float[] {1.6187F, 0.2878F, 0.0125F}, new float[] {1.5781F, 0.2759F, 0.0000F}, new float[] {1.5500F, 0.4548F, 0.0000F}, 152).link(1, 0.03981F, 0.03438F, 0.05625F);
        leg("jet", "noseLinkLow", GEAR, "nlkl", new String[] {"steel"}, new float[] {1.5671F, 0.2242F, -0.0111F}, new float[] {1.6125F, 0.2632F, 0.0111F}, new float[] {1.5781F, 0.2361F, 0.0000F}, new float[] {1.5500F, 0.4548F, 0.0000F}, 88).stroke(0.05625F).link(-1, 0.03981F, 0.03438F, 0.05625F);
        door("jet", "mdinL", "mdin", new String[] {"paint3"}, new float[] {-1.1939F, 0.2072F, -0.1483F}, new float[] {-0.5334F, 0.2983F, -0.1120F}, new float[] {-0.8638F, 0.2942F, -0.1219F}, new float[] {-1.0000F, -0.0018F, -0.0000F}, 12);
        door("jet", "mdoutL", "mdout", new String[] {"paint3"}, new float[] {-1.1941F, 0.2826F, -0.3503F}, new float[] {-0.5335F, 0.3744F, -0.2864F}, new float[] {-0.8638F, 0.3699F, -0.2875F}, new float[] {1.0000F, -0.0044F, 0.0000F}, 12);
        door("jet", "mdinR", "mdin", new String[] {"paint3"}, new float[] {-1.1939F, 0.2071F, 0.1120F}, new float[] {-0.5334F, 0.2983F, 0.1484F}, new float[] {-0.8638F, 0.2941F, 0.1219F}, new float[] {1.0000F, 0.0018F, 0.0000F}, 12);
        door("jet", "mdoutR", "mdout", new String[] {"paint3"}, new float[] {-1.1941F, 0.2826F, 0.2864F}, new float[] {-0.5335F, 0.3744F, 0.3503F}, new float[] {-0.8638F, 0.3699F, 0.2875F}, new float[] {-1.0000F, 0.0042F, -0.0000F}, 12);
        door("jet", "ndoorL", "ndoor", new String[] {"paint3"}, new float[] {1.0717F, 0.2663F, -0.0883F}, new float[] {1.6068F, 0.3580F, -0.0699F}, new float[] {1.3372F, 0.3480F, -0.0719F}, new float[] {0.9977F, 0.0685F, 0.0000F}, 12);
        door("jet", "ndoorR", "ndoor", new String[] {"paint3"}, new float[] {1.0717F, 0.2663F, 0.0699F}, new float[] {1.6068F, 0.3580F, 0.0882F}, new float[] {1.3372F, 0.3480F, 0.0719F}, new float[] {-0.9977F, -0.0684F, -0.0000F}, 12);
        // ★3 the dorsal speed brake behind the canopy (x -11.2..-1.2 concept, on the spine): hinged at its front edge, the
        // aft edge up 45 deg (25 over HighLiftMath.JET.brakeFastSpeed)
        device("jet", "speedBrake", BRAKE, "brake", new String[] {"paint"}, -0.7165F, 0.8852F, -0.1660F, -0.0835F, 0.9132F, 0.1660F, 0,
                new float[] {-0.0875F, 0.8986F, 0F}, new float[] {0F, 0F, -1F}, 45F, 45F, new float[] {0F, 0F, 0F}).minTris = 192;
        // ---- tilt.obj: the main gear - see the stage D3 rows below; the nose wheel (r 0.13) at x 1.594, y 0.156 only spins. Ailerons: the wing's trailing edge (wing x 0.094 TE .. 0.844,
        // y 1.02..1.17, |z| 0.312..2.125) behind x 0.25 for |z| 1.2..2.05; elevator: the H-tail plank (x -2.281..-1.781,
        // y 1.875..1.95, |z| <= 0.75) behind x -2.12; rudder: the fin (x -2.219..-1.406, y 0.906..1.906) behind x -2.0.
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            // stage A: the flaperons (veh_real7: one slotted piece 66 % .. TE, |z| 5..31.5, three moving fairings; the wing's
            // chord line at y 17.2 concept - the troop seats' heads stay enclosed, RiderFitSVA) replace the ailerons:
            // 0 / 15 / 25 deg as flaps (drooped 25 by themselves in the vertical mode), +-10 deg as ailerons
            Def fl = device("tilt", "flaperon" + side, FLAP, "flap", new String[] {"white"}, 0.0158F, 1.0039F, 0.3085F, 0.4012F, 1.1069F, 1.9728F, s,
                    new float[] {0.3302F, 0.9985F, 1.1406F}, new float[] {0.0138F, 0.0045F, 0.9999F}, 25F, 25F,
                    new float[] {-0.0099F, -0.0040F, -0.0004F});
            fl.minTris = 744;
            fl.ailMix = 10F;
            fl.control = AILERON;
            fl.gain = -s;
        }
        wheel("tilt", "noseWheel", new String[] {"rubber", "chrome", "dark", "accent"}, 1.594F, 0.156F, 0F, 0.145F, 0.145F, -0.07F, 0.07F,
                0, 0.13F);
        // stage D3 (★12, veh_real11.py): the main legs retract aft into the new side fairings (x -1.4..-0.78, |z| 0.33..0.54,
        // y 0.27..0.62) closed by two doors each; pivot x -0.531, y 0.444, |z| 0.375, the old tyres on half axles at |z| 0.45;
        // the nose wheel stays down (no room under the cockpit floor)
        leg("tilt", "mainLLeg", GEAR, "tleg", new String[] {"dark", "steel"}, new float[] {-0.5657F, 0.2630F, -0.4092F}, new float[] {-0.4968F, 0.4476F, -0.3408F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, 112);
        leg("tilt", "mainLPiston", GEAR, "tpis", new String[] {"chrome"}, new float[] {-0.5523F, 0.1377F, -0.4884F}, new float[] {-0.5102F, 0.3172F, -0.3540F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, 196).stroke(0.05000F);
        leg("tilt", "mainLWheel", WHEEL, "twhl", new String[] {"accent", "chrome", "dark", "rubber"}, new float[] {-0.6893F, -0.0020F, -0.5103F}, new float[] {-0.3732F, 0.3141F, -0.3897F}, new float[] {-0.5312F, 0.1561F, -0.4500F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, 864).wheel(0.156F).stroke(0.05000F);
        leg("tilt", "mainLLinkUp", GEAR, "tlku", new String[] {"chrome", "steel"}, new float[] {-0.5142F, 0.2204F, -0.3875F}, new float[] {-0.4440F, 0.3020F, -0.3625F}, new float[] {-0.5031F, 0.2901F, -0.3750F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, 152).link(1, 0.11213F, 0.07289F, 0.05000F);
        leg("tilt", "mainLLinkLow", GEAR, "tlkl", new String[] {"steel"}, new float[] {-0.5142F, 0.1661F, -0.3860F}, new float[] {-0.4495F, 0.2405F, -0.3640F}, new float[] {-0.5031F, 0.1780F, -0.3750F}, new float[] {-0.5312F, 0.4436F, -0.3750F}, 88).stroke(0.05000F).link(-1, 0.11213F, 0.07289F, 0.05000F);
        leg("tilt", "mainRLeg", GEAR, "tleg", new String[] {"dark", "steel"}, new float[] {-0.5657F, 0.2630F, 0.3408F}, new float[] {-0.4968F, 0.4476F, 0.4092F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, 112);
        leg("tilt", "mainRPiston", GEAR, "tpis", new String[] {"chrome"}, new float[] {-0.5523F, 0.1377F, 0.3540F}, new float[] {-0.5102F, 0.3172F, 0.4884F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, 196).stroke(0.05000F);
        leg("tilt", "mainRWheel", WHEEL, "twhl", new String[] {"accent", "chrome", "dark", "rubber"}, new float[] {-0.6893F, -0.0020F, 0.3897F}, new float[] {-0.3732F, 0.3141F, 0.5103F}, new float[] {-0.5312F, 0.1561F, 0.4500F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, 864).wheel(0.156F).stroke(0.05000F);
        leg("tilt", "mainRLinkUp", GEAR, "tlku", new String[] {"chrome", "steel"}, new float[] {-0.5142F, 0.2204F, 0.3625F}, new float[] {-0.4440F, 0.3020F, 0.3875F}, new float[] {-0.5031F, 0.2901F, 0.3750F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, 152).link(1, 0.11213F, 0.07289F, 0.05000F);
        leg("tilt", "mainRLinkLow", GEAR, "tlkl", new String[] {"steel"}, new float[] {-0.5142F, 0.1661F, 0.3640F}, new float[] {-0.4495F, 0.2405F, 0.3860F}, new float[] {-0.5031F, 0.1780F, 0.3750F}, new float[] {-0.5312F, 0.4436F, 0.3750F}, 88).stroke(0.05000F).link(-1, 0.11213F, 0.07289F, 0.05000F);
        door("tilt", "tdinL", "tdin", new String[] {"white"}, new float[] {-1.0352F, 0.1696F, -0.3478F}, new float[] {-0.4460F, 0.2713F, -0.3310F}, new float[] {-0.7406F, 0.2673F, -0.3438F}, new float[] {-1.0000F, -0.0000F, -0.0000F}, 12);
        door("tilt", "tdoutL", "tdout", new String[] {"white"}, new float[] {-1.0352F, 0.1696F, -0.5440F}, new float[] {-0.4460F, 0.2713F, -0.5272F}, new float[] {-0.7406F, 0.2673F, -0.5312F}, new float[] {1.0000F, 0.0000F, 0.0000F}, 12);
        door("tilt", "tdinR", "tdin", new String[] {"white"}, new float[] {-1.0352F, 0.1696F, 0.3310F}, new float[] {-0.4460F, 0.2713F, 0.3478F}, new float[] {-0.7406F, 0.2673F, 0.3438F}, new float[] {1.0000F, 0.0000F, 0.0000F}, 12);
        door("tilt", "tdoutR", "tdout", new String[] {"white"}, new float[] {-1.0352F, 0.1696F, 0.5272F}, new float[] {-0.4460F, 0.2713F, 0.5440F}, new float[] {-0.7406F, 0.2673F, 0.5312F}, new float[] {-1.0000F, -0.0000F, -0.0000F}, 12);
        surface("tilt", "elevator", new String[] {"white"}, -2.29F, 1.86F, -0.745F, -2.12F, 1.96F, 0.745F, 0,
                new float[] {-2.12F, 1.912F, 0F}, Z, ELEVATOR, -1);
        surface("tilt", "rudder", new String[] {"paint"}, -2.23F, 0.95F, -0.05F, -2.0F, 1.89F, 0.05F, 0,
                new float[] {-2.0F, 1.4F, 0F}, Y, RUDDER, 1);
        // ---- shuttle.obj: four landing legs (steel leg + paint2 foot pad, y 0..0.629) at x -1.772 / 1.103, z +-0.625; they
        // draw up 0.42 into the hull (its bottom at y 0.5) when the gear retracts.
        float[] legs = {-1.772F, 1.103F};
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < legs.length; i++) {
                Def g = box("shuttle", "leg" + (s > 0 ? "R" : "L") + i, GEAR, new String[] {"steel", "paint2"}, legs[i] - 0.16F, -0.01F, 0.47F,
                        legs[i] + 0.16F, 0.64F, 0.78F, s, new float[] {legs[i], 0.3F, s * 0.625F});
                gear(g, g.pivot, Z, 0F, 0.42F);
                g.minTris = 60;
            }
        }
        // ---- cockpit stage 1 (2026-10, mock-up docs/mockups/veh_cab_5_controls.png): the moving controls, measured by the
        // concept scratchpad vehicles/cab/controls.py from the exported OBJs - each box holds exactly its control's
        // triangles (centres, +0.004) and no other triangle of those materials. Steering wheels (rim leather, spokes steel,
        // hub accent; the dark column stays) turn up to 90 deg about the column (the wheel's normal, pointing away from the
        // driver) at full steering; the heli's cyclic (dark shaft + grip, a red - not a 'warn' lamp - button) tilts 12 deg
        // fore / aft and sideways about its foot, the collective (black grip, steel lever) rises 10 deg, the pedals slide
        // 0.028 (0.45 concept units) against each other; the plane's yoke turns 30 deg with the roll and slides 0.037 back
        // when pulled, its throttle lever swings +-28 deg about the quadrant top with the throttle.
        // Cockpit visibility fix (2026-10, the user's screenshots; debug/CockpitCheckSVA sweeps every row over its inputs
        // and checks the faces of the instruments from the pilot's camera): the car wheels sit higher and nearer the
        // driver, smaller (buggy r 0.097 / tilt 50 deg, truck 0.119 / 48, drill 0.113 / 50 - the rim cleared the scuttle
        // deck by the full turn, the cluster is read over the rim, the hub below the line of sight); the heli's cyclic is
        // 0.044 shorter and its pedals 0.025 lower / 0.012 further back (they cut the panel's foot); the plane's yoke has
        // short horns on a fixed column rising out of the panel's foot - it turns about the column (0.80, -0.60, 0) and
        // slides 0.037 back ALONG it when pulled (a level shaft between the rows lay over the lower centre dial); the
        // plane's pedals 0.019 back, the left one 0.013 inboard (pushed fully it went into the narrowing nose).
        // Cockpit wave (C+ panel, veh_real12.py): the light plane's throttle swings +-14 deg (was 28) - the new panel's MFD
        // and its keys sit right over the quadrant (the concept's screen angles), full throttle at 28 deg put the knob into
        // their lower right corner; at 14 the knob stops 0.03 short of them (debug/CockpitCheckSVA: clear).
        control("buggy", "steering", new String[] {"accent", "leather", "steel"}, new float[] {-0.248F, 0.940F, -0.386F}, new float[] {-0.077F, 1.136F, -0.146F}, new float[] {-0.162F, 1.038F, -0.266F}).turn(STEER, new float[] {0.766F, -0.643F, 0.000F}, 90.0F);
        control("truck", "steering", new String[] {"accent", "leather", "steel"}, new float[] {2.235F, 1.116F, -0.442F}, new float[] {2.440F, 1.340F, -0.158F}, new float[] {2.337F, 1.228F, -0.300F}).turn(STEER, new float[] {0.743F, -0.669F, 0.000F}, 90.0F);
        control("drill", "steering", new String[] {"accent", "leather", "steel"}, new float[] {0.417F, 1.209F, -0.445F}, new float[] {0.608F, 1.429F, -0.174F}, new float[] {0.512F, 1.319F, -0.309F}).turn(STEER, new float[] {0.766F, -0.643F, 0.000F}, 90.0F);
        control("heli", "collective", new String[] {"black", "steel"}, new float[] {0.752F, 0.660F, 0.002F}, new float[] {1.030F, 0.752F, 0.055F}, new float[] {0.758F, 0.669F, 0.028F}).turn(LIFT, new float[] {0.000F, 0.000F, 1.000F}, 10.0F);
        control("heli", "cyclic", new String[] {"dark", "red"}, new float[] {1.075F, 0.463F, 0.215F}, new float[] {1.154F, 0.740F, 0.285F}, new float[] {1.139F, 0.475F, 0.250F}).turn(PITCH, new float[] {0.000F, 0.000F, 1.000F}, 12.0F).turn2(ROLL, new float[] {1.000F, 0.000F, 0.000F}, 12.0F);
        control("heli", "pedalL", new String[] {"steel"}, new float[] {1.191F, 0.453F, 0.109F}, new float[] {1.224F, 0.572F, 0.204F}, new float[] {1.208F, 0.456F, 0.156F}).move(YAW, new float[] {-0.028F, 0.000F, 0.000F});
        control("heli", "pedalR", new String[] {"steel"}, new float[] {1.191F, 0.453F, 0.296F}, new float[] {1.224F, 0.572F, 0.391F}, new float[] {1.208F, 0.456F, 0.344F}).move(YAW, new float[] {0.028F, 0.000F, 0.000F});
        control("plane", "pedalL", new String[] {"steel"}, new float[] {1.230F, 0.465F, -0.241F}, new float[] {1.263F, 0.610F, -0.146F}, new float[] {1.247F, 0.469F, -0.194F}).move(YAW, new float[] {-0.028F, 0.000F, 0.000F});
        control("plane", "pedalR", new String[] {"steel"}, new float[] {1.230F, 0.465F, -0.066F}, new float[] {1.263F, 0.610F, 0.029F}, new float[] {1.247F, 0.469F, -0.019F}).move(YAW, new float[] {0.028F, 0.000F, 0.000F});
        control("plane", "throttle", new String[] {"black", "steel"}, new float[] {1.178F, 0.652F, 0.100F}, new float[] {1.228F, 0.801F, 0.150F}, new float[] {1.203F, 0.656F, 0.125F}).turn(THROTTLE, new float[] {0.000F, 0.000F, 1.000F}, -14.0F);
        // aircraft stage C (veh_real8.py, docs/mockups/veh_air2_5_cockpit.png): the flap lever (steel rod, the «wing» knob
        // 'aid') in its gate on the quadrant - modelled at the middle (-10 deg), +-22 deg with the flap position: 0 at 12 deg
        // forward, the last setting 32 deg back; the trim wheel moved onto the quadrant's left face (dark rim, steel / black
        // knurls, the 'aid' index; the axle's end cap on the quadrant face stays) turns up to 120 deg with the trim. Each box
        // holds its control's triangles (224 / 846) and no other of those materials (scratchpad vehicles/airC).
        control("plane", "flapLever", new String[] {"aid", "steel"}, new float[] {1.172F, 0.651F, 0.169F}, new float[] {1.230F, 0.755F, 0.212F}, FLAP_LEVER_PIVOT).turn(FLAPS, new float[] {0.000F, 0.000F, 1.000F}, FLAP_LEVER_SWING / 2);
        control("plane", "trimWheel", new String[] {"aid", "black", "dark", "steel"}, new float[] {1.120F, 0.492F, 0.062F}, new float[] {1.255F, 0.631F, 0.0935F}, new float[] {1.1875F, 0.559375F, 0.07875F}).turn(TRIM, new float[] {0.000F, 0.000F, 1.000F}, 120.0F);
        control("plane", "yoke", new String[] {"accent", "dark", "steel"}, new float[] {1.096F, 0.622F, -0.245F}, new float[] {1.204F, 0.726F, 0.054F}, new float[] {1.128F, 0.684F, -0.096F}).turn(ROLL, new float[] {0.800F, -0.600F, 0.000F}, 30.0F).move(PITCH, new float[] {-0.030F, 0.022F, -0.000F});
        // stage D3 (★13, veh_real11.py): the jet nozzle's 18 petals ('pet0'..'pet17'), each turned about its root's tangent
        // (0, cos a, -sin a) through its root (ROOT_X, AXIS_Y + R sin a, R cos a), a = k x 20 deg; the box is the whole
        // ring (the tag picks the petal)
        for (int k = 0; k < NozzleMath.PETALS; k++) {
            double a = k * Math.PI * 2 / NozzleMath.PETALS;
            float sa = (float) Math.sin(a), ca = (float) Math.cos(a);
            Def d = new Def("jet", "petal" + k, DEVICE, new String[] {"steel"}, new float[] {-3.0F, 0.33F, -0.23F}, new float[] {-2.82F, 0.79F, 0.23F},
                    new float[] {NozzleMath.ROOT_X, NozzleMath.AXIS_Y + NozzleMath.R_ROOT * sa, NozzleMath.R_ROOT * ca});
            d.tag = "pet" + k;
            d.device = PETAL;
            d.axis1 = new float[] {0F, ca, -sa};
            d.ang1 = 1F;
            d.move = new float[] {0F, 0F, 0F};
            d.minTris = 12;
            add(d);
        }
        for (List<Def> l : TABLE.values()) {
            ALL.addAll(l);
        }
    }

    private AnimParts() {
    }

    /** The animated parts of a model (empty for most). */
    public static List<Def> of(String model) {
        List<Def> l = TABLE.get(model);
        return l == null ? Collections.<Def>emptyList() : l;
    }

    /** Every Def (self-test). */
    public static List<Def> all() {
        return ALL;
    }

    /** ★15: a trailer model's wheels, rows {x, y, |z|, radius, box half-size, |z| in, |z| out}, both sides. */
    private static void trailerWheels(String model, float[][] rows) {
        for (int i = 0; i < rows.length; i++) {
            float[] r = rows[i];
            for (int s = -1; s <= 1; s += 2) {
                wheel(model, "wheel" + i + (s > 0 ? "R" : "L"), TYRE, r[0], r[1], s * r[2], r[4], r[4], r[5], r[6], s, r[3]);
            }
        }
    }

    /** A wheel: centre (x, y, z); box x +-dxFront/back, y centre +-dy..., |z| zIn..zOut on side s (0: centred +-zOut). */
    private static Def wheel(String model, String name, String[] groups, float x, float y, float z, float dx, float dy, float zIn, float zOut,
            int s, float radius) {
        Def d = box(model, name, WHEEL, groups, x - dx, y - dy, zIn, x + dx, y + dy, zOut, s, new float[] {x, y, z});
        d.radius = radius;
        d.axis = Z;
        return d;
    }

    /** A CONTROL part: the box (model units) and the point it turns about; its inputs are set by turn / turn2 / move. */
    private static Def control(String model, String name, String[] groups, float[] min, float[] max, float[] pivot) {
        Def d = box(model, name, CONTROL, groups, min[0], min[1], min[2], max[0], max[1], max[2], 0, pivot);
        d.minTris = 100;
        return d;
    }

    /** Stage D3: a fixed spring leg's part (the light plane) - flexes about x through its root (GearMath.flex). */
    private static void flex(Def d, float[] root) {
        d.gearPivot = root;
        d.flex = true;
    }

    /**
     * Stage D3: a retractable leg's tagged part (gen_rows.py): box = the tag's triangles on that side, pivot = the wheel's
     * axle / the torque link's hinge / the leg's pivot; retracts aft 90 deg about z through legPivot.
     */
    private static Def leg(String model, String name, int kind, String tag, String[] groups, float[] min, float[] max, float[] pivot, float[] legPivot,
            int tris) {
        Def d = new Def(model, name, kind, groups, min, max, pivot);
        d.tag = tag;
        d.minTris = tris;
        gear(d, legPivot, Z, -90F, 0F);
        add(d);
        return d;
    }

    /** Stage D3: a gear door (tagged, modelled open): closed by -90 deg about `axis` through its hinge point. */
    private static Def door(String model, String name, String tag, String[] groups, float[] min, float[] max, float[] pivot, float[] axis, int tris) {
        Def d = new Def(model, name, DEVICE, groups, min, max, pivot);
        d.tag = tag;
        d.device = DOOR;
        d.axis1 = axis;
        d.ang1 = -90F;
        d.move = new float[] {0F, 0F, 0F};
        d.minTris = tris;
        add(d);
        return d;
    }

    private static void add(Def d) {
        List<Def> l = TABLE.get(d.model);
        if (l == null) {
            l = new ArrayList<Def>();
            TABLE.put(d.model, l);
        }
        l.add(d);
    }

    private static void gear(Def d, float[] pivot, int axis, float angle, float lift) {
        d.gearPivot = pivot;
        d.gearAxis = axis;
        d.gearAngle = angle;
        d.gearLift = lift;
    }

    private static Def surface(String model, String name, String[] groups, float x0, float y0, float z0, float x1, float y1, float z1, int s,
            float[] pivot, int axis, int control, float gain) {
        Def d = box(model, name, SURFACE, groups, x0, y0, z0, x1, y1, z1, s, pivot);
        d.axis = axis;
        d.control = control;
        d.gain = gain;
        d.cut = true;
        d.minTris = 2;
        return d;
    }

    /**
     * Stage A: a tagged high-lift device, numbers for the RIGHT side (s = 1; s = -1 mirrors: the box and the pivot's /
     * the move's z, the axis as a mirrored rotation (-x, -y, z); s = 0 as given). deg / move at the reference deflection ref.
     */
    private static Def device(String model, String name, int kind, String tag, String[] groups, float x0, float y0, float z0, float x1,
            float y1, float z1, int s, float[] pivot, float[] axis, float deg, float ref, float[] move) {
        boolean m = s < 0;
        Def d = box(model, name, DEVICE, groups, x0, y0, z0, x1, y1, z1, s,
                new float[] {pivot[0], pivot[1], m ? -pivot[2] : pivot[2]});
        d.tag = tag;
        d.device = kind;
        d.axis1 = m ? new float[] {-axis[0], -axis[1], axis[2]} : axis;
        d.ang1 = deg;
        d.refDeg = ref;
        d.move = new float[] {move[0], move[1], m ? -move[2] : move[2]};
        d.minTris = 8;
        return d;
    }

    /**
     * A box part. z0..z1 are given for the RIGHT side (s = 1: z0 <= z <= z1); s = -1 mirrors them (-z1 .. -z0);
     * s = 0: the box is centred, z0..z1 used as they are.
     */
    private static Def box(String model, String name, int kind, String[] groups, float x0, float y0, float z0, float x1, float y1, float z1,
            int s, float[] pivot) {
        float lo = s < 0 ? -z1 : z0, hi = s < 0 ? -z0 : z1;
        Def d = new Def(model, name, kind, groups, new float[] {x0, y0, lo}, new float[] {x1, y1, hi}, pivot);
        List<Def> l = TABLE.get(model);
        if (l == null) {
            l = new ArrayList<Def>();
            TABLE.put(model, l);
        }
        l.add(d);
        return d;
    }

    // ------------------------------------------------------------------ clipping (cut parts)

    /**
     * Splits a convex polygon (vertices {x, y, z, nx, ny, nz, ...}) by the box [min, max]: returns the piece inside (null
     * if none) and adds the pieces outside to `outside` (each convex). Sutherland-Hodgman one plane at a time - what falls
     * outside a plane is set aside, the rest goes on to the next plane - so the pieces partition the polygon exactly.
     */
    public static float[][] clipBox(float[][] poly, float[] min, float[] max, List<float[][]> outside) {
        float[][] cur = poly;
        for (int k = 0; k < 6 && cur != null; k++) {
            int ax = k >> 1;
            boolean low = (k & 1) == 0;
            float edge = low ? min[ax] : max[ax];
            List<float[]> in = new ArrayList<float[]>(), out = new ArrayList<float[]>();
            for (int i = 0; i < cur.length; i++) {
                float[] p = cur[i], q = cur[(i + 1) % cur.length];
                float dp = low ? p[ax] - edge : edge - p[ax], dq = low ? q[ax] - edge : edge - q[ax];
                if (dp >= 0) {
                    in.add(p);
                } else {
                    out.add(p);
                }
                if (dp > 0 && dq < 0 || dp < 0 && dq > 0) {
                    float[] m = lerp(p, q, dp / (dp - dq));
                    in.add(m);
                    out.add(m);
                }
            }
            if (out.size() >= 3 && area(out) > 1e-9F) {
                outside.add(out.toArray(new float[out.size()][]));
            }
            cur = in.size() >= 3 && area(in) > 1e-9F ? in.toArray(new float[in.size()][]) : null;
        }
        return cur;
    }

    private static float[] lerp(float[] p, float[] q, float t) {
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            r[i] = p[i] + (q[i] - p[i]) * t;
        }
        if (r.length >= 6) {                                    // the normal stays unit length
            float l = (float) Math.sqrt(r[3] * r[3] + r[4] * r[4] + r[5] * r[5]);
            if (l > 1e-6F) {
                r[3] /= l;
                r[4] /= l;
                r[5] /= l;
            }
        }
        return r;
    }

    /** Area of a planar polygon in 3D (fan of cross products). */
    public static float area(List<float[]> poly) {
        return area(poly.toArray(new float[poly.size()][]));
    }

    public static float area(float[][] poly) {
        double sx = 0, sy = 0, sz = 0;
        float[] a = poly[0];
        for (int i = 1; i + 1 < poly.length; i++) {
            float[] b = poly[i], c = poly[i + 1];
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            sx += uy * vz - uz * vy;
            sy += uz * vx - ux * vz;
            sz += ux * vy - uy * vx;
        }
        return (float) (Math.sqrt(sx * sx + sy * sy + sz * sz) / 2);
    }
}
