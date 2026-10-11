package com.sva.vehicle;

/**
 * Wave 1, ★1 (mock-up veh_more_1_sound.png): which looping sounds a vehicle makes and how loud / how high each one is -
 * pure numbers (client/VehicleSoundsSVA plays them; the self-test checks the mapping and that every file is in the jar).
 * <p>
 * The loops (assets/siliconagevehicles/sounds/*.ogg, made by scratchpad vehicles/sounds_gen.py: mono 22 kHz, 1-3 s,
 * seamless) are LAYERS: the electric motor (buggy; the crawler and the drill play it lower), the hover hum, the
 * helicopter's blade slap + the turbine whistle, the propeller drone, the jet turbine + the afterburner roar, the
 * antigravity hum (shuttle; ship lower + the core's pulse), the track clank, the drill's grind, the wind at speed. Each
 * file but the wind also comes as NAME_in: low-passed - what the rider hears from inside a closed cabin (first person),
 * played INSIDE_VOL quieter and INSIDE_PITCH lower.
 * <p>
 * Pitch follows the speed / throttle / rotor rate, volume follows the load; a motor that is off is silent (the tracks
 * and the wind still sound while it rolls). Volumes are 0..1 before the config's vehicles.soundVolume and the game's
 * «Игроки» sound slider (sounds.json category "player"); pitch is kept in the game's 0.5..2.
 */
public final class SoundMath {

    public static final int ELECTRIC = 0, HOVER = 1, BLADES = 2, TURBINE = 3, PROP = 4, JET = 5, AFTER = 6, ANTIGRAV = 7,
            CORE = 8, TRACKS = 9, DRILL = 10, WIND = 11;
    /** File names by layer (sounds.json events of the same name). */
    public static final String[] FILES = {"electric", "hover", "heli_blades", "turbine", "propeller", "jet", "afterburner",
            "antigrav", "core_pulse", "tracks", "drill", "wind"};
    /** One-shot events (sounds.json): boarding, cargo hatch, tyre squeak on touch-down, a hit, charge done, hull alarm, ore. */
    public static final String[] ONE_SHOTS = {"door", "cargo", "squeak", "impact", "ding", "alarm", "drill_ore"};
    /** Inside a closed cabin: x this volume, x this pitch (and the _in file). */
    public static final float INSIDE_VOL = 0.6F, INSIDE_PITCH = 0.92F, INSIDE_WIND = 0.4F;
    /** Loops are heard this far (blocks) - started with a volume that makes the game's linear fall-off reach it. */
    public static final double RANGE = 48;
    /** Heli rotor rate (deg/tick) at full power; the propellers' top rate (18 + 50 at full throttle). */
    public static final float HELI_RATE = 42F, PROP_RATE = 68F;

    private SoundMath() {
    }

    /** What a vehicle is doing this tick, as the sounds need it (the client fills it from the synced state). */
    public static final class Input {
        /** Motor running: someone drives, it has charge, not a wreck, not overheated. */
        public boolean engine;
        /** Speed over the ground / its top speed (0..~1.3). */
        public double speedFrac;
        /** Speed in km/h (the wind). */
        public double kmh;
        /** Plane / jet throttle 0..1 (synced). */
        public double throttle;
        /** Rotor / propeller rate, deg/tick (EntityVehicleSVA.rotorRate). */
        public double rotor;
        public boolean boost, drilling, accelerating;
        /** Round 2, D2: the engine start's progress 0..1 (SystemsMath.progress), -1 when not starting. */
        public double spool = -1;
    }

    /** The layers a type plays. */
    public static int[] layers(VehicleType t) {
        switch (t) {
            case BUGGY:
                return new int[] {ELECTRIC, WIND};
            case HOVER:
                return new int[] {HOVER, WIND};
            case HELI:
                return new int[] {BLADES, TURBINE, WIND};
            case PLANE:
                return new int[] {PROP, WIND};
            case TRUCK:
                return new int[] {ELECTRIC, TRACKS};
            case DRILL:
                return new int[] {ELECTRIC, TRACKS, DRILL};
            case JET:
                return new int[] {JET, AFTER, WIND};
            case TILT:
                return new int[] {PROP, TURBINE, WIND};
            case SHUTTLE:
                return new int[] {ANTIGRAV, WIND};
            case SHIP:
                return new int[] {ANTIGRAV, CORE, WIND};
            default:
                return new int[] {ELECTRIC};
        }
    }

    /** A closed cabin: the riders hear the muffled files from inside (the buggy's roll cage and the hoverbike are open). */
    public static boolean closed(VehicleType t) {
        return t != VehicleType.BUGGY && t != VehicleType.HOVER;
    }

    /** The file a layer plays (inside: the _in variant; the wind has none). */
    public static String file(int layer, boolean inside) {
        return inside && layer != WIND ? FILES[layer] + "_in" : FILES[layer];
    }

    private static double c01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** The electric motor's base pitch: the crawler and the drill hum lower. */
    public static double electricBase(VehicleType t) {
        return t == VehicleType.DRILL ? 0.6 : t == VehicleType.TRUCK ? 0.72 : 1.0;
    }

    /** {volume 0..1, pitch 0.5..2} of a layer for this input (before the inside / config factors). */
    public static float[] mix(int layer, VehicleType t, Input in) {
        double sf = c01(in.speedFrac);
        double vol, pitch;
        switch (layer) {
            case ELECTRIC:
                vol = in.engine ? 0.25 + 0.45 * sf + (in.accelerating ? 0.1 : 0) : 0;
                pitch = electricBase(t) * (0.75 + 0.75 * sf + (in.accelerating ? 0.05 : 0));
                break;
            case HOVER:
                vol = in.engine ? 0.35 + 0.35 * sf + (in.boost ? 0.15 : 0) : 0;
                pitch = 0.8 + 0.4 * sf + (in.boost ? 0.15 : 0);
                break;
            case BLADES: {
                double r = c01(in.rotor / HELI_RATE);
                vol = r < 0.05 ? 0 : 0.2 + 0.5 * r + 0.1 * sf;
                pitch = 0.6 + 0.5 * r;
                break;
            }
            case TURBINE: {
                double load = Math.max(c01(in.throttle), sf);
                vol = in.engine ? 0.22 + 0.2 * load : 0;           // autorotation: the blades only
                pitch = 0.85 + 0.3 * load;
                if (in.spool >= 0) {
                    vol = Math.max(vol, 0.08 + 0.2 * in.spool);     // D2: the start's spool-up whine
                    pitch = 0.5 + 0.4 * in.spool;
                }
                break;
            }
            case PROP: {
                double r = c01(in.rotor / PROP_RATE);
                vol = r < 0.05 ? 0 : 0.15 + 0.55 * r;
                pitch = 0.5 + 0.9 * r;
                break;
            }
            case JET: {
                double thr = c01(in.throttle);
                vol = in.engine ? 0.35 + 0.45 * thr : 0;
                pitch = 0.7 + 0.6 * thr + 0.1 * sf;
                if (in.spool >= 0) {
                    vol = 0.1 + 0.3 * in.spool;                     // D2: spool-up - the whine's pitch = the turbine's speed
                    pitch = 0.5 + 0.25 * in.spool;
                }
                break;
            }
            case AFTER:
                vol = in.engine && in.boost ? 0.8 : 0;
                pitch = 1.0;
                break;
            case ANTIGRAV:
                vol = in.engine ? 0.3 + 0.3 * sf + (in.boost ? 0.1 : 0) : 0;
                pitch = (t == VehicleType.SHIP ? 0.75 : 1.0) * (0.85 + 0.35 * sf);
                break;
            case CORE:
                vol = in.engine ? 0.35 : 0;
                pitch = in.boost ? 1.1 : 1.0;
                break;
            case TRACKS:
                vol = sf < 0.02 ? 0 : 0.2 + 0.5 * c01(sf * 1.5);
                pitch = 0.7 + 0.6 * sf;
                break;
            case DRILL:
                vol = in.drilling ? 0.75 : 0;
                pitch = 1.0;
                break;
            case WIND: {
                double w = c01((in.kmh - 40) / 120);
                vol = w <= 0 ? 0 : 0.1 + 0.5 * w;
                pitch = 0.8 + 0.4 * w;
                break;
            }
            default:
                vol = 0;
                pitch = 1;
        }
        return new float[] {(float) c01(vol), (float) Math.max(0.5, Math.min(2.0, pitch))};
    }

    /**
     * The final {volume, pitch}: x the inside factors (wind: x INSIDE_WIND), x the config volume; volume 0..1, pitch
     * 0.5..2.
     */
    public static float[] finalMix(int layer, VehicleType t, Input in, boolean inside, double configVolume) {
        float[] m = mix(layer, t, in);
        double v = m[0], p = m[1];
        if (inside) {
            v *= layer == WIND ? INSIDE_WIND : INSIDE_VOL;
            p *= layer == WIND ? 1 : INSIDE_PITCH;
        }
        double cfg = Double.isNaN(configVolume) ? 1 : Math.max(0, configVolume);
        return new float[] {(float) c01(v * cfg), (float) Math.max(0.5, Math.min(2.0, p))};
    }

    /** The volume a loop is started with so the game's linear fall-off (16 x volume blocks when over 1) reaches RANGE. */
    public static float rangeVolume() {
        return (float) (RANGE / 16.0);
    }
}
